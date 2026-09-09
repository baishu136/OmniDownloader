"""
OmniDownloader B站原生极速直连解析与下载引擎 (Windows 移植版)
支持常规视频、多P分集、番剧合集、DASH 原生音视频分离流下载、FFmpeg 高效混流与 Cookie 鉴权
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

PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
BV_PATTERN = re.compile(r"(BV[a-zA-Z0-9]{10})|(av\d+)", re.IGNORECASE)


def sanitize_filename(name: str) -> str:
    """清理文件名中的非法字符"""
    clean = re.sub(r'[\\/:*?"<>|]', "_", name).strip()
    return clean[:120] if len(clean) > 120 else clean


def format_duration(seconds: int) -> str:
    """格式化秒数为时分秒"""
    if seconds <= 0:
        return "未知"
    m, s = divmod(seconds, 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"


class BilibiliDirectExtractor:
    """B站原生解析与下载"""
    session = requests.Session()
    session.headers.update({"User-Agent": PC_UA})
    _cookies_ready = False

    @classmethod
    def _ensure_cookies(cls):
        """自动从 B 站官方接口获取合规 buvid3 / buvid4，免去 412 拦截"""
        if cls._cookies_ready:
            return
        try:
            resp = cls.session.get(
                "https://api.bilibili.com/x/frontend/finger/spi",
                headers={"User-Agent": PC_UA, "Referer": "https://www.bilibili.com"},
                timeout=6
            )
            data = resp.json().get("data", {})
            b3 = data.get("b_3")
            b4 = data.get("b_4")
            if b3:
                cls.session.cookies.set("buvid3", b3, domain=".bilibili.com")
            if b4:
                cls.session.cookies.set("buvid4", b4, domain=".bilibili.com")
            cls._cookies_ready = True
        except Exception as e:
            print(f"[BilibiliExtractor] 初始化 buvid3 提示: {e}")

    @classmethod
    def _get_cookie_header(cls) -> str:
        """获取并拼接用户自定义凭证"""
        cls._ensure_cookies()
        user_cookie = config.bilibili_cookie.strip()
        cookie_parts = []
        for k, v in cls.session.cookies.items():
            cookie_parts.append(f"{k}={v}")

        if user_cookie:
            if "=" not in user_cookie and ";" not in user_cookie:
                cookie_parts.append(f"SESSDATA={user_cookie}")
            else:
                cookie_parts.append(user_cookie)

        return "; ".join(cookie_parts)

    @classmethod
    def extract_bvid(cls, url: str) -> Optional[str]:
        """从 URL 中识别提取 BV 号或 av 号"""
        match = BV_PATTERN.search(url)
        return match.group(0) if match else None

    @classmethod
    async def extract(cls, raw_url: str) -> VideoMetadata:
        """异步解析 B 站视频元数据与分P/清晰度"""
        return await asyncio.to_thread(cls._extract_sync, raw_url)

    @classmethod
    def _extract_sync(cls, raw_url: str) -> VideoMetadata:
        bvid = cls.extract_bvid(raw_url)
        if not bvid:
            raise ValueError("未能识别到 B 站视频 ID (BV/av)，请检查链接")

        headers = {
            "User-Agent": PC_UA,
            "Referer": "https://www.bilibili.com",
            "Cookie": cls._get_cookie_header()
        }

        # 1. 获取视频基本信息
        view_api = f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}" if bvid.upper().startswith("BV") else f"https://api.bilibili.com/x/web-interface/view?aid={bvid[2:]}"
        resp = cls.session.get(view_api, headers=headers, timeout=12)
        resp_json = resp.json()

        if resp_json.get("code") != 0:
            raise RuntimeError(f"B站接口反馈: {resp_json.get('message', '未知错误')}")

        data = resp_json["data"]
        title = data.get("title", "B站视频")
        pic = data.get("pic", "")
        if pic.startswith("//"):
            pic = "https:" + pic
        duration = data.get("duration", 0)
        owner = data.get("owner", {}).get("name", "B站UP主")
        pages = data.get("pages", [])

        # 解析用户指定的特定分P或默认第一P
        cid = pages[0].get("cid", 0) if pages else data.get("cid", 0)
        p_match = re.search(r"[?&]p=(\d+)", raw_url)
        target_p = int(p_match.group(1)) if p_match else 1
        if pages and target_p <= len(pages):
            cid = pages[target_p - 1].get("cid", cid)

        # 2. 获取清晰度列表 (DASH playurl)
        play_api = f"https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&qn=120&fnval=4048&fnver=0&fourk=1"
        play_resp = cls.session.get(play_api, headers=headers, timeout=12).json()

        video_formats: List[FormatOption] = []
        play_data = play_resp.get("data", {})
        accept_quality = play_data.get("accept_quality", [])
        accept_desc = play_data.get("accept_description", [])

        # 映射生成清晰度列表
        for qn, desc in zip(accept_quality, accept_desc):
            height = 1080
            if qn >= 120:
                height = 2160
            elif qn >= 116:
                height = 1080
            elif qn >= 80:
                height = 1080
            elif qn >= 64:
                height = 720
            elif qn >= 32:
                height = 480
            elif qn >= 16:
                height = 360

            video_formats.append(
                FormatOption(
                    formatId=str(qn),
                    resolutionLabel=desc,
                    height=height,
                    ext="mp4",
                    note="DASH 原生流"
                )
            )

        if not video_formats:
            video_formats.append(
                FormatOption(
                    formatId="80",
                    resolutionLabel="1080P 高清 (默认)",
                    height=1080,
                    ext="mp4"
                )
            )

        # 构建分P列表
        multi_list: List[VideoMetadata] = []
        if len(pages) > 1:
            for p in pages:
                p_num = p.get("page", 1)
                p_part = p.get("part", f"P{p_num}")
                p_dur = p.get("duration", 0)
                multi_list.append(
                    VideoMetadata(
                        url=f"https://www.bilibili.com/video/{bvid}?p={p_num}",
                        title=f"P{p_num} {p_part}",
                        author=owner,
                        durationText=format_duration(p_dur),
                        thumbnailUrl=pic,
                        siteName="哔哩哔哩",
                        availableVideoFormats=video_formats
                    )
                )

        final_title = f"{title} (共{len(pages)}集)" if len(pages) > 1 else title
        return VideoMetadata(
            url=f"https://www.bilibili.com/video/{bvid}",
            title=final_title,
            author=owner,
            durationText=format_duration(duration),
            thumbnailUrl=pic,
            siteName="哔哩哔哩",
            availableVideoFormats=video_formats,
            multiMediaList=multi_list
        )

    @classmethod
    async def download(
        cls,
        task: DownloadTask,
        on_progress: Callable[[float, str, str, TaskStatus], None],
        cancel_event: asyncio.Event
    ) -> str:
        """执行 B 站下载任务"""
        bvid = cls.extract_bvid(task.url)
        if not bvid:
            raise ValueError("无法解析 B 站视频 ID")

        on_progress(0.0, "", "正在获取高清播放直链...", TaskStatus.DOWNLOADING)
        headers = {
            "User-Agent": PC_UA,
            "Referer": "https://www.bilibili.com",
            "Cookie": cls._get_cookie_header()
        }

        # 1. 获取视频真实标题与 cid
        view_api = f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}" if bvid.upper().startswith("BV") else f"https://api.bilibili.com/x/web-interface/view?aid={bvid[2:]}"
        view_json = (await asyncio.to_thread(cls.session.get, view_api, headers=headers, timeout=12)).json()
        data = view_json.get("data", {})
        pages = data.get("pages", [])
        real_title = data.get("title", task.title)
        cid = pages[0].get("cid", 0) if pages else data.get("cid", 0)

        # 匹配具体分P
        p_match = re.search(r"[?&]p=(\d+)", task.url)
        p_num = int(p_match.group(1)) if p_match else 1
        if pages:
            for p in pages:
                if p.get("page") == p_num:
                    cid = p.get("cid", cid)
                    part = p.get("part", "")
                    real_title = f"{real_title} - P{p_num} {part}" if part else f"{real_title} - P{p_num}"
                    break

        clean_title = sanitize_filename(real_title)
        download_dir = Path(config.download_dir)
        download_dir.mkdir(parents=True, exist_ok=True)
        staging_dir = download_dir / ".staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        # 0. 仅保存封面处理
        if task.download_type == DownloadType.COVER:
            cover_url = task.thumbnail_url or data.get("pic", "")
            if not cover_url:
                raise ValueError("未获取到封面图地址")
            dest = download_dir / f"{clean_title}_cover.jpg"
            on_progress(30.0, "", "正在下载封面图片...", TaskStatus.DOWNLOADING)
            resp = await asyncio.to_thread(requests.get, cover_url, headers=headers, timeout=15)
            with open(dest, "wb") as f:
                f.write(resp.content)
            on_progress(100.0, "", "封面已保存", TaskStatus.COMPLETED)
            return str(dest)

        # 2. 请求 DASH 流地址
        qn_req = task.selected_resolution if task.selected_resolution.isdigit() else "120"
        play_api = f"https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&qn={qn_req}&fnval=4048&fnver=0&fourk=1"
        play_json = (await asyncio.to_thread(cls.session.get, play_api, headers=headers, timeout=12)).json()
        dash = play_json.get("data", {}).get("dash")
        if not dash:
            raise RuntimeError("未能获取到 B 站 DASH 音视频流")

        video_streams = dash.get("video", [])
        audio_streams = dash.get("audio", [])

        # 选择最匹配的视频轨
        target_video = None
        for v in video_streams:
            if str(v.get("id")) == qn_req:
                target_video = v
                break
        if not target_video and video_streams:
            target_video = video_streams[0]

        # 选择码率最高的音频轨
        target_audio = max(audio_streams, key=lambda a: a.get("bandwidth", 0)) if audio_streams else None

        video_url = target_video.get("baseUrl") or target_video.get("base_url") if target_video else None
        audio_url = target_audio.get("baseUrl") or target_audio.get("base_url") if target_audio else None

        temp_video = staging_dir / f"bili_{task.id}_video.m4s"
        temp_audio = staging_dir / f"bili_{task.id}_audio.m4s"

        stream_headers = {
            "User-Agent": PC_UA,
            "Referer": f"https://www.bilibili.com/video/{bvid}"
        }

        # 内部流式分段下载器
        async def _download_stream(url: str, dest_path: Path, weight: float, base_prog: float, label: str):
            def _download():
                with requests.get(url, headers=stream_headers, stream=True, timeout=20) as r:
                    r.raise_for_status()
                    total_size = int(r.headers.get("content-length", 0))
                    downloaded = 0
                    start_time = time.time()
                    last_time = start_time

                    with open(dest_path, "wb") as f:
                        for chunk in r.iter_content(chunk_size=1024 * 128):
                            if cancel_event.is_set():
                                raise asyncio.CancelledError("下载被取消")
                            if chunk:
                                f.write(chunk)
                                downloaded += len(chunk)
                                now = time.time()
                                if now - last_time >= 0.5:
                                    last_time = now
                                    ratio = downloaded / total_size if total_size > 0 else 0.5
                                    prog = base_prog + ratio * weight
                                    speed_mb = (downloaded / (now - start_time)) / (1024 * 1024)
                                    eta_s = int((total_size - downloaded) / (downloaded / (now - start_time))) if total_size > downloaded and now > start_time else 0
                                    eta_str = f"{eta_s // 60}分{eta_s % 60}秒" if eta_s > 0 else ""
                                    on_progress(round(prog, 1), f"{speed_mb:.1f} MB/s", eta_str, TaskStatus.DOWNLOADING)

            await asyncio.to_thread(_download)

        # 3. 执行分轨下载与合并
        try:
            if task.download_type == DownloadType.AUDIO_ONLY:
                # 仅音频
                if not audio_url:
                    raise RuntimeError("未发现可用音频轨")
                await _download_stream(audio_url, temp_audio, 90.0, 0.0, "下载音频流")
                on_progress(92.0, "", "正在转码音频格式...", TaskStatus.PROCESSING)
                final_file = download_dir / f"{clean_title}.{task.audio_format.value}"
                await FFmpegHelper.extract_audio(str(temp_audio), str(final_file), task.audio_format.value)
            else:
                # 包含画面的下载
                if not video_url:
                    raise RuntimeError("未发现可用视频轨")
                await _download_stream(video_url, temp_video, 75.0, 0.0, "下载视频画面")

                if task.download_type == DownloadType.VIDEO_ONLY:
                    # 仅纯画面
                    final_file = download_dir / f"{clean_title}_mute.mp4"
                    on_progress(80.0, "", "正在导出纯画面视频...", TaskStatus.PROCESSING)
                    await FFmpegHelper.strip_audio(str(temp_video), str(final_file))
                elif task.download_type == DownloadType.GIF:
                    # 动图 GIF
                    final_file = download_dir / f"{clean_title}.gif"
                    on_progress(80.0, "", "正在转换为 GIF 动图...", TaskStatus.PROCESSING)
                    await FFmpegHelper.convert_to_gif(str(temp_video), str(final_file))
                else:
                    # 音画合流
                    if audio_url:
                        await _download_stream(audio_url, temp_audio, 18.0, 75.0, "下载音频轨道")
                        final_file = download_dir / f"{clean_title}.mp4"
                        on_progress(94.0, "", "正在混流封装 MP4 (FFmpeg)...", TaskStatus.PROCESSING)
                        await FFmpegHelper.merge_video_and_audio(str(temp_video), str(temp_audio), str(final_file))
                    else:
                        final_file = download_dir / f"{clean_title}.mp4"
                        temp_video.replace(final_file)

            on_progress(100.0, "", "下载完成", TaskStatus.COMPLETED)
            return str(final_file)
        finally:
            # 清理临时切片
            for p in [temp_video, temp_audio]:
                if p.exists():
                    try:
                        p.unlink()
                    except Exception:
                        pass
