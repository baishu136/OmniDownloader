"""
OmniDownloader Windows 后台托盘静默运行守护程序
所有服务组件仅在后台静默执行，无控制台黑框窗口，自动调起浏览器并在右下角常驻托盘
"""

import os
import sys

# 针对 Windows GUI / 无控制台纯后台模式，保护标准流，杜绝 [Errno 22] Invalid argument
if sys.stdout is None or not hasattr(sys.stdout, "write"):
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None or not hasattr(sys.stderr, "write"):
    sys.stderr = open(os.devnull, "w", encoding="utf-8")
if sys.stdin is None:
    sys.stdin = open(os.devnull, "r", encoding="utf-8")

import time
import json
import socket
import threading
import webbrowser
import subprocess
import urllib.request
import urllib.error
from pathlib import Path
from PIL import Image, ImageDraw

if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32

try:
    import psutil
except ImportError:
    psutil = None

# 确保将当前 windows 目录加入模块搜索路径
CURRENT_DIR = Path(__file__).parent.resolve()
if str(CURRENT_DIR) not in sys.path:
    sys.path.insert(0, str(CURRENT_DIR))

import pystray
from pystray import MenuItem as item
import uvicorn

from omni_server import app
from core.config import config


def is_port_in_use(port: int = 58000) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('127.0.0.1', port)) == 0


class ServerThread(threading.Thread):
    def __init__(self, host: str, port: int):
        super().__init__()
        self.host = host
        self.port = port
        self.server = None
        self.daemon = True

    def run(self):
        cfg = uvicorn.Config(app, host=self.host, port=self.port, log_level="warning")
        self.server = uvicorn.Server(cfg)
        self.server.run()

    def stop(self):
        if self.server:
            self.server.should_exit = True


def generate_tray_icon():
    """加载高分辨率应用托盘图标"""
    logo_path = CURRENT_DIR / "web" / "static" / "logo.png"
    if logo_path.exists():
        try:
            return Image.open(logo_path)
        except Exception:
            pass

    width = 64
    height = 64
    image = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    dc = ImageDraw.Draw(image)
    dc.rounded_rectangle([4, 4, width - 4, height - 4], radius=16, fill=(79, 70, 229, 255))
    dc.rectangle([28, 14, 36, 36], fill=(255, 255, 255, 255))
    dc.polygon([(20, 34), (44, 34), (32, 46)], fill=(255, 255, 255, 255))
    dc.rectangle([18, 48, 46, 52], fill=(255, 255, 255, 255))
    return image


def find_omni_browser_window():
    """扫描系统所有顶级可见窗口，寻找标题包含 OmniDownloader 或端口 58000 的浏览器窗口"""
    if sys.platform != "win32":
        return None
    found_hwnds = []

    def enum_cb(hwnd, lparam):
        if user32.IsWindowVisible(hwnd):
            length = user32.GetWindowTextLengthW(hwnd)
            if length > 0:
                buf = ctypes.create_unicode_buffer(length + 1)
                user32.GetWindowTextW(hwnd, buf, length + 1)
                title = buf.value
                if "OmniDownloader" in title or "127.0.0.1:58000" in title or "localhost:58000" in title:
                    found_hwnds.append((hwnd, title))
        return True

    EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows(EnumProc(enum_cb), 0)

    if found_hwnds:
        return found_hwnds[0][0]
    return None


def force_foreground_window(hwnd):
    """将指定窗口从最小化恢复并强力置顶到前台焦点"""
    if not hwnd or sys.platform != "win32":
        return False
    try:
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        else:
            user32.ShowWindow(hwnd, 5)  # SW_SHOW

        fore_hwnd = user32.GetForegroundWindow()
        fore_thread = user32.GetWindowThreadProcessId(fore_hwnd, None)
        cur_thread = kernel32.GetCurrentThreadId()

        if fore_thread != cur_thread:
            user32.AttachThreadInput(cur_thread, fore_thread, True)
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
            user32.AttachThreadInput(cur_thread, fore_thread, False)
        else:
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
        return True
    except Exception as e:
        return False


def check_server_active_page(host: str = "127.0.0.1", port: int = 58000) -> bool:
    """向本地后端服务查询是否存在正在运行的活跃网页客户端"""
    url = f"http://{host}:{port}/api/system/client-status"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "OmniDownloader-Tray"})
        with urllib.request.urlopen(req, timeout=1.2) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                return data.get("hasActivePage", False)
    except Exception:
        pass
    return False


def notify_server_activate_page(host: str = "127.0.0.1", port: int = 58000):
    """请求服务端向所有连接中的网页广播 focus_page 指令"""
    url = f"http://{host}:{port}/api/system/activate-page"
    try:
        req = urllib.request.Request(url, data=b"{}", headers={"Content-Type": "application/json", "User-Agent": "OmniDownloader-Tray"})
        with urllib.request.urlopen(req, timeout=1.2) as resp:
            return resp.status == 200
    except Exception:
        return False


