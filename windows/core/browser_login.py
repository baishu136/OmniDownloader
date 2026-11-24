"""User-driven Edge sign-in shared by the web and desktop clients.

Only app-owned profiles are opened. Google SSO state remains in that profile;
only the selected service's first-party cookies enter the encrypted vault.
"""
import atexit
from contextlib import contextmanager, nullcontext
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
from urllib.parse import urlsplit

from core.session_store import DOMAINS, file_lock, sessions

LOGIN_URLS = {
    'YouTube': 'https://www.youtube.com/', 'X (Twitter)': 'https://x.com/i/flow/login',
    '小红书': 'https://www.xiaohongshu.com/', '小黑盒': 'https://www.xiaoheihe.cn/',
    'Pixiv': 'https://www.pixiv.net/', '抖音': 'https://www.douyin.com/',
    '哔哩哔哩': 'https://passport.bilibili.com/login',
    'Instagram': 'https://www.instagram.com/', 'Facebook': 'https://www.facebook.com/',
    'TikTok': 'https://www.tiktok.com/', '快手': 'https://www.kuaishou.com/',
    '微博': 'https://weibo.com/'}
AUTH_NAMES = {
    'YouTube': {'SAPISID', '__Secure-1PAPISID', '__Secure-3PAPISID'},
    'X (Twitter)': {'auth_token'}, '小红书': {'web_session'}, 'Pixiv': {'PHPSESSID'},
    '抖音': {'sessionid', 'sessionid_ss'}, '哔哩哔哩': {'SESSDATA'},
    'Instagram': {'sessionid'}, 'Facebook': {'c_user'},
    'TikTok': {'sessionid', 'sessionid_ss'}, '微博': {'SUB'}}


def cookie_text(platform, cookies):
    """Lossless HttpOnly Netscape export for unpartitioned first-party cookies."""
    rows, names, skipped = {}, set(), 0
    for cookie in cookies:
        domain = str(cookie.get('domain', ''))
        host = domain.lstrip('.').lower()
        if not any(host == d or host.endswith('.' + d) for d in DOMAINS[platform]): continue
        if cookie.get('partitionKey') or cookie.get('partitionKeyOpaque'):
            skipped += 1
            continue  # A regular HTTP cookie jar cannot represent CHIPS safely.
        name, value, path = str(cookie.get('name', '')), str(cookie.get('value', '')), str(cookie.get('path') or '/')
        if not name or any(c in domain + name + value + path for c in '\t\r\n\0'): continue
        expiry = 0 if cookie.get('session') or cookie.get('expires', -1) < 0 else int(cookie['expires'])
        if expiry and expiry <= time.time(): continue
        fields = [('#HttpOnly_' if cookie.get('httpOnly') else '') + domain,
                  'TRUE' if domain.startswith('.') else 'FALSE', path,
                  'TRUE' if cookie.get('secure') else 'FALSE', str(expiry), name, value]
        rows[(domain, path, name)] = '\t'.join(fields)
        names.add(name)
    return '# Netscape HTTP Cookie File\n' + '\n'.join(rows[k] for k in sorted(rows)) + '\n', names, skipped


