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
import socket
import threading
import webbrowser
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw

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


def open_browser(icon=None, item=None):
    webbrowser.open("http://127.0.0.1:58000/")


def open_folder(icon=None, item=None):
    folder = str(Path(config.download_dir).resolve())
    if sys.platform == "win32":
        subprocess.Popen(f'explorer "{folder}"')


def on_exit(icon, item, server_thread):
    icon.stop()
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

    # 如果端口未被占用，则启动本地后端服务
    server_thread = None
    if not is_port_in_use(port):
        server_thread = ServerThread(host, port)
        server_thread.start()
        # 等待服务就绪
        for _ in range(50):
            if is_port_in_use(port):
                break
            time.sleep(0.1)

    # 自动在默认浏览器中打开页面（若为开机静默自启动则不弹窗抢焦点）
    is_autostart = ("--autostart" in sys.argv or "--silent" in sys.argv)
    if not is_autostart:
        open_browser()

    # 创建系统托盘图标
    icon_image = generate_tray_icon()
    menu = (
        item('🌐 打开网页版 (http://127.0.0.1:58000)', open_browser, default=True),
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