def find_and_focus_browser_process():
    """当标签页位于后台时，尝试激活当前运行的主流浏览器窗口"""
    if sys.platform != "win32" or not psutil:
        return
    browser_names = ("msedge.exe", "chrome.exe", "firefox.exe", "brave.exe", "360chrome.exe", "opera.exe")
    try:
        for proc in psutil.process_iter(['pid', 'name']):
            name = proc.info.get('name')
            if name and name.lower() in browser_names:
                target_pid = proc.info['pid']
                def enum_proc_cb(hwnd, lparam):
                    if user32.IsWindowVisible(hwnd):
                        pid = wintypes.DWORD()
                        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                        if pid.value == target_pid:
                            length = user32.GetWindowTextLengthW(hwnd)
                            if length > 0:
                                force_foreground_window(hwnd)
                                return False
                    return True
                EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
                user32.EnumWindows(EnumProc(enum_proc_cb), 0)
                break
    except Exception:
        pass


def is_page_open_in_browser(host: str = "127.0.0.1", port: int = 58000) -> bool:
    """
    全面检测浏览器中是否已存在 OmniDownloader 页面：
    1. 窗口级别：检测是否有对应标题的浏览器窗口
    2. 服务端连接级别：检测是否有保持 SSE 活跃推流的网页
    若检测到已存在，自动触发置顶/聚焦并返回 True，否则返回 False
    """
    # 1. 检查是否存在浏览器窗口
    hwnd = find_omni_browser_window()
    if hwnd:
        force_foreground_window(hwnd)
        notify_server_activate_page(host, port)
        return True

    # 2. 检查服务端是否存在活跃 SSE 网页连接
    if check_server_active_page(host, port):
        notify_server_activate_page(host, port)
        find_and_focus_browser_process()
        return True

    return False


def smart_open_browser(force: bool = False, host: str = "127.0.0.1", port: int = 58000):
    """
    智能唤起浏览器页面：
    若浏览器中已存在页面，则聚焦/激活该页面，不拉起新页面；
    仅当浏览器中未存在页面时，才拉起新页面。
    """
    if not force:
        if is_page_open_in_browser(host, port):
            print("[OmniTray] 浏览器中已存在 OmniDownloader 页面，已聚焦激活现有页面，不拉起新页面。")
            return False

    print("[OmniTray] 浏览器中未检测到已打开的页面，正在拉起新页面...")
    webbrowser.open(f"http://{host}:{port}/")
    return True


def open_browser(icon=None, item=None):
    smart_open_browser(force=False)


def open_folder(icon=None, item=None):
    folder = str(Path(config.download_dir).resolve())
    if sys.platform == "win32":
        subprocess.Popen(f'explorer "{folder}"')


def on_exit(icon, item, server_thread):
    icon.stop()
    if server_thread:
        server_thread.stop()
    # 确保子进程完全退出
    os._exit(0)


def toggle_autostart(icon, item):
    try:
        from setup_shortcuts import is_autostart_enabled, enable_autostart
        new_state = not is_autostart_enabled()
        enable_autostart(new_state)
    except Exception as e:
        print(f"切换开机自启动失败: {e}")


def get_autostart_state(item):
    try:
        from setup_shortcuts import is_autostart_enabled
        return is_autostart_enabled()
    except Exception:
        return False


def main():
    host = "127.0.0.1"
    port = 58000
    server_url = f"http://{host}:{port}"
    is_autostart = ("--autostart" in sys.argv or "--silent" in sys.argv)

    # 1. 多实例防重检测：如果端口已经被占用，说明后台服务已在运行
    if is_port_in_use(port):
        if not is_autostart:
            # 用户通过快捷方式启动：智能聚焦已有页面或拉起新页面
            smart_open_browser(force=False, host=host, port=port)
        # 已有实例常驻托盘守护，新进程无需重复创建托盘，安全彻底退出
        os._exit(0)

    # 2. 服务未运行，启动本地后端服务
    server_thread = ServerThread(host, port)
    server_thread.start()

    # 等待服务就绪
    for _ in range(50):
        if is_port_in_use(port):
            break
        time.sleep(0.1)

    # 3. 冷启动时的浏览器打开逻辑
    if not is_autostart:
        # 冷启动时可能用户的浏览器在上次会话中保留了该标签页
        # 给予短暂停顿以检测窗口或让已恢复标签页重连
        existing_found = False
        for _ in range(10):
            if is_page_open_in_browser(host, port):
                existing_found = True
                break
            time.sleep(0.1)

        if not existing_found:
            smart_open_browser(force=False, host=host, port=port)

    # 4. 创建系统托盘图标
    icon_image = generate_tray_icon()
    menu = (
        item('🌐 打开网页版 (http://127.0.0.1:58000)', lambda icon, item: smart_open_browser(force=False, host=host, port=port), default=True),
        item('📂 打开下载文件夹', open_folder),
        item('🚀 开机自启动', toggle_autostart, checked=get_autostart_state),
        pystray.Menu.SEPARATOR,
        item('❌ 退出后台服务', lambda icon, item: on_exit(icon, item, server_thread))
    )

    tray_icon = pystray.Icon(
        name="OmniDownloader",
        icon=icon_image,
        title="OmniDownloader - 后台下载服务运行中",
        menu=menu
    )

    tray_icon.run()


if __name__ == "__main__":
    main()