@contextmanager
def edge_profile(profile, url='about:blank', proxy='', visible=True, *, _leased=False, _no_pages=False, _restore_session=False):
    from engine.automatic_douyin import CDP, edge_executable
    from websockets.sync.client import connect
    executable = edge_executable()
    if not executable: raise RuntimeError('[EDGE_UNAVAILABLE] 请安装 Microsoft Edge 后重试')
    profile = Path(profile)
    profile.mkdir(parents=True, exist_ok=True)
    with (nullcontext() if _leased else file_lock(profile.parent / (profile.name + '.lock'), timeout=.1)):
        # 若存在残留调试端口，先检查是否为无窗口内部残留进程（--no-startup-window），是则自动清理后再检查端口
        port_file = profile / 'DevToolsActivePort'
        if port_file.exists():
            orphan_pids = _find_profile_browser_pids(profile)
            if orphan_pids and not _visible_profile_windows(orphan_pids):
                _ensure_profile_unlocked(profile, reject_visible=True)
            import socket
            try:
                port = int(port_file.read_text('utf-8').splitlines()[0])
                with socket.create_connection(('127.0.0.1', port), timeout=.3):
                    raise RuntimeError('[PROFILE_BUSY] 请关闭之前的 OmniDownloader 登录窗口后重试')
            except (OSError, ValueError, IndexError): pass
            port_file.unlink(missing_ok=True)
        # 清理此前异常退出或兼容性重启残留的无窗口幽灵 Edge 进程（仅限本应用专用目录）
        _ensure_profile_unlocked(profile, reject_visible=True)
        command = [str(executable), '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
                   f'--user-data-dir={profile}', '--no-first-run', '--no-default-browser-check',
                   '--disable-background-mode', '--edge-skip-compat-layer-relaunch',
                   '--no-startup-window' if _no_pages else '--new-window']
        if _restore_session: command += ['--restore-last-session']
        if not visible: command += ['--headless=new']
        if proxy:
            parsed = urlsplit(proxy)
            if parsed.scheme not in ('http', 'https', 'socks5', 'socks5h') or not parsed.hostname or not parsed.port or parsed.username or parsed.password:
                raise ValueError('[PROXY_UNSUPPORTED] 登录浏览器需要不含账号密码的 HTTP 或 SOCKS5 代理')
            command += ['--proxy-server=' + proxy.replace('socks5h://', 'socks5://')]
        if not _no_pages: command.append(url)
        process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        connection = connection_context = None
        try:
            deadline = time.monotonic() + 15
            while not port_file.is_file():
                if process.poll() is not None:
                    time.sleep(.2)
                    if not _find_profile_browser_pids(profile):
                        raise RuntimeError('[PROFILE_BUSY] 浏览器未启动，请关闭先前登录窗口后重试')
                if time.monotonic() > deadline: raise RuntimeError('[DEBUG_UNAVAILABLE] Edge 调试通道不可用，请检查浏览器策略')
                time.sleep(.1)
            lines = port_file.read_text('utf-8').splitlines()
            port = int(lines[0])
            endpoint = lines[1]
            if not 0 < port < 65536 or not endpoint.startswith('/devtools/browser/'):
                raise RuntimeError('[DEBUG_UNAVAILABLE] 浏览器未提供有效的本机会话通道')
            connection_context = connect(f'ws://127.0.0.1:{port}{endpoint}', open_timeout=5, close_timeout=1,
                                         max_size=8*1024*1024, proxy=None)
            connection = connection_context.__enter__()
            yield CDP(connection)
        finally:
            if connection:
                try: CDP(connection).call('Browser.close', timeout=2)
                except Exception: pass
                connection_context.__exit__(None, None, None)
            try: process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.terminate()
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=3)
            for pid in _find_profile_browser_pids(profile):
                _terminate_pid_tree(pid, graceful_wait=1.5)
            _wait_profile_released(profile, timeout=2.5)
            port_file.unlink(missing_ok=True)


def normal_edge_command(profile, url, proxy=''):
    """Interactive authentication must not advertise WebDriver or expose CDP."""
    from engine.automatic_douyin import edge_executable
    executable = edge_executable()
    if not executable: raise RuntimeError('[EDGE_UNAVAILABLE] 请安装 Microsoft Edge 后重试')
    command = [str(executable), f'--user-data-dir={Path(profile).resolve()}', '--no-first-run',
               '--no-default-browser-check', '--disable-background-mode', '--restore-last-session',
               '--new-window', '--edge-skip-compat-layer-relaunch']
    if proxy:
        parsed = urlsplit(proxy)
        if parsed.scheme not in ('http', 'https', 'socks5', 'socks5h') or not parsed.hostname or not parsed.port or parsed.username or parsed.password:
            raise ValueError('[PROXY_UNSUPPORTED] 登录浏览器需要不含账号密码的 HTTP 或 SOCKS5 代理')
        command += ['--proxy-server=' + proxy.replace('socks5h://', 'socks5://')]
    command.append(url)
    return command


def _owned_process_tree_pids(root_pid):
    """Collect root_pid and its descendant PIDs without invoking external tools."""
    pids = {int(root_pid)}
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        TH32CS_SNAPPROCESS = 0x00000002
        INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

        class PROCESSENTRY32W(ctypes.Structure):
            _fields_ = [
                ('dwSize', wintypes.DWORD),
                ('cntUsage', wintypes.DWORD),
                ('th32ProcessID', wintypes.DWORD),
                ('th32DefaultHeapID', ctypes.c_size_t),
                ('th32ModuleID', wintypes.DWORD),
                ('cntThreads', wintypes.DWORD),
                ('th32ParentProcessID', wintypes.DWORD),
                ('pcPriClassBase', ctypes.c_long),
                ('dwFlags', wintypes.DWORD),
                ('szExeFile', wintypes.WCHAR * 260),
            ]

        snap = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if not snap or snap == INVALID_HANDLE_VALUE:
            return pids
        try:
            entry = PROCESSENTRY32W()
            entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
            edges = []
            if kernel32.Process32FirstW(snap, ctypes.byref(entry)):
                while True:
                    edges.append((entry.th32ProcessID, entry.th32ParentProcessID))
                    if not kernel32.Process32NextW(snap, ctypes.byref(entry)):
                        break
            changed = True
            while changed:
                changed = False
                for child_pid, parent_pid in edges:
                    if parent_pid in pids and child_pid not in pids:
                        pids.add(child_pid)
                        changed = True
        finally:
            kernel32.CloseHandle(snap)
    except Exception:
        pass
    return pids


