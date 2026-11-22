"""User-driven Edge sign-in shared by the web and desktop clients.

Only app-owned profiles are opened. Google SSO state remains in that profile;
only the selected service's first-party cookies enter the encrypted vault.
"""
import atexit
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
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
    '哔哩哔哩': 'https://www.bilibili.com/', 'Pinterest': 'https://www.pinterest.com/',
    'Instagram': 'https://www.instagram.com/', 'Facebook': 'https://www.facebook.com/',
    'TikTok': 'https://www.tiktok.com/', '快手': 'https://www.kuaishou.com/',
    '微博': 'https://weibo.com/'}
AUTH_NAMES = {
    'YouTube': {'SAPISID', '__Secure-1PAPISID', '__Secure-3PAPISID'},
    'X (Twitter)': {'auth_token'}, '小红书': {'web_session'}, 'Pixiv': {'PHPSESSID'},
    '抖音': {'sessionid', 'sessionid_ss'}, '哔哩哔哩': {'SESSDATA'},
    'Pinterest': {'_pinterest_sess'}, 'Instagram': {'sessionid'}, 'Facebook': {'c_user'},
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
def edge_profile(profile, url='about:blank', proxy='', visible=True):
    from engine.automatic_douyin import CDP, edge_executable
    from websockets.sync.client import connect
    executable = edge_executable()
    if not executable: raise RuntimeError('[EDGE_UNAVAILABLE] 请安装 Microsoft Edge 后重试')
    profile = Path(profile)
    profile.mkdir(parents=True, exist_ok=True)
    with file_lock(profile.parent / (profile.name + '.lock'), timeout=.1):
        port_file = profile / 'DevToolsActivePort'
        if port_file.exists():
            import socket
            try:
                port = int(port_file.read_text('utf-8').splitlines()[0])
                with socket.create_connection(('127.0.0.1', port), timeout=.3):
                    raise RuntimeError('[PROFILE_BUSY] 请关闭之前的 OmniDownloader 登录窗口后重试')
            except (OSError, ValueError, IndexError): pass
            port_file.unlink(missing_ok=True)
        command = [str(executable), '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
                   f'--user-data-dir={profile}', '--no-first-run', '--no-default-browser-check',
                   '--disable-background-mode', '--new-window']
        if not visible: command += ['--headless=new']
        if proxy:
            parsed = urlsplit(proxy)
            if parsed.scheme not in ('http', 'https', 'socks5', 'socks5h') or not parsed.hostname or not parsed.port or parsed.username or parsed.password:
                raise ValueError('[PROXY_UNSUPPORTED] 登录浏览器需要不含账号密码的 HTTP 或 SOCKS5 代理')
            command += ['--proxy-server=' + proxy.replace('socks5h://', 'socks5://')]
        command.append(url)
        process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        connection = connection_context = None
        try:
            deadline = time.monotonic() + 15
            while not port_file.is_file():
                if process.poll() is not None: raise RuntimeError('[PROFILE_BUSY] 浏览器未启动，请关闭先前登录窗口后重试')
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
            port_file.unlink(missing_ok=True)


class LoginJob:
    def __init__(self, manager, platform, proxy):
        self.manager, self.platform, self.proxy = manager, platform, proxy
        self.generation = manager.store.status(platform)['generation']
        self.stop, self.capture, self.finished = threading.Event(), threading.Event(), threading.Event()
        self.lock = threading.RLock()
        self.result = dict(platform=platform, phase='OPENING', running=True, cookieCount=0,
                           capturedAt=0, errorCode='', skippedPartitionedCookies=0)
        self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True, name='OmniLogin')

    def status(self):
        with self.lock: return dict(self.result)

    def update(self, **values):
        with self.lock: self.result.update(values)

    def run(self):
        try:
            with self.manager.browser(self.manager.profile(self.platform), LOGIN_URLS[self.platform], self.proxy) as browser:
                self.update(phase='WAITING_LOGIN')
                fingerprint = ''
                deadline = time.monotonic() + 900
                while not self.stop.is_set() and time.monotonic() < deadline:
                    explicit = self.capture.is_set()
                    try:
                        cookies = browser.call('Storage.getCookies')['cookies']
                    except Exception:
                        if explicit: raise RuntimeError('[BROWSER_CLOSED] 登录窗口已关闭，请重新打开后保存') from None
                        break
                    text, names, skipped = cookie_text(self.platform, cookies)
                    self.update(skippedPartitionedCookies=skipped)
                    authenticated = bool(names & AUTH_NAMES.get(self.platform, set()))
                    if self.platform == 'YouTube':
                        authenticated = authenticated and bool(names & {'SID', '__Secure-1PSID', '__Secure-3PSID'})
                    if explicit and (not names or (self.platform == 'YouTube' and not authenticated)):
                        self.error = ValueError('[PARTITIONED_AUTH_UNSUPPORTED] 该浏览器会话依赖分区 Cookie，当前 HTTP 引擎无法使用' if skipped else '[LOGIN_REQUIRED] 尚未取得目标平台登录凭据，请在登录窗口中返回该平台后重试')
                        self.capture.clear(); self.finished.set()
                    elif authenticated or explicit:
                        state = self.manager.store.status(self.platform)
                        if state['generation'] != self.generation:
                            raise ValueError('[SOURCE_EXPIRED] 会话已在其他窗口变化，请重新打开登录窗口')
                        digest = hashlib.sha256(text.encode()).hexdigest()
                        if digest != fingerprint:
                            state = self.manager.store.import_text(self.platform, text, expected_generation=self.generation)
                            self.generation, fingerprint = state['generation'], digest
                        self.update(phase='CAPTURED', cookieCount=state['cookieCount'], capturedAt=int(time.time()*1000),
                                    skippedPartitionedCookies=skipped)
                        if explicit:
                            self.capture.clear(); self.stop.set()
                    self.stop.wait(.75)
        except Exception as exc:
            # Never expose URLs, cookie values, browser arguments or library errors.
            safe = str(exc) if isinstance(exc, (ValueError, RuntimeError)) and str(exc).startswith('[') else '[CAPTURE_FAILED] 会话捕获失败，请重新打开登录窗口'
            self.error = RuntimeError(safe)
            self.update(phase='ERROR', errorCode=safe.split(']')[0].lstrip('['))
        finally:
            self.update(running=False)
            if self.status()['phase'] not in ('CAPTURED', 'ERROR'): self.update(phase='CLOSED')
            self.finished.set()


class BrowserLogin:
    def __init__(self, store=None, browser=edge_profile):
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
        self.profile(platform)
        with self.lock:
            if not self.store.is_enabled(platform): raise ValueError('[COOKIE_DISABLED] 请先启用该平台 Cookie')
            job = self.jobs.get(platform)
            if job and job.thread.is_alive(): return job.status()
            job = self.jobs[platform] = LoginJob(self, platform, proxy)
            job.thread.start()
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
        if not job.finished.wait(15): raise RuntimeError('[CAPTURE_TIMEOUT] 保存尚未完成，请稍后检查状态')
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
