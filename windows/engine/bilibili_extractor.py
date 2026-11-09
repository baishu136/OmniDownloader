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


def collection_items(data, bvid, owner, pic, formats, account):
    """Expand pages or UGC seasons with each child's own BV/CID and duration."""
    pages = data.get('pages') or []
    children = []
    if len(pages) > 1:
        rows = [(bvid, p, f"P{p.get('page', i + 1)} {p.get('part') or ''}", pic, p.get('duration') or 0)
                for i, p in enumerate(pages)]
    else:
        season = data.get('ugc_season') or {}
        rows = []
        for section in season.get('sections') or []:
            for ep in section.get('episodes') or []:
                arc, page = ep.get('arc') or {}, ep.get('page') or {}
                target = ep.get('bvid') or arc.get('bvid') or ''
                if not re.fullmatch(r'BV[a-zA-Z0-9]{10}', target): continue
                page = dict(page, cid=page.get('cid') or arc.get('cid'))
                rows.append((target, page, f"[{season.get('title') or '合集'}] {ep.get('title') or arc.get('title') or target}",
                             arc.get('pic') or pic, arc.get('duration') or page.get('duration') or 0))
    seen = set()
    for index, (target, page, title, cover, duration) in enumerate(rows):
        number = int(page.get('page') or index + 1) if len(pages) > 1 else 1
        identity = (target, number)
        if identity in seen: continue
        seen.add(identity)
        cid = page.get('cid')
        children.append(VideoMetadata(url=f'https://www.bilibili.com/video/{target}' + (f'?p={number}' if number > 1 else ''),
            title=title, author=owner, durationText=format_duration(duration),
            mediaId=f'{target}:{cid}' if cid else '', expectedDurationMs=int(duration * 1000),
            thumbnailUrl='https:' + cover if cover.startswith('//') else cover,
            accountId=account['id'], accountName=account['name'], accountVerifiedAt=account['checkedAt'],
            siteName='哔哩哔哩', availableVideoFormats=formats))
    return children


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
        cls.session.proxies = {'http': config.proxy_url, 'https': config.proxy_url} if config.proxy_url else {}
        cls._ensure_cookies()
        # Remove stale account cookies when the saved session has been cleared/replaced.
        cls.session.cookies.clear()
        from core.session_store import sessions
        cls.session.cookies.update(sessions.jar("哔哩哔哩"))
        user_cookie = config.bilibili_cookie.strip() if sessions.is_enabled("哔哩哔哩") else ''
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
    def _verify_account(cls, headers):
        result = {'id': '', 'name': '', 'verified': False, 'checkedAt': int(time.time() * 1000)}
        if 'SESSDATA=' in headers.get('Cookie', ''):
            try:
                response = cls.session.get('https://api.bilibili.com/x/web-interface/nav', headers=headers, timeout=10).json()
                data = response.get('data') or {}
                if response.get('code') == 0 and data.get('isLogin') is True and data.get('mid'):
                    result.update(id=str(data['mid']), name=data.get('uname') or '', verified=True)
            except (requests.RequestException, ValueError): pass
        from core.session_store import sessions
        sessions.record_validation('哔哩哔哩', result)
        return result

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
        account = cls._verify_account(headers)
        exclusive = data.get('is_upower_exclusive', (data.get('rights') or {}).get('is_upower_exclusive'))
        if exclusive == 1 and not account['verified']: raise RuntimeError('[AUTH_REQUIRED] 充电专属需要验证对应权益账号')
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
        play_resp = cls._playinfo(play_api, headers)

        video_formats: List[FormatOption] = []
        validate_playback(play_resp)
        play_resp = cls._expand(play_resp, play_api, headers)
        play_data = play_resp.get("data", {})
        accept_quality = play_data.get("accept_quality", [])
        accept_desc = play_data.get("accept_description", [])

        # Real DASH candidates; dimensions and codec are separate from Bilibili qn.
        from engine.quality import label, score
        streams = play_data.get("dash", {}).get("video", [])
        for v in sorted(streams, key=lambda f: ((f.get("width") or 0) * (f.get("height") or 0), f.get("bandwidth") or 0), reverse=True):
            w, h = v.get("width") or 0, v.get("height") or 0
            video_formats.append(FormatOption(formatId=f'{v.get("id")}-{v.get("codecid", 0)}',
                resolutionLabel=label(w, h), width=w, height=h, vcodec=v.get("codecs") or "",
                isVideoOnly=True, ext="mp4"))

        if not video_formats:
            raise RuntimeError('[MEDIA_UNCONFIRMED] 当前账号未取得真实DASH格式，请检查登录和权益')

        expected_part = (pages[target_p-1].get('duration', 0) if pages else duration) * 1000
        returned = play_data.get('timelength', 0)
        if expected_part and returned and returned + 2000 < expected_part: raise RuntimeError('[PREVIEW_ONLY] 平台返回时长短于目标分P')
        multi_list = collection_items(data, bvid, owner, pic, video_formats, account)
        final_title = f"{title} (共{len(multi_list)}集)" if multi_list else title
        return VideoMetadata(
            url=f"https://www.bilibili.com/video/{bvid}" + (f"?p={target_p}" if target_p > 1 else ""),
            mediaId=f"{bvid}:{cid}", expectedDurationMs=int((pages[target_p-1].get("duration", 0) if pages else duration) * 1000),
            accessState=access_state(None if exclusive is None else exclusive == 1, account['verified'], bool(streams), False, expected_part, returned),
            accountId=account['id'], accountName=account['name'], accountVerifiedAt=account['checkedAt'],
            exclusiveType='UPOWER' if exclusive == 1 else 'REGULAR' if exclusive == 0 else 'UNKNOWN',
            accessEvidence=[f"accountVerified={account['verified']}", f"exclusive={exclusive}", "playurl.code=0", "dash=true", f"expectedMs={expected_part}", f"returnedMs={returned}"],
            title=final_title,
            author=owner,
            durationText=format_duration(duration),
            thumbnailUrl=pic,
            siteName="哔哩哔哩",
            availableVideoFormats=video_formats,
            multiMediaList=multi_list
        )

    @classmethod
    def _playinfo(cls, api, headers):
        from engine.wbi import key, query
        from urllib.parse import parse_qsl, urlsplit
        def get(url):
            response = cls.session.get(url, headers=headers, timeout=12)
            if response.status_code == 404: return {'code': -404}
            response.raise_for_status()
            return response.json()
        primary = get(api)
        if primary.get('code', 0) not in (-400, -404): return primary
        images = (get('https://api.bilibili.com/x/web-interface/nav').get('data') or {}).get('wbi_img') or {}
        if not images: return primary
        public = key(images.get('img_url', ''), images.get('sub_url', ''))
        return get('https://api.bilibili.com/x/player/wbi/playurl?' + query(dict(parse_qsl(urlsplit(api).query)), public, round(time.time())))

    @classmethod
    def _expand(cls, response, api, headers):
        validate_playback(response)
        data = response.get('data') or {}
        qualities = [int(q) for q in data.get('accept_quality', []) if str(q).isdigit()]
        current = int(re.search(r'qn=(\d+)', api)[1])
        if qualities and max(qualities) > current:
            extra = cls._playinfo(re.sub(r'qn=\d+', f'qn={max(qualities)}', api), headers)
            validate_playback(extra)
            old = data.get('dash') or {}
            new = (extra.get('data') or {}).get('dash') or {}
            unique = {(v.get('id'), v.get('codecid')): v for v in old.get('video', []) + new.get('video', [])}
            if new:
                new['video'] = list(unique.values())
                return extra
        return response

    @classmethod
    async def download(cls, task, on_progress, cancel_event):
        from engine.ytdlp_engine import YtDlpEngine
        if YtDlpEngine.has_ready(task): return await YtDlpEngine.download(task, on_progress, cancel_event)
        bvid = cls.extract_bvid(task.url)
        if not bvid: raise ValueError('无法解析B站视频ID')
        headers = {'User-Agent': PC_UA, 'Referer': 'https://www.bilibili.com/', 'Cookie': cls._get_cookie_header()}
        api = f'https://api.bilibili.com/x/web-interface/view?bvid={bvid}' if bvid.upper().startswith('BV') else f'https://api.bilibili.com/x/web-interface/view?aid={bvid[2:]}'
        view = await asyncio.to_thread(lambda: cls.session.get(api, headers=headers, timeout=12).json())
        if view.get('code') != 0: raise RuntimeError('[CONTENT_UNAVAILABLE] 获取目标视频信息失败')
        data = view['data']; pages = data.get('pages') or []
        if data.get('is_upower_exclusive', (data.get('rights') or {}).get('is_upower_exclusive')) == 1 and not (await asyncio.to_thread(cls._verify_account, headers))['verified']: raise RuntimeError('[AUTH_REQUIRED] 充电专属需要验证对应权益账号')
        match = re.search(r'[?&]p=(\d+)', task.url); number = int(match[1]) if match else 1
        if number < 1 or (pages and number > len(pages)): raise ValueError('[MEDIA_ID_MISMATCH] 分P不存在')
        part = pages[number - 1] if pages else data
        cid = part.get('cid')
        actual_id = f'{bvid}:{cid}'
        if task.media_id and task.media_id != actual_id: raise ValueError('[MEDIA_ID_MISMATCH] 分P身份已改变')
        api = f'https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&qn=120&fnval=4048&fnver=0&fourk=1'
        response = await asyncio.to_thread(lambda: cls._expand(cls._playinfo(api, headers), api, headers))
        dash = (response.get('data') or {}).get('dash') or {}
        formats = []
        for v in dash.get('video', []):
            rate = str(v.get('frame_rate') or '0').split('/')
            fps = float(rate[0]) / float(rate[1]) if len(rate) == 2 and float(rate[1]) else float(rate[0])
            formats.append(dict(v, format_id=f'{v.get("id")}-{v.get("codecid", 0)}', url=v.get('baseUrl') or v.get('base_url'), ext='mp4',
                                vcodec=v.get('codecs') or 'unknown', acodec='none', fps=fps, tbr=v.get('bandwidth', 0) / 1000))
        for a in dash.get('audio', []):
            formats.append(dict(format_id=f'audio-{a.get("id")}', url=a.get('baseUrl') or a.get('base_url'), ext='m4a',
                                vcodec='none', acodec=a.get('codecs') or 'mp4a.40.2', abr=a.get('bandwidth', 0) / 1000))
        if not formats: raise RuntimeError('[MEDIA_UNCONFIRMED] 未取得目标视频DASH流')
        info = dict(id=actual_id, title=task.title, webpage_url=task.url, extractor='BilibiliNative',
                    duration=part.get('duration') or data.get('duration'), thumbnail=task.thumbnail_url or data.get('pic'), formats=formats,
                    http_headers={'User-Agent': PC_UA, 'Referer': f'https://www.bilibili.com/video/{bvid}'})
        with YtDlpEngine._lock: YtDlpEngine._cache[YtDlpEngine._key(task.url, '哔哩哔哩')] = (time.monotonic(), info)
        return await YtDlpEngine.download(task, on_progress, cancel_event)