def close_owned_edge_windows(pid):
    """Ask only this launched browser's windows to close, allowing cookie flush.

    No taskkill, process-tree termination, or interaction with daily profiles.
    A beforeunload dialog can block closure; the caller reports that honestly.
    """
    if os.name != 'nt': raise RuntimeError('[UNSUPPORTED_PLATFORM] 普通登录窗口目前仅支持 Windows')
    import ctypes
    from ctypes import wintypes
    owned_pids = _owned_process_tree_pids(pid)
    user32 = ctypes.WinDLL('user32', use_last_error=True)
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    @callback_type
    def visit(window, _):
        owner = wintypes.DWORD(); user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value in owned_pids:
            name = ctypes.create_unicode_buffer(128); user32.GetClassNameW(window, name, len(name))
            if name.value.startswith('Chrome_WidgetWin_'): user32.PostMessageW(window, 0x0010, 0, 0)
        return True
    user32.EnumWindows(visit, 0)


def _process_command_line(pid):
    """使用 Win32 NtQueryInformationProcess 安全读取指定进程命令行，不依赖外部工具。"""
    if os.name != 'nt' or not pid: return ''
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        ntdll = ctypes.WinDLL('ntdll')

        class UNICODE_STRING(ctypes.Structure):
            _fields_ = [('Length', wintypes.USHORT), ('MaximumLength', wintypes.USHORT), ('Buffer', ctypes.c_wchar_p)]

        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
        if not handle: return ''
        try:
            needed = wintypes.ULONG(0)
            ntdll.NtQueryInformationProcess(handle, 60, None, 0, ctypes.byref(needed))
            size = max(int(needed.value) + 64, 1024)
            buf = ctypes.create_string_buffer(size)
            status = ntdll.NtQueryInformationProcess(handle, 60, buf, size, ctypes.byref(needed))
            if status != 0 and needed.value > size:
                size = int(needed.value) + 64
                buf = ctypes.create_string_buffer(size)
                status = ntdll.NtQueryInformationProcess(handle, 60, buf, size, ctypes.byref(needed))
            if status == 0:
                us = ctypes.cast(buf, ctypes.POINTER(UNICODE_STRING)).contents
                if us.Buffer and us.Length:
                    return ctypes.wstring_at(us.Buffer, us.Length // 2)
        finally:
            kernel32.CloseHandle(handle)
    except Exception:
        pass
    return ''


def _is_pid_alive(pid):
    """检查给定 Windows PID 是否仍在运行且尚未退出。"""
    if os.name != 'nt' or not pid: return False
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
        if not handle: return False
        try:
            code = wintypes.DWORD(0)
            return bool(kernel32.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    except Exception:
        return False


def _find_profile_browser_pids(profile):
    """定位绑定在指定独立登录档案目录上的全部 Edge 主进程（不含渲染/工具子进程）。"""
    if os.name != 'nt' or not profile: return []
    try:
        target_norm = os.path.normcase(os.path.realpath(str(Path(profile).resolve())))
    except OSError:
        return []
    pids = []
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        TH32CS_SNAPPROCESS = 0x00000002
        INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

        class PROCESSENTRY32W(ctypes.Structure):
            _fields_ = [
                ('dwSize', wintypes.DWORD),
                ('cntUsage', wintypes.DWORD),
                ('th32ProcessID', wintypes.DWORD),
                ('th32DefaultHeapID', ctypes.c_size_t),
                ('th32ModuleID', wintypes.DWORD),
                ('cntThreads', wintypes.DWORD),
                ('th32ParentProcessID', wintypes.DWORD),
                ('pcPriClassBase', ctypes.c_long),
                ('dwFlags', wintypes.DWORD),
                ('szExeFile', wintypes.WCHAR * 260),
            ]

        snap = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if not snap or snap == INVALID_HANDLE_VALUE: return []
        try:
            entry = PROCESSENTRY32W()
            entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
            edge_pids = []
            if kernel32.Process32FirstW(snap, ctypes.byref(entry)):
                while True:
                    if str(entry.szExeFile).lower() == 'msedge.exe':
                        edge_pids.append(int(entry.th32ProcessID))
                    if not kernel32.Process32NextW(snap, ctypes.byref(entry)):
                        break
        finally:
            kernel32.CloseHandle(snap)

        for pid in edge_pids:
            cmd = _process_command_line(pid)
            if not cmd or re.search(r'(?:^|\s)--type=', cmd): continue
            match = re.search(r'--user-data-dir=(?:"([^"]+)"|(\S+))', cmd)
            if not match: continue
            raw_dir = match.group(1) or match.group(2) or ''
            try:
                if os.path.normcase(os.path.realpath(raw_dir)) == target_norm:
                    pids.append(pid)
            except OSError:
                continue
    except Exception:
        pass
    return pids


def _visible_profile_windows(pids):
    """返回指定浏览器进程树中所有真实可见窗口句柄列表。"""
    if os.name != 'nt' or not pids: return []
    owned = set()
    for p in pids:
        owned.update(_owned_process_tree_pids(p))
    found = []
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.WinDLL('user32', use_last_error=True)
        user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
        user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
        user32.IsWindowVisible.argtypes = [wintypes.HWND]
        user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]

        @callback_type
        def visit(hwnd, _):
            owner = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
            if owner.value in owned and user32.IsWindowVisible(hwnd):
                name = ctypes.create_unicode_buffer(128)
                user32.GetClassNameW(hwnd, name, len(name))
                if name.value.startswith('Chrome_WidgetWin_'):
                    rect = wintypes.RECT()
                    if user32.GetWindowRect(hwnd, ctypes.byref(rect)) and (rect.right - rect.left) > 40 and (rect.bottom - rect.top) > 40:
                        found.append((hwnd, owner.value))
            return True

        user32.EnumWindows(visit, 0)
    except Exception:
        pass
    return found


def _focus_profile_windows(pids):
    """将已打开的独立登录浏览器窗口恢复并前置显示。"""
    wins = _visible_profile_windows(pids)
    if not wins: return False
    try:
        import ctypes
        user32 = ctypes.WinDLL('user32', use_last_error=True)
        for hwnd, _ in wins:
            if user32.IsIconic(hwnd):
                user32.ShowWindow(hwnd, 9)  # SW_RESTORE
            else:
                user32.ShowWindow(hwnd, 5)  # SW_SHOW
            user32.SetForegroundWindow(hwnd)
        return True
    except Exception:
        return False


def _terminate_pid_tree(pid, graceful_wait=1.5):
    """仅终止本应用独占登录目录的无窗口残留进程；先留出优雅落盘窗口期。"""
    if os.name != 'nt' or not pid: return
    deadline = time.monotonic() + graceful_wait
    while time.monotonic() < deadline:
        if not _is_pid_alive(pid): return
        time.sleep(.08)
    close_owned_edge_windows(pid)
    post_close_deadline = time.monotonic() + .8
    while time.monotonic() < post_close_deadline:
        if not _is_pid_alive(pid): return
        time.sleep(.08)
    try:
        import ctypes
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        PROCESS_TERMINATE = 0x0001
        for child_pid in sorted(_owned_process_tree_pids(pid), reverse=True):
            handle = kernel32.OpenProcess(PROCESS_TERMINATE, False, int(child_pid))
            if handle:
                try: kernel32.TerminateProcess(handle, 1)
                finally: kernel32.CloseHandle(handle)
    except Exception:
        pass


def _is_lockfile_busy(profile):
    """检测 Edge Profile 单例 lockfile 是否仍被进程独占占用。"""
    lockfile = Path(profile) / 'lockfile'
    if not lockfile.exists(): return False
    try:
        os.rename(str(lockfile), str(lockfile))
        return False
    except PermissionError:
        return True
    except OSError:
        return False


def _wait_profile_released(profile, timeout=2.5):
    """等待 Edge 主进程及子进程完全释放 lockfile 句柄。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _find_profile_browser_pids(profile) and not _is_lockfile_busy(profile):
            return True
        time.sleep(.08)
    return not _find_profile_browser_pids(profile) and not _is_lockfile_busy(profile)


def _ensure_profile_unlocked(profile, reject_visible=True):
    """自动清理异常/兼容性重启遗留的无窗口幽灵 Edge 进程；若存在可见登录窗则由调用方决策。"""
    pids = _find_profile_browser_pids(profile)
    if pids:
        if _visible_profile_windows(pids):
            if reject_visible:
                raise RuntimeError('[PROFILE_BUSY] 请关闭该平台之前的专用登录窗口后重试')
            return pids
        # 所有匹配该登录档案的 Edge 进程均无可见窗口，属于无法被用户手动关闭的僵尸锁定进程
        for pid in pids:
            _terminate_pid_tree(pid, graceful_wait=.4)
        _wait_profile_released(profile, timeout=2.5)
    elif _is_lockfile_busy(profile):
        _wait_profile_released(profile, timeout=2.5)
    if _find_profile_browser_pids(profile):
        raise RuntimeError('[PROFILE_BUSY] 请关闭该平台之前的专用登录窗口后重试')
    return []


def profile_browser_pid(profile):
    """返回精确绑定在指定登录档案目录上的 Edge 主进程 PID。"""
    if os.name != 'nt': return 0
    import ctypes
    from ctypes import wintypes
    api = ctypes.WinDLL('user32', use_last_error=True)
    api.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]; api.FindWindowW.restype = wintypes.HWND
    api.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    window = api.FindWindowW('Chrome_MessageWindow', str(Path(profile).resolve()))
    owner = wintypes.DWORD()
    if window:
        api.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value: return owner.value
    pids = _find_profile_browser_pids(profile)
    return pids[0] if pids else 0


def ensure_session_restore(profile, proxy=''):
    """Let Edge write its own protected preference; never patch Secure Preferences.

    Only an internal, hidden settings target is opened before interactive login.
    No Google page is opened in this configuration process.
    """
    secure = Path(profile) / 'Default' / 'Secure Preferences'
    if secure.is_file():
        try:
            saved = json.loads(secure.read_text('utf-8'))
            if saved.get('session', {}).get('restore_on_startup') == 1: return
        except (ValueError, OSError, AttributeError):
            raise RuntimeError('[PROFILE_DATA_INVALID] 浏览器档案无法读取，原有数据已保留') from None
    from engine.automatic_douyin import CDP
    from websockets.sync.client import connect
    with edge_profile(profile, proxy=proxy, visible=True, _leased=True,
                      _no_pages=True, _restore_session=True) as browser:
        try:
            target = browser.call('Target.createTarget', {'url': 'edge://settings/startHomeNTP', 'hidden': True})['targetId']
        except RuntimeError:
            detail = browser.last_error or {}
            if detail.get('code') != -32000 or not detail.get('message', '').startswith('Hidden target can be created only'):
                raise RuntimeError('[SESSION_RESTORE_UNAVAILABLE] 浏览器未能打开会话恢复设置，请更新 Edge 或导入 Cookie 文件') from None
            # Some existing Edge profiles deny hidden targets, while ordinary
            # settings tabs remain available. This is a display capability only.
            target = browser.call('Target.createTarget', {'url': 'edge://settings/startHomeNTP', 'background': True})['targetId']
        port = int((Path(profile) / 'DevToolsActivePort').read_text('utf-8').splitlines()[0])
        with connect(f'ws://127.0.0.1:{port}/devtools/page/{target}', open_timeout=5,
                     close_timeout=1, max_size=1024*1024, proxy=None) as socket:
            page = CDP(socket)
            deadline = time.monotonic() + 5
            while True:
                result = page.call('Runtime.evaluate', {'expression': 'typeof chrome.settingsPrivate', 'returnByValue': True})
                if result.get('result', {}).get('value') == 'object': break
                if time.monotonic() >= deadline:
                    raise RuntimeError('[SESSION_RESTORE_UNAVAILABLE] 浏览器无法配置可靠会话保存，请更新 Edge 或导入 Cookie 文件')
                time.sleep(.1)
            read = "new Promise(r=>chrome.settingsPrivate.getPref('session.restore_on_startup',p=>r({value:p.value,enforcement:p.enforcement})))"
            def evaluate(expression):
                result = page.call('Runtime.evaluate', {'expression': expression, 'awaitPromise': True, 'returnByValue': True}, timeout=5)
                value = result.get('result', {}).get('value')
                if not isinstance(value, dict): raise RuntimeError('[SESSION_RESTORE_UNAVAILABLE] 浏览器未能确认会话恢复设置')
                return value
            preference = evaluate(read)
            if preference.get('value') == 1: return
            if preference.get('enforcement') == 'ENFORCED':
                raise RuntimeError('[SESSION_POLICY_BLOCKED] 浏览器策略禁止会话恢复，请联系管理员或导入 Cookie 文件')
            saved = evaluate("new Promise(r=>chrome.settingsPrivate.setPref('session.restore_on_startup',1,'',ok=>r({saved:ok})))")
            if not saved.get('saved') or evaluate(read).get('value') != 1:
                raise RuntimeError('[SESSION_RESTORE_UNAVAILABLE] 浏览器未能保存会话恢复设置')


class NormalEdge:
    def __init__(self, profile, url, proxy=''):
        self.profile, self.url, self.proxy = Path(profile), url, proxy
        self.process = None
        self.pid = 0

    def reopen(self):
        if self.is_open():
            _focus_profile_windows([p for p in [self.pid, *( _find_profile_browser_pids(self.profile))] if p])
            return
        # 先清理无窗口的旧版/僵尸调试进程；仅当仍有活跃调试通道或可见旧窗口时才提示冲突
        orphan_pids = _find_profile_browser_pids(self.profile)
        if orphan_pids and not _visible_profile_windows(orphan_pids):
            _ensure_profile_unlocked(self.profile, reject_visible=False)
        port_file = self.profile / 'DevToolsActivePort'
        if port_file.exists():
            import socket
            try:
                port = int(port_file.read_text('utf-8').splitlines()[0])
                with socket.create_connection(('127.0.0.1', port), timeout=.3):
                    raise RuntimeError('[PROFILE_BUSY] 请关闭旧版登录窗口后重试')
            except (OSError, ValueError, IndexError): pass
            port_file.unlink(missing_ok=True)
        # 若用户之前未关闭旧的可见登录窗口，直接接管并前置激活；若仅剩无窗口僵尸进程则自动清理释放锁
        visible_pids = _ensure_profile_unlocked(self.profile, reject_visible=False)
        if visible_pids:
            self.pid = visible_pids[0]
            _focus_profile_windows(visible_pids)
            return
        ensure_session_restore(self.profile, self.proxy)
        _wait_profile_released(self.profile, timeout=2.5)
        self.process = subprocess.Popen(normal_edge_command(self.profile, self.url, self.proxy),
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.pid = self.process.pid
        # 等待确认进程稳定驻留或完成接管，避免 compat relaunch 造成假阳性失败
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            if self.process.poll() is None:
                live = _find_profile_browser_pids(self.profile)
                if _visible_profile_windows(live or [self.process.pid]) or (time.monotonic() + 2.3 <= deadline):
                    if live and self.process.pid not in live:
                        self.pid = live[0]
                    return
            else:
                live = _find_profile_browser_pids(self.profile)
                if live:
                    self.pid = live[0]
                    _focus_profile_windows(live)
                    return
                if time.monotonic() + 2.0 <= deadline:
                    raise RuntimeError('[PROFILE_BUSY] 独立浏览器未启动，请关闭旧登录窗口后重试')
            time.sleep(.1)
        if not self.is_open():
            raise RuntimeError('[PROFILE_BUSY] 独立浏览器未启动，请关闭旧登录窗口后重试')

    def is_open(self):
        if self.process is not None and self.process.poll() is None:
            return True
        if self.pid and _is_pid_alive(self.pid):
            return True
        return bool(_visible_profile_windows(_find_profile_browser_pids(self.profile)))

    def close(self):
        if not self.is_open():
            _ensure_profile_unlocked(self.profile, reject_visible=False)
            return
        pids = []
        if self.process is not None and self.process.poll() is None:
            pids.append(self.process.pid)
        if self.pid and self.pid not in pids and _is_pid_alive(self.pid):
            pids.append(self.pid)
        for p in _find_profile_browser_pids(self.profile):
            if p not in pids:
                pids.append(p)
        for pid in pids:
            close_owned_edge_windows(pid)
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            proc_alive = self.process is not None and self.process.poll() is None
            live_pids = _find_profile_browser_pids(self.profile)
            if not proc_alive and not live_pids:
                self.pid = 0
                return
            if not _visible_profile_windows(live_pids or pids):
                # 可见窗口已关闭（用户无确认弹窗阻塞），短暂等待后台进程写完 Cookies 并退出
                if _wait_profile_released(self.profile, timeout=1.5):
                    self.pid = 0
                    return
                _ensure_profile_unlocked(self.profile, reject_visible=False)
                self.pid = 0
                return
            time.sleep(.12)
        raise RuntimeError('[CLOSE_BROWSER_REQUIRED] 请手动关闭 OmniDownloader 的登录窗口后再点保存，未强制结束浏览器') from None

    def read_cookies(self, stop):
        self.close()
        if stop.is_set(): raise RuntimeError('[CANCELLED] 登录保存已取消')
        _wait_profile_released(self.profile, timeout=2.5)
        # No interactive login or navigation to Google in this short-lived reader.
        with edge_profile(self.profile, proxy=self.proxy, visible=True, _leased=True, _no_pages=True, _restore_session=True) as browser:
            return browser.call('Storage.getCookies')['cookies']


@contextmanager
def normal_edge(profile, url, proxy=''):
    profile = Path(profile); profile.mkdir(parents=True, exist_ok=True)
    with file_lock(profile.parent / (profile.name + '.lock'), timeout=.1):
        browser = NormalEdge(profile, url, proxy)
        try:
            browser.reopen()
            yield browser
        finally:
            browser.close()


class LoginJob:
    def __init__(self, manager, platform, proxy):
        self.manager, self.platform, self.proxy = manager, platform, proxy
        self.generation = manager.store.status(platform)['generation']
        self.stop, self.capture, self.finished = threading.Event(), threading.Event(), threading.Event()
        self.startup_done = threading.Event()
        self.lock = threading.RLock()
        self.result = dict(platform=platform, phase='OPENING', running=True, cookieCount=0,
                           capturedAt=0, errorCode='', errorMessage='', skippedPartitionedCookies=0)
        self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True, name='OmniLogin')

    def status(self):
        with self.lock: return dict(self.result)

    def update(self, **values):
        with self.lock: self.result.update(values)

    def run(self):
        try:
            with self.manager.browser(self.manager.profile(self.platform), LOGIN_URLS[self.platform], self.proxy) as browser:
                self.update(phase='WAITING_LOGIN', errorCode='', errorMessage='')
                self.startup_done.set()
                fingerprint = ''
                deadline = time.monotonic() + 900
                while not self.stop.is_set() and time.monotonic() < deadline:
                    if self.manager.store.status(self.platform)['generation'] != self.generation:
                        raise ValueError('[SOURCE_EXPIRED] 会话已在其他窗口变化，请重新打开登录窗口')
                    explicit = self.capture.is_set()
                    if not explicit:
                        self.update(phase='WAITING_LOGIN' if browser.is_open() else 'AWAITING_CAPTURE')
                        self.stop.wait(.2)
                        continue
                    try:
                        self.update(phase='CAPTURING', errorCode='', errorMessage='')
                        cookies = browser.read_cookies(self.stop)
                    except RuntimeError as exc:
                        if str(exc).startswith('[CLOSE_BROWSER_REQUIRED]'):
                            self.update(phase='WAITING_LOGIN', errorCode='CLOSE_BROWSER_REQUIRED', errorMessage=str(exc))
                            self.error = exc; self.capture.clear(); self.finished.set()
                            continue
                        raise
                    if self.stop.is_set(): raise RuntimeError('[CANCELLED] 登录保存已取消')
                    text, names, skipped = cookie_text(self.platform, cookies)
                    self.update(skippedPartitionedCookies=skipped)
                    authenticated = bool(names & AUTH_NAMES.get(self.platform, set()))
                    if self.platform == 'YouTube':
                        authenticated = authenticated and bool(names & {'SID', '__Secure-1PSID', '__Secure-3PSID'})
                    if not names or (self.platform in AUTH_NAMES and not authenticated):
                        self.error = ValueError('[PARTITIONED_AUTH_UNSUPPORTED] 该浏览器会话依赖分区 Cookie，当前 HTTP 引擎无法使用' if skipped else '[LOGIN_REQUIRED] 尚未取得目标平台登录凭据，请在登录窗口中返回该平台后重试')
                        self.capture.clear(); browser.reopen(); self.update(phase='WAITING_LOGIN'); self.finished.set()
                    else:
                        state = self.manager.store.status(self.platform)
                        if state['generation'] != self.generation:
                            raise ValueError('[SOURCE_EXPIRED] 会话已在其他窗口变化，请重新打开登录窗口')
                        digest = hashlib.sha256(text.encode()).hexdigest()
                        if digest != fingerprint:
                            state = self.manager.store.import_text(self.platform, text, expected_generation=self.generation)
                            self.generation, fingerprint = state['generation'], digest
                        self.update(phase='CAPTURED', cookieCount=state['cookieCount'], capturedAt=int(time.time()*1000),
                                    skippedPartitionedCookies=skipped, errorCode='', errorMessage='')
                        if explicit:
                            self.capture.clear(); self.stop.set()
                    self.stop.wait(.75)
        except Exception as exc:
            # Never expose URLs, cookie values, browser arguments or library errors.
            safe = str(exc) if isinstance(exc, (ValueError, RuntimeError)) and str(exc).startswith('[') else '[CAPTURE_FAILED] 会话捕获失败，请重新打开登录窗口'
            self.error = RuntimeError(safe)
            self.update(phase='ERROR', errorCode=safe.split(']')[0].lstrip('['), errorMessage=safe)
        finally:
            self.update(running=False)
            if self.status()['phase'] not in ('CAPTURED', 'ERROR'): self.update(phase='CLOSED')
            self.startup_done.set()
            self.finished.set()


class BrowserLogin:
    def __init__(self, store=None, browser=normal_edge):
        self._store, self.browser = store, browser
        self.jobs, self.lock = {}, threading.RLock()

    @property
    def store(self):
        from core import session_store
        return self._store if self._store is not None else session_store.sessions

    @property
    def root(self): return self.store.directory.parent / 'login-profiles'

    def profile(self, platform):
        if platform not in LOGIN_URLS: raise ValueError('[UNSUPPORTED_PLATFORM] 不支持的平台')
        # Stable ID independent of display-name ordering and application location.
        path = self.root / DOMAINS[platform][0]
        if self.root.resolve() != self.store.directory.parent.resolve() / 'login-profiles' or path.resolve() != self.root.resolve() / DOMAINS[platform][0]:
            raise RuntimeError('[UNSAFE_PROFILE] 浏览器目录不能指向应用数据目录之外')
        return path

    def status(self, platform):
        self.profile(platform)
        with self.lock:
            job = self.jobs.get(platform)
            return job.status() if job else dict(platform=platform, phase='CLOSED', running=False, cookieCount=0, capturedAt=0)

    def open(self, platform, proxy=''):
        profile_path = self.profile(platform)
        existing_job = None
        with self.lock:
            if not self.store.is_enabled(platform): raise ValueError('[COOKIE_DISABLED] 请先启用该平台 Cookie')
            job = self.jobs.get(platform)
            if job and job.thread.is_alive():
                existing_job = job
            else:
                job = self.jobs[platform] = LoginJob(self, platform, proxy)
                job.thread.start()
        if existing_job is not None:
            _focus_profile_windows(_find_profile_browser_pids(profile_path))
            return existing_job.status()
        # 短暂等待浏览器拉起结果，使启动期错误能够立即反馈至界面而非无声吞掉
        job.startup_done.wait(4.0)
        return job.status()

    def save(self, platform):
        with self.lock:
            job = self.jobs.get(platform)
            if not job: raise ValueError('[BROWSER_CLOSED] 请先打开登录窗口')
            if not job.thread.is_alive():
                if job.error: raise job.error
                state = self.store.status(platform)
                if job.status()['capturedAt'] and state['generation'] == job.generation and state['state'] == 'IMPORTED':
                    return dict(job.status(), session=state, alreadySaved=True)
                raise ValueError('[BROWSER_CLOSED] 请先打开登录窗口')
            job.error = None; job.finished.clear(); job.capture.set()
        if not job.finished.wait(45): raise RuntimeError('[CAPTURE_TIMEOUT] 保存尚未完成，请稍后检查状态')
        if job.error: raise job.error
        if not job.status()['capturedAt']: raise ValueError('[LOGIN_REQUIRED] 尚未取得有效的平台会话')
        return dict(job.status(), session=self.store.status(platform))

    def clear(self, platform):
        with self.lock:
            path = self.profile(platform)
            self.store.clear(platform)  # Tombstone first: late captures cannot resurrect a login.
            with self.lock: job = self.jobs.get(platform)
            if job and job.thread.is_alive():
                job.stop.set(); job.thread.join(timeout=15)
                if job.thread.is_alive(): raise RuntimeError('[PROFILE_BUSY] 凭据已清除，请关闭登录窗口后再次清理浏览器状态')
            root = self.root.resolve()
            if path.is_symlink() or path.resolve() != root / DOMAINS[platform][0] or root.parent != self.store.directory.parent.resolve():
                raise RuntimeError('[UNSAFE_PROFILE] 拒绝删除许可目录以外的浏览器数据')
            with file_lock(self.root / (path.name + '.lock'), timeout=.1):
                _ensure_profile_unlocked(path, reject_visible=True)
                if path.exists(): shutil.rmtree(path)
            with self.lock: self.jobs.pop(platform, None)
            return self.store.status(platform)


    def shutdown(self):
        with self.lock: jobs = list(self.jobs.values())
        for job in jobs: job.stop.set()
        deadline = time.monotonic() + 15
        for job in jobs: job.thread.join(timeout=max(0, deadline-time.monotonic()))


browser_login = BrowserLogin()
atexit.register(browser_login.shutdown)
