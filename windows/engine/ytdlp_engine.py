"""
OmniDownloader yt-dlp 原生通用解析与下载引擎 (Windows 移植版)
用于 YouTube, X (Twitter), Instagram, Facebook, Pinterest 等海内外主流及通用媒体网站
"""

import os
import re
import asyncio
from pathlib import Path
from typing import Optional, List, Dict, Any, Callable
import yt_dlp

from core.models import VideoMetadata, FormatOption, DownloadTask, DownloadType, TaskStatus
from core.config import config
from engine.ffmpeg_helper import get_ffmpeg_path, FFmpegHelper


def format_duration(seconds: Optional[int]) -> str:
    if not seconds or seconds <= 0:
        return ""
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"


def format_bytes(b: Optional[int]) -> str:
    if not b or b <= 0:
        return ""
    if b >= 1024 * 1024 * 1024:
        return f"{b / (1024 * 1024 * 1024):.1f} GB"
    return f"{b / (1024 * 1024):.1f} MB"


def clean_media_title(raw_title: str) -> str:
    """清理标题：彻底移除内嵌的 URL 短链、链接与 Windows 非法字符"""
    if not raw_title:
        return "video"
    # 移除标题中附带的 http/https 链接（如推特推文自带的 https://t.co/xxx）
    t = re.sub(r'https?://\S+', '', raw_title).strip()
    # 移除多余的短横线、下划线、竖线与两端空格
    t = re.sub(r'^[_\-\s|]+|[_\-\s|]+$', '', t).strip()
    # 清理 Windows 非法文件名字符与不可见换行符
    t = re.sub(r'[\\/:*?"<>|\r\n\t]', '_', t).strip()
    # 去除首尾的点和空格
    t = t.strip('. ')
    return t[:80] if t else "video"


def extract_height_from_label(label: str) -> int:
    lower = label.lower().strip()
    if lower.startswith("http-") or lower.startswith("hls-"):
        # 针对 http-2176 等码率标识，不属于常规分辨率高度
        return 0
    if "4k" in lower:
        return 2160
    if "2k" in lower:
        return 1440
    m = re.search(r"(\d{3,4})[pP]", label)
    if m:
        return int(m.group(1))
    return 0


class YtDlpSafeLogger:
    """安全的空日志器，避免在 Windows 无窗口模式下由于向 None 的 stdout 打印导致 Errno 22"""
    def debug(self, msg: str):
        pass

    def info(self, msg: str):
        pass

    def warning(self, msg: str):
        pass

    def error(self, msg: str):
        pass


