"""Session-aware extraction and explicit per-media quality selection."""
import asyncio
import copy
import os
import hashlib
import shutil
import logging
from pathlib import Path
import re
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit
import yt_dlp
from core.models import VideoMetadata, FormatOption, DownloadType, TaskStatus
from core.config import config
from core.session_store import sessions, DOMAINS
from engine.ffmpeg_helper import get_ffmpeg_path, FFmpegHelper
from engine.quality import choose, label, score, height_from_legacy
from engine.media_errors import sanitize
from engine.media_verifier import verify, probe as probe_output


def format_duration(seconds):
    if not seconds: return ''
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f'{h:02}:{m:02}:{s:02}' if h else f'{m:02}:{s:02}'


def format_bytes(value):
    return f'{value / 1048576:.1f} MB' if value else ''


def clean_media_title(title):
    title = re.sub(r'https?://\S+', '', title or '')
    return re.sub(r'[\\/:*?"<>|\r\n\t]', '_', title).strip('. ')[:80] or 'video'


extract_height_from_label = height_from_legacy


class YtDlpSafeLogger:
    def debug(self, msg): logging.getLogger('omni.ytdlp').debug(sanitize(msg))
    def info(self, msg): logging.getLogger('omni.ytdlp').info(sanitize(msg))
    def warning(self, msg): logging.getLogger('omni.ytdlp').warning(sanitize(msg))
    def error(self, msg): logging.getLogger('omni.ytdlp').error(sanitize(msg))


