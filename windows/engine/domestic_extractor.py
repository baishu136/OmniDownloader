"""
OmniDownloader 国内平台 (抖音、快手、小红书) 原生免登录直连解析与下载引擎 (Windows 移植版)
提供免 Python 复杂环境、毫秒级的无水印视频元数据嗅探与极速流式下载
"""

import os
import re
import json
import time
import asyncio
from pathlib import Path
from typing import Optional, List, Dict, Any, Callable
import requests

from core.models import VideoMetadata, FormatOption, DownloadTask, DownloadType, TaskStatus
from core.config import config
from engine.ffmpeg_helper import FFmpegHelper

MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


def sanitize_filename(name: str) -> str:
    clean = re.sub(r'[\\/:*?"<>|]', "_", name).strip()
    return clean[:100] if len(clean) > 100 else clean


class DomesticDirectExtractor:
    """国内短视频原生直接解析"""

    @classmethod
    def is_supported(cls, site: str) -> bool:
        return site in ["抖音", "快手", "小红书"]

    @classmethod
    async def extract(cls, raw_url: str, site: str) -> VideoMetadata:
        return await asyncio.to_thread(cls._extract_sync, raw_url, site)

    @classmethod
    def _extract_sync(cls, raw_url: str, site: str) -> VideoMetadata:
        if site == "抖音":
            return cls._extract_douyin(raw_url)
        elif site == "快手":
            return cls._extract_kuaishou(raw_url)
        elif site == "小红书":
            return cls._extract_xiaohongshu(raw_url)
        raise ValueError(f"不支持的国内平台: {site}")

    @classmethod
    def _extract_douyin(cls, raw_url: str) -> VideoMetadata:
        # 跟随重定向
        resp = requests.head(raw_url, headers={"User-Agent": MOBILE_UA}, allow_redirects=True, timeout=10)
        resolved_url = resp.url

        id_match = re.search(r"(?:video|note|item_ids=)/?(\d+)", resolved_url)
        video_id = id_match.group(1) if id_match else ""
        if not video_id:
            id_match = re.search(r"(\d{18,20})", resolved_url)
            video_id = id_match.group(1) if id_match else ""

        if not video_id:
            raise RuntimeError("未能从抖音链接识别到视频 ID")

        # 优先使用字节 Feed API
        api_url = f"https://api.amemv.com/aweme/v1/feed/?aweme_id={video_id}"
        feed_resp = requests.get(api_url, headers={"User-Agent": MOBILE_UA}, timeout=10)
        feed_json = feed_resp.json()
        aweme_list = feed_json.get("aweme_list", [])

        target_item = None
        for item in aweme_list:
            if str(item.get("aweme_id")) == str(video_id):
                target_item = item
                break
        if not target_item and aweme_list:
            target_item = aweme_list[0]

        if not target_item:
            raise RuntimeError("未能获取到抖音视频数据，请尝试通用引擎")

        title = target_item.get("desc", f"抖音视频_{video_id}").strip() or f"抖音视频_{video_id}"
        author = target_item.get("author", {}).get("nickname", "抖音创作者")
        duration = target_item.get("duration", 0) // 1000

        video_obj = target_item.get("video", {})
        cover_list = video_obj.get("cover", {}).get("url_list", [])
        cover = cover_list[0] if cover_list else ""

        # 无水印播放直链
        play_list = video_obj.get("play_addr", {}).get("url_list", [])
        play_url = play_list[0] if play_list else ""
        if play_url:
            play_url = play_url.replace("playwm", "play")

        formats = [
            FormatOption(
                formatId=play_url,
                resolutionLabel="原画无水印",
                ext="mp4",
                note="字节极速直连"
            )
        ]

        return VideoMetadata(
            url=raw_url,
            title=title,
            author=author,
            durationText=f"{duration // 60:02d}:{duration % 60:02d}",
            thumbnailUrl=cover,
            siteName="抖音",
            availableVideoFormats=formats
        )

    @classmethod
    def _extract_kuaishou(cls, raw_url: str) -> VideoMetadata:
        session = requests.Session()
        resp = session.get(raw_url, headers={"User-Agent": MOBILE_UA}, timeout=10)
        html = resp.text

        # 尝试匹配快手内嵌视频源
        play_match = re.search(r'"srcNoMark":"(https?://[^"]+)"', html) or re.search(r'"url":"(https?://[^"]+)"', html)
        play_url = play_match.group(1).replace(r"\u002F", "/") if play_match else ""

        title_match = re.search(r'<title>([^<]+)</title>', html)
        title = title_match.group(1).strip() if title_match else "快手短视频"
        title = title.replace(" - 快手", "").strip()

        cover_match = re.search(r'"poster":"(https?://[^"]+)"', html)
        cover = cover_match.group(1).replace(r"\u002F", "/") if cover_match else ""

        if not play_url:
            raise RuntimeError("快手直链嗅探未命中，将尝试通用引擎")

        formats = [
            FormatOption(
                formatId=play_url,
                resolutionLabel="原画高清",
                ext="mp4",
                note="快手免水印"
            )
        ]

        return VideoMetadata(
            url=raw_url,
            title=title,
            author="快手创作者",
            durationText="",
            thumbnailUrl=cover,
            siteName="快手",
            availableVideoFormats=formats
        )

    @classmethod
    def _extract_xiaohongshu(cls, raw_url: str) -> VideoMetadata:
        headers = {"User-Agent": DESKTOP_UA, "Referer": "https://www.xiaohongshu.com/"}
        resp = requests.get(raw_url, headers=headers, timeout=10)
        html = resp.text

        # 解析小红书网页 state 中的视频链接
        video_match = re.search(r'"originVideoKey":"([^"]+)"', html) or re.search(r'"url":"(https?://[^"]+mp4[^"]*)"', html)
        play_url = ""
        if video_match:
            val = video_match.group(1)
            play_url = f"http://sns-video-bd.xhscdn.com/{val}" if not val.startswith("http") else val

        title_match = re.search(r'<title>([^<]+)</title>', html)
        title = title_match.group(1).strip() if title_match else "小红书笔记视频"
        title = title.replace(" - 小红书", "").strip()

        cover_match = re.search(r'<meta name="og:image" content="([^"]+)"', html)
        cover = cover_match.group(1) if cover_match else ""

        if not play_url:
            raise RuntimeError("小红书视频直链未命中，将尝试通用引擎")

        formats = [
            FormatOption(
                formatId=play_url,
                resolutionLabel="原画无水印",
                ext="mp4",
                note="小红书原生流"
            )
        ]

        return VideoMetadata(
            url=raw_url,
            title=title,
            author="小红书作者",
            durationText="",
            thumbnailUrl=cover,
            siteName="小红书",
            availableVideoFormats=formats
        )

    @classmethod
    async def download(
        cls,
        task: DownloadTask,
        on_progress: Callable[[float, str, str, TaskStatus], None],
        cancel_event: asyncio.Event
    ) -> str:
        """流式直接下载国内短视频"""
        clean_title = sanitize_filename(task.title)
        download_dir = Path(config.download_dir)
        download_dir.mkdir(parents=True, exist_ok=True)
        staging_dir = download_dir / ".staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        video_url = task.selected_resolution if task.selected_resolution.startswith("http") else ""
        if not video_url:
            # 重新 extract 直链
            meta = await cls.extract(task.url, task.title)
            video_url = meta.available_video_formats[0].format_id if meta.available_video_formats else ""

        if not video_url and task.download_type != DownloadType.COVER:
            raise RuntimeError("未获取到有效播放直链")

        # 0. 仅保存封面
        if task.download_type == DownloadType.COVER:
            cover_url = task.thumbnail_url or task.url
            dest = download_dir / f"{clean_title}_cover.jpg"
            on_progress(20.0, "", "正在下载封面图片...", TaskStatus.DOWNLOADING)
            resp = await asyncio.to_thread(requests.get, cover_url, headers={"User-Agent": DESKTOP_UA}, timeout=15)
            with open(dest, "wb") as f:
                f.write(resp.content)
            on_progress(100.0, "", "封面保存成功", TaskStatus.COMPLETED)
            return str(dest)

        temp_file = staging_dir / f"domestic_{task.id}.mp4"
        headers = {"User-Agent": MOBILE_UA}

        # 流式下载
        def _download():
            with requests.get(video_url, headers=headers, stream=True, timeout=20) as r:
                r.raise_for_status()
                total_size = int(r.headers.get("content-length", 0))
                downloaded = 0
                start_time = time.time()
                last_time = start_time

                with open(temp_file, "wb") as f:
                    for chunk in r.iter_content(chunk_size=1024 * 64):
                        if cancel_event.is_set():
                            raise asyncio.CancelledError("下载被取消")
                        if chunk:
                            f.write(chunk)
                            downloaded += len(chunk)
                            now = time.time()
                            if now - last_time >= 0.5:
                                last_time = now
                                prog = (downloaded / total_size * 90.0) if total_size > 0 else 50.0
                                speed_mb = (downloaded / (now - start_time)) / (1024 * 1024)
                                on_progress(round(prog, 1), f"{speed_mb:.1f} MB/s", "", TaskStatus.DOWNLOADING)

        await asyncio.to_thread(_download)

        # 后续格式处理
        try:
            if task.download_type == DownloadType.AUDIO_ONLY:
                final_file = download_dir / f"{clean_title}.{task.audio_format.value}"
                on_progress(92.0, "", "正在提取并转码音频...", TaskStatus.PROCESSING)
                await FFmpegHelper.extract_audio(str(temp_file), str(final_file), task.audio_format.value)
            elif task.download_type == DownloadType.VIDEO_ONLY:
                final_file = download_dir / f"{clean_title}_mute.mp4"
                on_progress(92.0, "", "正在消除视频音频...", TaskStatus.PROCESSING)
                await FFmpegHelper.strip_audio(str(temp_file), str(final_file))
            elif task.download_type == DownloadType.GIF:
                final_file = download_dir / f"{clean_title}.gif"
                on_progress(92.0, "", "正在渲染生成 GIF...", TaskStatus.PROCESSING)
                await FFmpegHelper.convert_to_gif(str(temp_file), str(final_file))
            else:
                final_file = download_dir / f"{clean_title}.mp4"
                if final_file.exists():
                    final_file.unlink()
                temp_file.rename(final_file)

            on_progress(100.0, "", "下载完成", TaskStatus.COMPLETED)
            return str(final_file)
        finally:
            if temp_file.exists():
                try:
                    temp_file.unlink()
                except Exception:
                    pass