def validate_playback(response):
    if response.get('code', 0) != 0:
        reason = {-101: 'AUTH_REQUIRED', -352: 'CHALLENGE_REQUIRED', -412: 'CHALLENGE_REQUIRED', -403: 'ACCESS_DENIED', -10403: 'ACCESS_DENIED', -404: 'CONTENT_UNAVAILABLE'}.get(response.get('code'), 'EXTRACTOR_CHANGED')
        raise RuntimeError(f'[{reason}] B站播放接口拒绝访问，请检查目标账号、权益和接口状态')
    data = response.get('data') or {}
    if data.get('is_preview') or any('试看' in str(x) for x in data.get('accept_description', [])):
        raise RuntimeError('[PREVIEW_ONLY] 当前仅取得试看，请确认目标视频的充电权益')


def access_state(exclusive, account_verified, has_stream, preview, expected_ms, returned_ms):
    if preview or (expected_ms and returned_ms and returned_ms + 2000 < expected_ms): return 'PREVIEW'
    if exclusive is True and not account_verified: return 'DENIED'
    if not has_stream or not expected_ms or not returned_ms: return 'UNKNOWN'
    return 'FULL' if abs(expected_ms-returned_ms) <= 2000 and (exclusive is False or account_verified) else 'UNKNOWN'