class YtDlpEngine:
    _cache = {}
    _lock = threading.RLock()

    @classmethod
    def _build_ydl_opts(cls, proxy_url='', extra_headers=None):
        opts = {'quiet': True, 'no_warnings': False, 'ignoreerrors': False,
                'socket_timeout': 25, 'logger': YtDlpSafeLogger(), 'retries': 2,
                'fragment_retries': 2, 'noplaylist': True, 'format_sort': ['res', 'fps']}
        if get_ffmpeg_path(): opts['ffmpeg_location'] = get_ffmpeg_path()
        if proxy_url: opts['proxy'] = proxy_url.strip()
        if extra_headers: opts['http_headers'] = extra_headers
        runtime = Path(__file__).resolve().parents[1] / 'bin' / 'deno.exe'
        if runtime.is_file(): opts['js_runtimes'] = {'deno': {'path': str(runtime)}}
        return opts

    @staticmethod
    def _identity(raw):
        parts = urlsplit(raw)
        query = parse_qs(parts.query, keep_blank_values=True)
        media = query.pop('omni_media', [''])[0]
        return urlunsplit(parts._replace(query=urlencode(query, doseq=True))), media

    @classmethod
    def _key(cls, raw, site):
        generation = sessions.status(site)['generation'] if site in DOMAINS else 0
        return raw, site, generation, config.proxy_url

    @classmethod
    def _resolve(cls, raw, site, ydl):
        key = cls._key(raw, site)
        with cls._lock: cached = cls._cache.get(key)
        if cached and time.monotonic() - cached[0] < 600:
            return copy.deepcopy(cached[1])
        url, media = cls._identity(raw)
        try:
            if site == 'Pinterest':
                from engine.media_resolver import resolve_site_video
                info = resolve_site_video(url, site, ydl.params.get('cookiefile'), ydl.params.get('proxy', ''))
            else: info = ydl.extract_info(url, download=False)
        except yt_dlp.utils.DownloadError as exc:
            from engine.media_errors import code
            if site == 'Instagram' and code(exc) == 'NETWORK_ERROR':
                time.sleep(.8)
                info = ydl.extract_info(url, download=False)
                if not info: raise RuntimeError('[MEDIA_UNCONFIRMED] 重试后仍未取得目标媒体')
                with cls._lock: cls._cache[key] = (time.monotonic(), copy.deepcopy(info))
                return info
            if site != 'X (Twitter)' or not any(value in str(exc).lower() for value in ('no video could be found', '429', 'timed out', '502', '503', 'connection reset', 'connection aborted', 'remote end closed', 'unexpected_eof', 'server disconnected')): raise
            # A new public request avoids cached _selected_api and logged-in GraphQL preflight.
            from engine.media_resolver import resolve_x_video
            info = resolve_x_video(url, proxy=ydl.params.get('proxy', ''))
        if not info: raise RuntimeError('[MEDIA_UNCONFIRMED] 本次未取得媒体')
        entries = [e for e in (info.get('entries') or []) if e]
        if media:
            info = next((e for e in entries if str(e.get('id')) == media), info if str(info.get('id')) == media else None)
            if not info: raise RuntimeError('[MEDIA_ID_MISMATCH] 原媒体已不可定位')
        with cls._lock:
            if len(cls._cache) >= 128: cls._cache.clear()
            cls._cache[key] = (time.monotonic(), copy.deepcopy(info))
        return info

    @classmethod
    async def extract(cls, raw_url, site_name=''):
        return await asyncio.to_thread(cls._extract_sync, raw_url, site_name)

    @classmethod
    def _extract_sync(cls, raw_url, site_name=''):
        opts = cls._build_ydl_opts(config.proxy_url)
        opts['noplaylist'] = False
        with sessions.cookie_file(site_name) as cookie:
            if cookie: opts['cookiefile'] = cookie
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = cls._resolve(raw_url, site_name, ydl)
                entries = [e for e in (info.get('entries') or []) if e]
                if not entries: return cls._metadata(info, raw_url, site_name)
                children = []
                for entry in entries[:200]:
                    if site_name == 'X (Twitter)':
                        parts = urlsplit(raw_url)
                        query = parse_qs(parts.query); query['omni_media'] = [str(entry['id'])]
                        child = urlunsplit(parts._replace(query=urlencode(query, doseq=True)))
                    else: child = entry.get('webpage_url') or entry.get('original_url') or entry.get('url')
                    if not child: continue
                    with cls._lock: cls._cache[cls._key(child, site_name)] = (time.monotonic(), copy.deepcopy(entry))
                    children.append(cls._metadata(entry, child, site_name))
                if not children: raise RuntimeError('[NO_MEDIA] 合集没有可定位的媒体')
                return children[0].model_copy(update={'multi_media_list': children})

    @classmethod
    def _metadata(cls, info, url, site):
        video, audio = [], []
        for f in sorted(info.get('formats') or [], key=score, reverse=True):
            if f.get('has_drm'): continue
            w, h = int(f.get('width') or 0), int(f.get('height') or 0)
            if f.get('vcodec') == 'none':
                audio.append(FormatOption(formatId=str(f['format_id']), resolutionLabel='音频', isAudioOnly=True, ext=f.get('ext') or 'm4a'))
            elif w or h or (site == 'X (Twitter)' and 'video.twimg.com/tweet_video/' in f.get('url', '')):
                video.append(FormatOption(formatId=str(f['format_id']), resolutionLabel=label(w, h) if w or h else '源动图（尺寸未知）',
                    width=w, height=h, fps=int(f.get('fps') or 0), ext=f.get('ext') or '',
                    vcodec=f.get('vcodec') or '', acodec=f.get('acodec') or '',
                    isVideoOnly=f.get('acodec') == 'none', approximateSize=format_bytes(f.get('filesize') or f.get('filesize_approx')),
                    note=' · '.join(filter(None, [f.get('vcodec'), f.get('dynamic_range')]))))
        if not video: raise RuntimeError('[MEDIA_UNCONFIRMED] 未取得真实视频格式')
        return VideoMetadata(url=url, title=info.get('title') or '媒体', author=info.get('uploader') or '',
            isGif=site == 'X (Twitter)' and any('video.twimg.com/tweet_video/' in f.get('url', '') for f in info.get('formats') or []) and not any('ext_tw_video' in f.get('url', '') or 'amplify_video' in f.get('url', '') for f in info.get('formats') or []),
            durationText=format_duration(info.get('duration')), thumbnailUrl=info.get('thumbnail') or '',
            siteName=site, availableVideoFormats=video, availableAudioFormats=audio,
            mediaId=str(info.get('id', '')), expectedDurationMs=int((info.get('duration') or 0) * 1000))

    @staticmethod
    def workspace(task):
        return Path(os.environ.get('OMNI_STATE_DIR') or Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'OmniDownloader') / 'media_tasks' / task.id

    @staticmethod
    def signature(task):
        intent = task.quality_selection.model_dump(by_alias=True) if task.quality_selection else {}
        return hashlib.sha256(f'{task.url}|{task.selected_resolution}|{task.selected_video_format_id}|{task.download_type}|{intent}'.encode()).hexdigest()

    @classmethod
    def has_ready(cls, task):
        import json
        try:
            data = json.loads((cls.workspace(task) / 'ready.json').read_text(encoding='utf-8'))
            return data['signature'] == cls.signature(task) and (cls.workspace(task) / Path(data['file']).name).is_file()
        except (OSError, ValueError, KeyError): return False

    @classmethod
    async def download(cls, task, on_progress, cancel_event):
        try:
            return await asyncio.to_thread(cls._download_sync, task, on_progress, cancel_event)
        except yt_dlp.utils.DownloadError as exc:
            if cancel_event.is_set() or not any(x in str(exc).lower() for x in ('http error 403', 'http error 410', 'url has expired')): raise
            from engine.url_sniffer import UrlSniffer
            site = UrlSniffer.identify_site(task.url)
            if site not in ('X (Twitter)', 'YouTube'): raise
            with cls._lock: cls._cache.pop(cls._key(task.url, site), None)
            on_progress(0, '', '刷新同一媒体地址（仅一次）', TaskStatus.DOWNLOADING)
            return await asyncio.to_thread(cls._download_sync, task, on_progress, cancel_event)

    @classmethod
    def _download_sync(cls, task, on_progress, cancel_event):
        from engine.url_sniffer import UrlSniffer
        import json
        site = UrlSniffer.identify_site(task.url)
        staging = cls.workspace(task)
        staging.mkdir(parents=True, exist_ok=True)
        ready = staging / 'ready.json'
        title = clean_media_title(task.title)
        intent = task.quality_selection.model_dump(by_alias=True) if task.quality_selection else {}
        signature = cls.signature(task)
        source = None
        if ready.is_file():
            data = json.loads(ready.read_text(encoding='utf-8'))
            candidate_path = staging / Path(data['file']).name
            if data['signature'] == signature and candidate_path.is_file(): source = candidate_path
        if source is None:
            opts = cls._build_ydl_opts(config.proxy_url)
            opts.update(outtmpl=str(staging / 'media.%(ext)s'), windowsfilenames=True)
            final_paths = []
            def hook(data):
                if cancel_event.is_set(): raise RuntimeError('[CANCELLED] 用户取消')
                total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
                on_progress(data.get('downloaded_bytes', 0) / total * 90 if total else 0, '', '', TaskStatus.DOWNLOADING)
            def post_hook(data):
                path = data.get('info_dict', {}).get('filepath')
                if data.get('status') == 'finished' and path: final_paths.append(Path(path))
            opts.update(progress_hooks=[hook], postprocessor_hooks=[post_hook])
            selected, expected_duration, require_audio = None, 0, False
            with sessions.cookie_file(site) as cookie:
                if cookie: opts['cookiefile'] = cookie
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = cls._resolve(task.url, site, ydl)
                    if info.get('entries'): raise RuntimeError('[MEDIA_ID_MISMATCH] 请先选择具体子媒体')
                    formats = info.get('formats') or []
                    expected_duration = info.get('duration') or 0
                    if not intent:
                        limit = height_from_legacy(task.selected_resolution)
                        intent = {'mode': 'MAX_HEIGHT' if limit else 'BEST_AVAILABLE', 'maxHeight': limit}
                    exact = task.selected_video_format_id or task.selected_resolution
                    if exact in ('', 'best', 'direct', '默认画质') or height_from_legacy(exact) or exact == info.get('_omni_native_source_id'): exact = ''
                    if task.download_type == DownloadType.COVER:
                        opts.update(writethumbnail=True, skip_download=True)
                    elif task.download_type == DownloadType.AUDIO_ONLY:
                        opts.update(format='bestaudio/best', postprocessors=[{'key': 'FFmpegExtractAudio', 'preferredcodec': task.audio_format.value}])
                    else:
                        selected = choose(formats, intent, exact)
                        fid = str(selected['format_id'])
                        audio_tracks = any(f.get('vcodec') == 'none' for f in formats)
                        require_audio = bool(selected.get('acodec') and selected.get('acodec') != 'none') or audio_tracks
                        if task.download_type == DownloadType.VIDEO_WITH_AUDIO and selected.get('acodec') == 'none' and not audio_tracks:
                            raise RuntimeError('[NO_AUDIO] 目标未提供音轨，请选择仅视频')
                        if task.download_type == DownloadType.VIDEO_ONLY:
                            opts.update(format=fid, postprocessors=[{'key': 'FFmpegVideoRemuxer', 'preferedformat': 'mp4'}],
                                        postprocessor_args={'VideoRemuxer': ['-an']})
                        else:
                            opts['format'] = fid if selected.get('acodec') != 'none' else f'{fid}+bestaudio'
                            opts['merge_output_format'] = 'mkv' if intent.get('container') == 'auto' else intent.get('container', 'mp4')
                    ydl.params.update(opts)
                    with yt_dlp.YoutubeDL(opts) as downloader:
                        result = downloader.process_ie_result(info, download=True)
                    output = result.get('filepath') or result.get('_filename')
                    paths = final_paths + ([Path(output)] if output else [])
                    paths += [Path(f['filepath']) for f in result.get('requested_downloads', []) if f.get('filepath')]
                    if task.download_type == DownloadType.COVER:
                        paths += [Path(t['filepath']) for t in result.get('thumbnails', []) if t.get('filepath')]
                    source = next((p for p in reversed(paths) if p.is_file() and p.parent.resolve() == staging.resolve()), None)
                    if not source: raise RuntimeError('[POSTPROCESS_FAILED] 引擎未返回已完成媒体文件')
            if task.download_type == DownloadType.VIDEO_ONLY:
                muted = staging / 'muted.mp4'
                if not asyncio.run(FFmpegHelper.strip_audio(str(source), str(muted))): raise RuntimeError('[POSTPROCESS_FAILED] 移除音轨失败')
                if probe_output(muted)['audio']: raise RuntimeError('[POSTPROCESS_FAILED] 仅视频成品仍包含音轨')
                source = muted
            if task.download_type == DownloadType.GIF:
                target = staging / 'result.gif'
                if not asyncio.run(FFmpegHelper.convert_to_gif(str(source), str(target))): raise RuntimeError('[POSTPROCESS_FAILED] GIF转换失败')
                source = target
            elif task.download_type != DownloadType.COVER:
                verify(source, selected, expected_duration, require_audio and task.download_type == DownloadType.VIDEO_WITH_AUDIO)
            ready.write_text(json.dumps({'signature': signature, 'file': source.name}), encoding='utf-8')
        if cancel_event.is_set(): raise RuntimeError('[CANCELLED] 用户取消')
        destination = Path(config.download_dir) / f'{title}{source.suffix}'
        index = 1
        while destination.exists():
            destination = destination.with_name(f'{title}_{index}{source.suffix}'); index += 1
        try:
            destination.parent.mkdir(parents=True, exist_ok=True)
            partial = destination.with_name('.' + destination.name + '.' + task.id + '.part')
            shutil.copy2(source, partial)
            if partial.stat().st_size != source.stat().st_size: raise OSError('导出长度不符')
            partial.replace(destination)
            source.unlink()
        except OSError as exc: raise RuntimeError('[EXPORT_FAILED] 成品已保留，可以重试导出') from exc
        ready.unlink(missing_ok=True)
        on_progress(100, '', '', TaskStatus.PROCESSING)
        return str(destination)
