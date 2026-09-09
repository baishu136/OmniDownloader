"""
OmniDownloader TikTok 原生免登录直连解析与下载引擎 (Windows 移植版)
对接 TikWM 接口，秒级绕过 WAF 校验提取无水印高清视频直链
"""

import os
import re
import json
import time
import asyncio
from pathlib import Path
from typing import Optional, List, Dict, Any, Callable
from urllib.parse import quote
import requests

from core.models import VideoMetadata, FormatOption, DownloadTask, DownloadType, TaskStatus
from core.config import config
from engine.ffmpeg_helper import FFmpegHelper

MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"


def sanitize_filename(name: str) -> str:
    clean = re.sub(r'[\\/:*?"<>|]', "_", name).strip()
    return clean[:100] if len(clean) > 100 else clean


class TikTokDirectExtractor:
    """TikTok 原生无水印直接解析"""

    @classmethod
    def is_supported(cls, url: str) -> bool:
        return "tiktok.com" in url.lower()

    @classmethod
    async def extract(cls, raw_url: str) -> VideoMetadata:
        return await asyncio.to_thread(cls._extract_sync, raw_url)

    @classmethod
    def _extract_sync(cls, raw_url: str) -> VideoMetadata:
        # 跟随重定向
        proxies = {"http": config.proxy_url, "https": config.proxy_url} if config.proxy_url else None
        resp = requests.head(raw_url, headers={"User-Agent": MOBILE_UA}, proxies=proxies, allow_redirects=True, timeout=12)
        resolved_url = resp.url

        encoded = quote(resolved_url, safe="")
        api_url = f"https://www.tikwm.com/api/?url={encoded}&hd=1"
        api_resp = requests.get(api_url, headers={"User-Agent": MOBILE_UA}, proxies=proxies, timeout=15)
        res_json = api_resp.json()

        if res_json.get("code") != 0:
            raise RuntimeError(f"TikTok 直连解析接口反馈: {res_json.get('msg', '解析失败')}")

        data = res_json.get("data", {})
        title = data.get("title", "").strip() or "TikTok短视频"
        author = data.get("author", {}).get("nickname", "TikTok用户")
        duration = data.get("duration", 0)
        cover = data.get("cover", "")
        play_url = data.get("hdplay") or data.get("play", "")

        if not play_url:
            raise RuntimeError("未获取到 TikTok 视频下载地址")

        formats = [
            FormatOption(
                formatId=play_url,
                resolutionLabel="1080P/超清无水印",
                ext="mp4",
                note="TikWM 高清流"
            )
        ]

        return VideoMetadata(
            url=raw_url,
            title=title,
            author=author,
            durationText=f"{duration // 60:02d}:{duration % 60:02d}" if duration else "",
            thumbnailUrl=cover,
            siteName="TikTok",
            availableVideoFormats=formats
        )

    @classmethod
    async def download(
        cls,
        task: DownloadTask,
        on_progress: Callable[[float, str, str, TaskStatus], None],
        cancel_event: asyncio.Event
    ) -> str:
        """流式下载 TikTok 视频"""
        clean_title = sanitize_filename(task.title)
        download_dir = Path(config.download_dir)
        download_dir.mkdir(parents=True, exist_ok=True)
        staging_dir = download_dir / ".staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        video_url = task.selected_resolution if task.selected_resolution.startswith("http") else ""
        if not video_url:
            meta = await cls.extract(task.url)
            video_url = meta.available_video_formats[0].format_id if meta.available_video_formats else ""

        if not video_url and task.download_type != DownloadType.COVER:
            raise RuntimeError("未获取到 TikTok 视频播放地址")

        proxies = {"http": config.proxy_url, "https": config.proxy_url} if config.proxy_url else None

        # 0. 仅保存封面
        if task.download_type == DownloadType.COVER:
            cover_url = task.thumbnail_url or task.url
            dest = download_dir / f"{clean_title}_cover.jpg"
            on_progress(20.0, "", "正在保存封面...", TaskStatus.DOWNLOADING)
            resp = await asyncio.to_thread(requests.get, cover_url, headers={"User-Agent": MOBILE_UA}, proxies=proxies, timeout=15)
            with open(dest, "wb") as f:
                f.write(resp.content)
            on_progress(100.0, "", "封面已保存", TaskStatus.COMPLETED)
            return str(dest)

        temp_file = staging_dir / f"tiktok_{task.id}.mp4"

        # 流式下载
        def _download():
            with requests.get(video_url, headers={"User-Agent": MOBILE_UA}, proxies=proxies, stream=True, timeout=25) as r:
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

        try:
            if task.download_type == DownloadType.AUDIO_ONLY:
                final_file = download_dir / f"{clean_title}.{task.audio_format.value}"
                on_progress(92.0, "", "正在抽取音频并转码...", TaskStatus.PROCESSING)
                await FFmpegHelper.extract_audio(str(temp_file), str(final_file), task.audio_format.value)
            elif task.download_type == DownloadType.VIDEO_ONLY:
                final_file = download_dir / f"{clean_title}_mute.mp4"
                on_progress(92.0, "", "正在消除视频音频...", TaskStatus.PROCESSING)
                await FFmpegHelper.strip_audio(str(temp_file), str(final_file))
            elif task.download_type == DownloadType.GIF:
                final_file = download_dir / f"{clean_title}.gif"
                on_progress(92.0, "", "正在渲染生成动图 GIF...", TaskStatus.PROCESSING)
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
