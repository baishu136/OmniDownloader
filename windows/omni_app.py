"""
OmniDownloader Windows 桌面客户端入口 (pywebview + FastAPI 混合架构)
利用系统内置 Edge WebView2 启动无原生边框限制的现代化桌面窗口
"""

import sys
import os

# 针对 Windows GUI / 无控制台独立窗口模式，保护标准流，杜绝 [Errno 22] Invalid argument
if sys.stdout is None or not hasattr(sys.stdout, "write"):
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None or not hasattr(sys.stderr, "write"):
    sys.stderr = open(os.devnull, "w", encoding="utf-8")
if sys.stdin is None:
    sys.stdin = open(os.devnull, "r", encoding="utf-8")

import time
import threading
import socket
from pathlib import Path
import webview
import uvicorn

CURRENT_DIR = Path(__file__).parent.resolve()
if str(CURRENT_DIR) not in sys.path:
    sys.path.insert(0, str(CURRENT_DIR))

from omni_server import app


def is_port_in_use(port: int) -> bool:
    """检测本地端口是否已被占用"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('127.0.0.1', port)) == 0


def find_available_port(start_port: int = 58000) -> int:
    """寻找可用端口"""
    port = start_port
    while is_port_in_use(port):
        port += 1
    return port


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


def main():
    host = "127.0.0.1"
    port = find_available_port(58000)
    server_url = f"http://{host}:{port}"

    print(f"[OmniApp] 正在启动本地核心服务: {server_url}")
    server_thread = ServerThread(host, port)
    server_thread.start()

    # 等待后端端口就绪
    for _ in range(50):
        if is_port_in_use(port):
            break
        time.sleep(0.1)

    print(f"[OmniApp] 核心服务已就绪，正在创建桌面窗口...")

    # 创建桌面原生窗口 (基于 Edge WebView2)
    window = webview.create_window(
        title="OmniDownloader - 全能音视频下载器",
        url=server_url,
        width=1080,
        height=760,
        min_size=(800, 600),
        text_select=True
    )

    try:
        webview.start()
    finally:
        print("[OmniApp] 窗口关闭，正在停止后台服务...")
        server_thread.stop()


if __name__ == "__main__":
    main()
