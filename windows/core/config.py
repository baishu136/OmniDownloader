"""
OmniDownloader 配置管理器 (Windows 移植版)
持久化管理用户配置项（下载路径、网络代理、B站 Cookie、并发数等）
"""

import os
import json
from pathlib import Path
from typing import Dict, Any

CONFIG_FILE = Path(__file__).parent.parent / "config.json"


def get_default_download_dir() -> str:
    """获取默认下载保存路径：用户家目录下的 Downloads/OmniDownloader"""
    user_home = Path.home()
    default_dir = user_home / "Downloads" / "OmniDownloader"
    default_dir.mkdir(parents=True, exist_ok=True)
    return str(default_dir)


class AppConfig:
    """全局配置管理器"""
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(AppConfig, cls).__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self):
        self.download_dir: str = get_default_download_dir()
        self.proxy_url: str = ""
        self.bilibili_cookie: str = ""
        self.max_concurrent_tasks: int = 3
        self.theme: str = "auto"  # auto, dark, light

        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.download_dir = data.get("download_dir", self.download_dir)
                    self.proxy_url = data.get("proxy_url", "")
                    self.bilibili_cookie = data.get("bilibili_cookie", "")
                    self.max_concurrent_tasks = data.get("max_concurrent_tasks", 3)
                    self.theme = data.get("theme", "auto")
            except Exception as e:
                print(f"[Config] 读取配置文件失败，使用默认配置: {e}")

        # 确保下载路径存在
        try:
            os.makedirs(self.download_dir, exist_ok=True)
        except Exception:
            pass

    def save(self):
        """保存配置到本地 JSON"""
        data = {
            "download_dir": self.download_dir,
            "proxy_url": self.proxy_url,
            "bilibili_cookie": self.bilibili_cookie,
            "max_concurrent_tasks": self.max_concurrent_tasks,
            "theme": self.theme
        }
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"[Config] 保存配置文件失败: {e}")

    def to_dict(self) -> Dict[str, Any]:
        """导出字典格式"""
        return {
            "downloadDir": self.download_dir,
            "proxyUrl": self.proxy_url,
            "bilibiliCookie": self.bilibili_cookie,
            "maxConcurrentTasks": self.max_concurrent_tasks,
            "theme": self.theme
        }


config = AppConfig()