class YtDlpEngine:
    """yt-dlp 原生 API 封装"""

    @classmethod
    def _build_ydl_opts(cls, proxy_url: str = "", extra_headers: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        opts: Dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "ignoreerrors": True,
            "no_color": True,
            "geo_bypass": True,
            "socket_timeout": 30,
            "logger": YtDlpSafeLogger(),
            "logtostderr": False,
        }

        ffmpeg_bin = get_ffmpeg_path()
        if ffmpeg_bin:
            opts["ffmpeg_location"] = os.path.dirname(ffmpeg_bin)

        if proxy_url:
            opts["proxy"] = proxy_url.strip()

        if extra_headers:
            opts["http_headers"] = extra_headers

        return opts

    @classmethod
    async def extract(cls, raw_url: str, site_name: str = "") -> VideoMetadata:
        return await asyncio.to_thread(cls._extract_sync, raw_url, site_name)

    @classmethod
    def _extract_sync(cls, raw_url: str, site_name: str = "") -> VideoMetadata:
        proxy_url = config.proxy_url
        headers = {}
        if site_name == "YouTube":
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        elif site_name == "X (Twitter)":
            headers["User-Agent"] = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15"

        opts = cls._build_ydl_opts(proxy_url, headers)
        opts["extract_flat"] = False

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(raw_url, download=False)
            if not info:
                raise RuntimeError("yt-dlp 未能获取到视频信息")

            # 处理推文多视频或多 entry 列表
            entries = info.get("entries")
            if entries and isinstance(entries, list) and len(entries) > 0:
                first_entry = entries[0]
                multi_list = []
                for idx, item in enumerate(entries):
                    if not item:
                        continue
                    multi_list.append(
                        VideoMetadata(
                            url=item.get("webpage_url") or item.get("url") or raw_url,
                            title=item.get("title") or f"视频片段 {idx+1}",
                            author=item.get("uploader") or "",
                            durationText=format_duration(item.get("duration")),
                            thumbnailUrl=item.get("thumbnail") or "",
                            siteName=site_name or "网络视频",
                            availableVideoFormats=[]
                        )
                    )
                info = first_entry
            else:
                multi_list = []

            title = info.get("title", "网络视频")
            author = info.get("uploader") or info.get("creator") or info.get("channel") or ""
            duration = info.get("duration")
            thumbnail = info.get("thumbnail") or ""

            # 解析可用清晰度
            formats_raw = info.get("formats", [])
            video_formats: Dict[str, FormatOption] = {}

            # 遍历 formats 聚合出常用分辨率选项
            for f in formats_raw:
                vcodec = f.get("vcodec", "")
                acodec = f.get("acodec", "")
                height = f.get("height") or 0
                width = f.get("width") or 0
                filesize = f.get("filesize") or f.get("filesize_approx")

                if height <= 0 and width <= 0:
                    continue
                if vcodec == "none":
                    continue

                label = f"{height}p"
                if height >= 2160:
                    label = "4K (2160p)"
                elif height >= 1440:
                    label = "2K (1440p)"
                elif height >= 1080:
                    label = "1080p"
                elif height >= 720:
                    label = "720p"
                elif height >= 480:
                    label = "480p"
                elif height >= 360:
                    label = "360p"

                size_str = format_bytes(filesize)
                if label not in video_formats or (filesize and not video_formats[label].approximate_size):
                    video_formats[label] = FormatOption(
                        formatId=str(f.get("format_id", label)),
                        resolutionLabel=label,
                        width=width,
                        height=height,
                        fps=int(f.get("fps") or 0),
                        ext="mp4",
                        approximateSize=size_str,
                        note="最佳视频编码轨"
                    )

            # 排序（高分辨率优先）
            sorted_formats = sorted(
                list(video_formats.values()),
                key=lambda x: x.height,
                reverse=True
            )

            if not sorted_formats:
                sorted_formats.append(
                    FormatOption(
                        formatId="best",
                        resolutionLabel="自适应最高画质",
                        ext="mp4"
                    )
                )

            return VideoMetadata(
                url=raw_url,
                title=title,
                author=author,
                durationText=format_duration(duration),
                thumbnailUrl=thumbnail,
                siteName=site_name or info.get("extractor_key") or "网络视频",
                availableVideoFormats=sorted_formats,
                multiMediaList=multi_list
            )

    @classmethod
    async def download(
        cls,
        task: DownloadTask,
        on_progress: Callable[[float, str, str, TaskStatus], None],
        cancel_event: asyncio.Event
    ) -> str:
        """运行 yt-dlp 进行实际文件下载与混流"""
        clean_title = clean_media_title(task.title) or f"video_{task.id}"
        download_dir = Path(config.download_dir)
        download_dir.mkdir(parents=True, exist_ok=True)
        staging_dir = download_dir / ".staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        # 0. 仅保存封面
        if task.download_type == DownloadType.COVER:
            cover_url = task.thumbnail_url or task.url
            dest = download_dir / f"{clean_title}_cover.jpg"
            on_progress(20.0, "", "正在保存封面图...", TaskStatus.DOWNLOADING)
            import requests
            resp = await asyncio.to_thread(requests.get, cover_url, timeout=15)
            with open(dest, "wb") as f:
                f.write(resp.content)
            on_progress(100.0, "", "封面已保存", TaskStatus.COMPLETED)
            return str(dest)

        outtmpl = str(staging_dir / f"{task.id}_stream.%(ext)s")
        opts = cls._build_ydl_opts(config.proxy_url)
        opts["outtmpl"] = outtmpl
        opts["windowsfilenames"] = True
        opts["restrictfilenames"] = True
        opts["retries"] = 10
        opts["fragment_retries"] = 10
        opts["nopart"] = True  # 彻底禁用临时 .part 命名

        # progress hook
        def hook(d: Dict[str, Any]):
            if cancel_event.is_set():
                raise asyncio.CancelledError("用户取消下载")
            status = d.get("status")
            if status == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                downloaded = d.get("downloaded_bytes") or 0
                prog = (downloaded / total * 90.0) if total > 0 else 50.0
                speed = d.get("speed") or 0
                speed_str = f"{speed / (1024 * 1024):.1f} MB/s" if speed > 0 else ""
                eta = d.get("eta") or 0
                eta_str = f"{eta // 60}分{eta % 60}秒" if eta > 0 else ""
                on_progress(round(prog, 1), speed_str, eta_str, TaskStatus.DOWNLOADING)
            elif status == "finished":
                on_progress(92.0, "", "下载完成，正在合并封装...", TaskStatus.PROCESSING)

        opts["progress_hooks"] = [hook]

        # 格式与转码策略
        req_height = extract_height_from_label(task.selected_resolution)
        selected_fmt = task.selected_resolution.strip()

        if task.download_type == DownloadType.VIDEO_WITH_AUDIO:
            if selected_fmt and selected_fmt != "best" and not selected_fmt.endswith("p"):
                # 如果是明确的格式 ID (如 http-2176, hls-872, 137, 22 等)
                opts["format"] = f"{selected_fmt}+bestaudio/{selected_fmt}/best"
            elif req_height > 0:
                opts["format"] = f"bestvideo[height<={req_height}]+bestaudio/best[height<={req_height}]/best"
            else:
                opts["format"] = "bestvideo+bestaudio/best"
            opts["merge_output_format"] = "mp4"

        elif task.download_type == DownloadType.VIDEO_ONLY:
            if selected_fmt and selected_fmt != "best" and not selected_fmt.endswith("p"):
                opts["format"] = f"{selected_fmt}/best"
            elif req_height > 0:
                opts["format"] = f"bestvideo[height<={req_height}]/best[height<={req_height}]"
            else:
                opts["format"] = "bestvideo/best"
            opts["postprocessor_args"] = ["-an"]

        elif task.download_type == DownloadType.AUDIO_ONLY:
            opts["format"] = "bestaudio/best"
            opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": task.audio_format.value,
                "preferredquality": "0",
            }]
        elif task.download_type == DownloadType.GIF:
            opts["format"] = "bestvideo/best"

        on_progress(0.0, "", "准备启动下载引擎...", TaskStatus.DOWNLOADING)

        def _exec():
            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.download([task.url])

        await asyncio.to_thread(_exec)

        # 严格寻找暂存目录中生成的有效媒体文件（彻底排除 .part / .ytdl / .temp 临时文件）
        staged_files = [
            f for f in staging_dir.glob(f"{task.id}_*")
            if f.is_file() and not f.name.endswith(".part") and not f.name.endswith(".ytdl") and not f.name.endswith(".temp")
        ]

        if not staged_files:
            # 清理残留的未完成临时碎片
            for p in staging_dir.glob(f"{task.id}_*"):
                try:
                    p.unlink()
                except Exception:
                    pass
            raise RuntimeError("媒体文件未完全生成或下载合并被中断，请重试")

        target_staged = max(staged_files, key=lambda f: f.stat().st_mtime)

        # 如果是 GIF 则进行二次转换
        if task.download_type == DownloadType.GIF:
            final_file = download_dir / f"{clean_title}.gif"
            on_progress(95.0, "", "正在使用 FFmpeg 渲染 GIF...", TaskStatus.PROCESSING)
            await FFmpegHelper.convert_to_gif(str(target_staged), str(final_file))
            try:
                target_staged.unlink()
            except Exception:
                pass
        else:
            final_file = download_dir / f"{clean_title}{target_staged.suffix}"
            counter = 1
            while final_file.exists():
                final_file = download_dir / f"{clean_title}_{counter}{target_staged.suffix}"
                counter += 1

            target_staged.rename(final_file)

        on_progress(100.0, "", "下载完成", TaskStatus.COMPLETED)
        return str(final_file)
