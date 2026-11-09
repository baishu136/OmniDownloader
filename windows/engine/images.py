"""Original-image jobs use the existing queue, cancellation and recoverable export."""
import asyncio
import hashlib
import json
from pathlib import Path
import shutil
import time
import copy
from core.config import config
from core.models import TaskStatus, DownloadType
from core.session_store import sessions
from engine.media_resolver import resolve_images, download_images
from engine.ytdlp_engine import YtDlpEngine, clean_media_title


class ImageEngine:
    _posts = {}

    @staticmethod
    def remember(post):
        if len(ImageEngine._posts) >= 32: ImageEngine._posts.clear()
        ImageEngine._posts[(post['url'], post['platform'], sessions.status(post['platform'])['generation'], config.proxy_url)] = (time.monotonic(), copy.deepcopy(post))

    @staticmethod
    async def extract(url, platform):
        def run():
            with sessions.cookie_file(platform) as cookie:
                post = resolve_images(url, platform, cookie, config.proxy_url)
                ImageEngine.remember(post)
                return post
        return await asyncio.to_thread(run)

    @staticmethod
    async def download(task, progress, cancel):
        def run():
            directory = YtDlpEngine.workspace(task)
            directory.mkdir(parents=True, exist_ok=True)
            signature = hashlib.sha256(f'{task.url}|{task.image_platform}|{task.selected_image_ids}|{task.download_type}'.encode()).hexdigest()
            ready = directory / 'images-ready.json'
            record = json.loads(ready.read_text('utf-8')) if ready.is_file() else {}
            source = directory / Path(record.get('file', 'missing')).name
            if record.get('signature') != signature or not source.is_file():
                with sessions.cookie_file(task.image_platform) as cookie:
                    saved = ImageEngine._posts.get((task.url, task.image_platform, sessions.status(task.image_platform)['generation'], config.proxy_url))
                    post = copy.deepcopy(saved[1]) if saved and time.monotonic() - saved[0] < 600 else None
                    result = download_images(task.url, task.image_platform, task.selected_image_ids, directory,
                        task.download_type == DownloadType.IMAGE_ZIP, cookie, config.proxy_url, cancel,
                        lambda value: progress(value, '', '正在保存原图', TaskStatus.DOWNLOADING), post=post)
                source = Path(result['path'])
                ready.write_text(json.dumps({'signature': signature, 'file': source.name}), encoding='utf-8')
            if cancel.is_set(): raise RuntimeError('[CANCELLED] 用户取消')
            progress(98, '', '正在导出', TaskStatus.PROCESSING)
            destination = Path(config.download_dir) / f'{clean_media_title(task.title)}{source.suffix}'
            index = 1
            while destination.exists():
                destination = Path(config.download_dir) / f'{clean_media_title(task.title)}_{index}{source.suffix}'
                index += 1
            partial = destination.with_name('.' + destination.name + '.' + task.id + '.part')
            try:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, partial)
                if source.stat().st_size != partial.stat().st_size: raise OSError('导出大小不符')
                partial.replace(destination)
            except OSError as error:
                raise RuntimeError('[EXPORT_FAILED] 原图成品已保留，请更换目录后重试导出') from error
            finally: partial.unlink(missing_ok=True)
            ready.unlink(missing_ok=True); source.unlink(missing_ok=True)
            return str(destination)
        return await asyncio.to_thread(run)
