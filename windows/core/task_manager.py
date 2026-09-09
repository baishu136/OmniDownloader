"""
OmniDownloader 任务调度与状态管理器 (Windows 移植版)
提供任务队列调度、并发控制、断点持久化、实时事件推流与取消支持
"""

import os
import sys
import re
import json
import uuid
import asyncio
import traceback
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Callable, Set
from collections import OrderedDict

from core.models import DownloadTask, TaskStatus, DownloadType, AudioFormat
from core.config import config
from engine.url_sniffer import UrlSniffer
from engine.bilibili_extractor import BilibiliDirectExtractor
from engine.domestic_extractor import DomesticDirectExtractor
from engine.tiktok_extractor import TikTokDirectExtractor
from engine.ytdlp_engine import YtDlpEngine

TASKS_STORAGE_FILE = Path(__file__).parent.parent / "tasks.json"


class TaskManager:
    """全局单例任务调度器"""
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(TaskManager, cls).__new__(cls)
            cls._instance._init()
        return cls._instance

    def _init(self):
        self.tasks: OrderedDict[str, DownloadTask] = OrderedDict()
        self.cancel_events: Dict[str, asyncio.Event] = {}
        self.listeners: Set[asyncio.Queue] = set()
        self.semaphore = asyncio.Semaphore(config.max_concurrent_tasks)
        self._load_persisted_tasks()

    def _load_persisted_tasks(self):
        """恢复本地历史任务"""
        if TASKS_STORAGE_FILE.exists():
            try:
                with open(TASKS_STORAGE_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    for item in data:
                        task = DownloadTask(**item)
                        # 如果退出前任务是下载中或等待中，重启后重置为失败或取消
                        if task.status in [TaskStatus.DOWNLOADING, TaskStatus.PROCESSING, TaskStatus.PENDING]:
                            task.status = TaskStatus.CANCELLED
                            task.error_message = "程序退出已中断"
                        self.tasks[task.id] = task
            except Exception as e:
                print(f"[TaskManager] 读取任务历史异常: {e}")

    def _save_persisted_tasks(self):
        """保存任务历史记录"""
        try:
            # 最多保存最近 100 条任务
            recent = list(self.tasks.values())[-100:]
            data = [t.model_dump(by_alias=True) for t in recent]
            with open(TASKS_STORAGE_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"[TaskManager] 保存任务记录失败: {e}")

    async def register_listener(self) -> asyncio.Queue:
        """注册 SSE 推流监听器"""
        q = asyncio.Queue()
        self.listeners.add(q)
        return q

    def unregister_listener(self, q: asyncio.Queue):
        """注销监听器"""
        self.listeners.discard(q)

    async def broadcast_task_update(self, task: DownloadTask):
        """广播任务状态变更通知"""
        payload = {
            "type": "task_update",
            "task": task.model_dump(by_alias=True)
        }
        for q in list(self.listeners):
            try:
                await q.put(payload)
            except Exception:
                self.listeners.discard(q)

    async def broadcast_event(self, payload: dict):
        """广播任意系统级自定义事件（如页面聚焦 focus_page 指令）"""
        for q in list(self.listeners):
            try:
                await q.put(payload)
            except Exception:
                self.listeners.discard(q)

    def get_active_client_count(self) -> int:
        """获取当前正在监听 SSE 推流的活跃网页客户端数量"""
        return len(self.listeners)

    def get_all_tasks(self) -> List[DownloadTask]:
        """按倒序返回全部任务（最新的在前面）"""
        return list(reversed(list(self.tasks.values())))

    def get_task(self, task_id: str) -> Optional[DownloadTask]:
        return self.tasks.get(task_id)

    async def add_task(
        self,
        url: str,
        title: str,
        download_type: DownloadType,
        selected_resolution: str = "",
        audio_format: AudioFormat = AudioFormat.MP3,
        thumbnail_url: str = "",
        author: str = "",
        collection_id: Optional[str] = None,
        collection_title: Optional[str] = None,
        episode_index: int = 0,
        episode_total: int = 0
    ) -> DownloadTask:
        """创建并投递新任务"""
        task_id = str(uuid.uuid4())[:8]
        # 智能清洗标题，去除推文携带的短链
        clean_t = re.sub(r'https?://\S+', '', title).strip('-_ \t\r\n')
        final_title = clean_t if clean_t else title

        task = DownloadTask(
            id=task_id,
            url=url,
            title=final_title,
            author=author,
            thumbnailUrl=thumbnail_url,
            downloadType=download_type,
            selectedResolution=selected_resolution,
            audioFormat=audio_format,
            status=TaskStatus.PENDING,
            collectionId=collection_id,
            collectionTitle=collection_title,
            episodeIndex=episode_index,
            episodeTotal=episode_total
        )

        self.tasks[task_id] = task
        self.cancel_events[task_id] = asyncio.Event()
        self._save_persisted_tasks()
        await self.broadcast_task_update(task)

        # 启动异步下载后台协程
        asyncio.create_task(self._process_task(task_id))
        return task

    async def cancel_task(self, task_id: str):
        """取消指定任务"""
        task = self.tasks.get(task_id)
        if not task:
            return
        if task_id in self.cancel_events:
            self.cancel_events[task_id].set()

        if task.status in [TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PROCESSING]:
            task.status = TaskStatus.CANCELLED
            task.error_message = "用户主动取消"
            task.speed_text = ""
            task.eta_text = ""
            self._save_persisted_tasks()
            await self.broadcast_task_update(task)

    async def delete_task(self, task_id: str, delete_file: bool = False):
        """从记录中删除任务"""
        await self.cancel_task(task_id)
        task = self.tasks.pop(task_id, None)
        self.cancel_events.pop(task_id, None)

        if task and delete_file and task.local_file_path and os.path.exists(task.local_file_path):
            try:
                os.remove(task.local_file_path)
            except Exception:
                pass

        self._save_persisted_tasks()
        payload = {"type": "task_deleted", "taskId": task_id}
        for q in list(self.listeners):
            try:
                await q.put(payload)
            except Exception:
                self.listeners.discard(q)

    async def clear_all_tasks(self, clear_only_finished: bool = True):
        """一键清空下载记录"""
        if clear_only_finished:
            to_remove = [
                tid for tid, t in self.tasks.items()
                if t.status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED]
            ]
        else:
            to_remove = list(self.tasks.keys())

        for tid in to_remove:
            self.tasks.pop(tid, None)
            self.cancel_events.pop(tid, None)

        self._save_persisted_tasks()
        payload = {"type": "tasks_cleared"}
        for q in list(self.listeners):
            try:
                await q.put(payload)
            except Exception:
                self.listeners.discard(q)

    def open_file_in_explorer(self, task_id: str) -> bool:
        """在 Windows 资源管理器中高亮定位文件"""
        task = self.tasks.get(task_id)
        if not task or not task.local_file_path or not os.path.exists(task.local_file_path):
            return False

        path = str(Path(task.local_file_path).resolve())
        if sys.platform == "win32":
            subprocess.Popen(f'explorer /select,"{path}"')
            return True
        return False

    def open_download_folder(self) -> bool:
        """打开下载主目录"""
        folder = str(Path(config.download_dir).resolve())
        if sys.platform == "win32":
            subprocess.Popen(f'explorer "{folder}"')
            return True
        return False

    async def _process_task(self, task_id: str):
        """单个任务执行协程，带并发信号量控制"""
        task = self.tasks.get(task_id)
        if not task:
            return

        cancel_ev = self.cancel_events.get(task_id, asyncio.Event())

        async with self.semaphore:
            if cancel_ev.is_set():
                return

            task.status = TaskStatus.DOWNLOADING
            await self.broadcast_task_update(task)

            loop = asyncio.get_running_loop()

            def on_progress(prog: float, speed: str, eta: str, status: TaskStatus):
                task.progress = prog
                task.speed_text = speed
                task.eta_text = eta
                task.status = status

                def _safe_broadcast():
                    asyncio.create_task(self.broadcast_task_update(task))

                try:
                    loop.call_soon_threadsafe(_safe_broadcast)
                except Exception:
                    pass

            try:
                site = UrlSniffer.identify_site(task.url)

                if site == "哔哩哔哩":
                    final_path = await BilibiliDirectExtractor.download(task, on_progress, cancel_ev)
                elif DomesticDirectExtractor.is_supported(site) and (task.selected_resolution.startswith("http") or task.download_type in [DownloadType.AUDIO_ONLY, DownloadType.COVER, DownloadType.VIDEO_WITH_AUDIO]):
                    try:
                        final_path = await DomesticDirectExtractor.download(task, on_progress, cancel_ev)
                    except Exception as e:
                        print(f"[TaskManager] 国内直连下载失败，降级通用引擎: {e}")
                        final_path = await YtDlpEngine.download(task, on_progress, cancel_ev)
                elif site == "TikTok" or TikTokDirectExtractor.is_supported(task.url):
                    try:
                        final_path = await TikTokDirectExtractor.download(task, on_progress, cancel_ev)
                    except Exception as e:
                        print(f"[TaskManager] TikTok 直连下载失败，降级通用引擎: {e}")
                        final_path = await YtDlpEngine.download(task, on_progress, cancel_ev)
                else:
                    final_path = await YtDlpEngine.download(task, on_progress, cancel_ev)

                task.local_file_path = final_path
                task.status = TaskStatus.COMPLETED
                task.progress = 100.0
                task.speed_text = ""
                task.eta_text = ""
                if os.path.exists(final_path):
                    size = os.path.getsize(final_path)
                    task.file_size_text = f"{size / (1024 * 1024):.1f} MB"
            except asyncio.CancelledError:
                task.status = TaskStatus.CANCELLED
                task.error_message = "下载已取消"
            except Exception as e:
                task.status = TaskStatus.FAILED
                task.error_message = str(e)
                err_msg = traceback.format_exc()
                print(f"[TaskManager] 任务 {task_id} 执行异常: {err_msg}")
                try:
                    log_file = Path(config.download_dir) / "download_error.log"
                    with open(log_file, "a", encoding="utf-8") as f:
                        f.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] Task {task_id} FAILED:\n{err_msg}\n")
                except Exception:
                    pass
            finally:
                self._save_persisted_tasks()
                await self.broadcast_task_update(task)


task_manager = TaskManager()
