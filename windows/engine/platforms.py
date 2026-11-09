"""Native site adapters: strict content identity, Pixiv frames, bounded public pages."""
import asyncio
import html
import hashlib
import json
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit, urlunsplit
import zipfile
import requests
from core.config import config
from core.models import VideoMetadata, FormatOption, DownloadType, TaskStatus
from core.session_store import sessions, DOMAINS
from engine.ffmpeg_helper import FFmpegHelper
from engine.media_verifier import verify
from engine.quality import label

HOSTS = {'哔哩哔哩': ('bilibili.com', 'b23.tv'), 'YouTube': ('youtube.com', 'youtu.be'),
         'X (Twitter)': ('x.com', 'twitter.com', 't.co'), '抖音': ('douyin.com', 'iesdouyin.com'),
         '小红书': ('xiaohongshu.com', 'xhslink.com', 'xhslink.cn', 'rednote.com'),
         '小黑盒': ('xiaoheihe.cn', 'heybox.cn'), 'Pixiv': ('pixiv.net',),
         'TikTok': ('tiktok.com',), '快手': ('kuaishou.com', 'kwai.com', 'gifshow.com', 'chenzhongtech.com'),
         'Instagram': ('instagram.com',), 'Facebook': ('facebook.com', 'fb.watch', 'fb.me'),
         'Pinterest': ('pinterest.com', 'pin.it')}


def platform(url):
    host = (urlsplit(url).hostname or '').lower()
    return next((site for site, domains in HOSTS.items() if any(host == d or host.endswith('.' + d) for d in domains)), '网络视频')


def normalize(url):
    if re.fullmatch(r'(?i)BV[a-z0-9]{10}', url): return f'https://www.bilibili.com/video/{url}'
    parts = urlsplit(url)
    if parts.scheme not in ('http', 'https') or not parts.hostname: raise ValueError('需要有效HTTP(S)链接')
    if platform(url) == '快手' and (parts.hostname == 'chenzhongtech.com' or parts.hostname.endswith('.chenzhongtech.com')):
        match = re.search(r'/photo/([a-zA-Z0-9]+)', parts.path)
        if match: return f'https://www.kuaishou.com/short-video/{match[1]}'
    if platform(url) == '小红书' and parts.path == '/login':
        target = parse_qs(parts.query).get('redirectPath', [''])[0]
        if target.startswith('/') and not target.startswith('//'): target = urljoin('https://www.xiaohongshu.com', target)
        if platform(target) != '小红书' or not re.search(r'/(explore|discovery/item)/[a-z0-9]+', urlsplit(target).path):
            raise ValueError('[AUTH_REQUIRED] 登录页没有合法内容地址')
        return urlunsplit(urlsplit(target)._replace(scheme='https'))
    return url


def http(site):
    from engine.media_resolver import session as request_session
    session = request_session(proxy=config.proxy_url)
    if site in DOMAINS: session.cookies.update(sessions.jar(site))
    if config.proxy_url: session.proxies.update({'http': config.proxy_url, 'https': config.proxy_url})
    return session


def page(session, url):
    for _ in range(5):
        response = session.get(url, timeout=25, allow_redirects=False)
        if response.status_code in (301, 302, 303, 307, 308):
            target = urljoin(url, response.headers.get('Location', ''))
            if '/login' in urlsplit(target).path: raise RuntimeError('[AUTH_REQUIRED] 请验证会话后重新解析原内容页')
            if any(x in urlsplit(target).path for x in ('captcha', 'challenge')): raise RuntimeError('[CHALLENGE_REQUIRED] 请在浏览器完成验证')
            url = target; continue
        response.raise_for_status()
        if len(response.content) > 8 * 1024 * 1024: raise ValueError('页面超过解析大小限制')
        return response
    raise RuntimeError('[NETWORK_ERROR] 页面跳转次数过多')


def find_item(value, identifier):
    if isinstance(value, dict):
        if str(value.get('aweme_id', value.get('awemeId', ''))) == identifier: return value
        for child in value.values():
            result = find_item(child, identifier)
            if result is not None: return result
    elif isinstance(value, list):
        for child in value:
            result = find_item(child, identifier)
            if result is not None: return result
    return None


def douyin_item(items, identifier):
    item = find_item(items, identifier)
    if item is None: raise RuntimeError('[MEDIA_ID_MISMATCH] 平台未返回目标作品，拒绝使用推荐视频')
    if item.get('images') or item.get('image_post_info'):
        raise RuntimeError('[NO_MEDIA] 这是图文作品，不适用视频导出')
    return item


class PlatformEngine:
    _sources = {}

    @classmethod
    async def extract(cls, raw, site):
        import threading
        cancelled = threading.Event()
        try:
            return await asyncio.to_thread(cls._extract, raw, site, cancelled)
        except asyncio.CancelledError:
            cancelled.set()
            raise

    @classmethod
    def _extract(cls, raw, site, cancelled=None):
        raw = normalize(raw)
        session = http(site)
        frames = None
        if site == 'Pixiv':
            match = re.search(r'/artworks/(\d+)', raw)
            if not match: raise ValueError('[NO_MEDIA] 需要Pixiv作品链接')
            identifier = match[1]
            session.headers['Referer'] = raw
            detail = page(session, f'https://www.pixiv.net/ajax/illust/{identifier}').json()
            if detail.get('error'): raise RuntimeError('[AUTH_REQUIRED] Pixiv作品不可访问，请检查会话')
            data = detail['body']
            if data.get('illustType') != 2: raise RuntimeError('[NO_MEDIA] 当前支持Pixiv Ugoira动图，图片/漫画需单独导出')
            meta = page(session, f'https://www.pixiv.net/ajax/illust/{identifier}/ugoira_meta').json()
            if meta.get('error'): raise RuntimeError('[AUTH_REQUIRED] 无法取得Ugoira帧资源')
            frames = meta['body']['frames']
            urls = [meta['body'].get('originalSrc') or meta['body']['src']]
            formats = [FormatOption(formatId=f'ugoira_{ext}', resolutionLabel=f'Ugoira {ext.upper()}', ext=ext) for ext in ('mp4', 'gif', 'zip')]
            title, author = data.get('title') or identifier, data.get('userName') or ''
            thumbnail = (data.get('urls') or {}).get('small') or ''
        elif site == '抖音':
            from engine.media_resolver import resolve_site_video
            from engine.automatic_douyin import resolve
            with sessions.cookie_file(site) as cookie:
                result = resolve(raw, lambda: resolve_site_video(raw, site, cookie, config.proxy_url), cancelled)
            identifier, title = result['id'], result['title']
            author, thumbnail = result.get('uploader') or '', result.get('thumbnail') or ''
            formats, urls = [], []
            for item in result['formats']:
                source = item['url']; w, h = item.get('width') or 0, item.get('height') or 0
                identity = hashlib.sha256(source.encode()).hexdigest()[:20]
                urls.append(source)
                formats.append(FormatOption(formatId='dy_' + identity, resolutionLabel=label(w, h) if w or h else '源视频（尺寸未知）', width=w, height=h,
                    fps=int(item.get('fps') or 0), vcodec=item.get('vcodec') or '', dynamicRange=item.get('dynamicRange') or item.get('dynamic_range') or '',
                    videoBitrateKbps=item.get('bitrateKbps') or 0, dimensionsVerified=bool(item.get('dimensions_verified'))))
        elif site == '快手':
            from engine.kuaishou import parse_page
            from engine.media_resolver import UA
            session.headers['User-Agent'] = UA
            response = page(session, raw)
            info = parse_page(response.text, response.url)
            identifier, title, thumbnail = info['id'], info['title'], info['thumbnail']
            author = ''
            formats, urls = [], []
            for item in info['formats']:
                source, w, h = item['url'], item['width'], item['height']
                identity = hashlib.sha256(f"{urlsplit(source).netloc}{urlsplit(source).path}|{w}|{h}|{item['vcodec']}".encode()).hexdigest()[:16]
                urls.append(source)
                formats.append(FormatOption(formatId='ks_' + identity, resolutionLabel=label(w, h) if w or h else '平台视频源（尺寸未知）',
                                            width=w, height=h, fps=item['fps'], vcodec=item['vcodec']))
        elif site == '小红书':
            from engine.media_resolver import xhs_target, xhs_video_formats
            canonical, identifier, item = xhs_target(session, raw)
            streams = xhs_video_formats(item, session)
            formats, urls = [], []
            for v in streams:
                source = v['url']
                if source in urls: continue
                w, h = v['width'], v['height']; urls.append(source)
                identity = hashlib.sha256(source.encode()).hexdigest()[:20]
                formats.append(FormatOption(formatId='xhs_' + identity, resolutionLabel=label(w, h), width=w, height=h, vcodec=v['vcodec'], note=v['note']))
            if not urls: raise RuntimeError('[MEDIA_UNCONFIRMED] 当前会话未取得笔记视频')
            title, author = item.get('title') or item.get('desc') or identifier, (item.get('user') or {}).get('nickname') or ''
            thumbnail = ((item.get('imageList') or [{}])[0]).get('urlDefault') or ''
        elif site == '小黑盒':
            response = page(session, raw)
            identifier = (parse_qs(urlsplit(raw).query).get('link_id') or [None])[0] or urlsplit(response.url).path.rsplit('/', 1)[-1]
            urls = []
            for m in re.finditer(r'<(?:video|source)\b[^>]*\bsrc=["\']([^"\']+)["\']', response.text, re.I):
                url = urljoin(response.url, html.unescape(m[1]))
                if re.search(r'\.(mp4|m3u8)(?:\?|$)', url, re.I): urls.append(url)
            if not urls: raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 分享页没有公开媒体，请在内置浏览器打开并播放目标内容')
            title_match = re.search(r'<title[^>]*>(.*?)</title>', response.text, re.S | re.I)
            title, author, thumbnail = html.unescape(title_match[1]).strip() if title_match else identifier, '', ''
            formats = [FormatOption(formatId=str(i), resolutionLabel=f'媒体 {i + 1}') for i in range(len(urls))]
        else: raise ValueError('平台不支持')
        if not frames:
            pairs = sorted(zip(formats, urls), key=lambda pair: (pair[0].width * pair[0].height, pair[0].fps), reverse=True)
            formats, urls = [f for f, _ in pairs], [u for _, u in pairs]
        generation = sessions.status(site)['generation']
        cls._sources[raw] = {'urls': urls, 'formats': formats, 'site': site, 'frames': frames,
                             'id': identifier, 'generation': generation, 'created': time.monotonic()}
        return VideoMetadata(url=raw, title=title, author=author, thumbnailUrl=thumbnail, siteName=site,
                             availableVideoFormats=formats, mediaId=identifier,
                             qualityWarning=result.get('quality_warning', '') if site == '抖音' else '',
                             accessEvidence=result.get('access_evidence', []) if site == '抖音' else [],
                             qualityEvidence=('本次最高规格：' + formats[0].resolution_label + (' · 尺寸已实测' if formats[0].dimensions_verified else ' · 平台标注')) if site == '抖音' and formats else '',
                             expectedDurationMs=sum(f['delay'] for f in frames) if frames else info['duration_ms'] if site == '快手' else 0)

    @classmethod
    async def download(cls, task, progress, cancel):
        from engine.url_sniffer import UrlSniffer
        from engine.ytdlp_engine import YtDlpEngine, clean_media_title
        site = UrlSniffer.identify_site(task.url)
        if YtDlpEngine.has_ready(task): return await YtDlpEngine.download(task, progress, cancel)
        raw = normalize(task.url)
        snapshot = cls._sources.get(raw)
        if not snapshot or time.monotonic() - snapshot['created'] > 600 or snapshot['generation'] != sessions.status(site)['generation']:
            await cls.extract(raw, site); snapshot = cls._sources[raw]
        work = YtDlpEngine.workspace(task)
        work.mkdir(parents=True, exist_ok=True)
        session = http(site)
        session.headers['Referer'] = raw
        fid = task.selected_video_format_id or task.selected_resolution
        chosen = next((f for f in snapshot['formats'] if f.format_id == fid), None)
        if chosen is None and fid not in ('', 'best'): raise RuntimeError('[QUALITY_UNAVAILABLE] 指定源已变化，请重新选择')
        chosen = chosen or snapshot['formats'][0]
        source_index = snapshot['formats'].index(chosen) if not snapshot['frames'] else 0
        url = snapshot['urls'][source_index]
        if not snapshot['frames']:
            from engine.quality import choose
            candidates = [dict(format_id=f.format_id, url=source, ext='mp4', vcodec=f.vcodec or 'unknown', acodec='unknown',
                               width=f.width, height=f.height, fps=f.fps, http_headers={'Referer': raw},
                               protocol='m3u8_native' if '.m3u8' in source else 'https') for f, source in zip(snapshot['formats'], snapshot['urls'])]
            intent = task.quality_selection.model_dump(by_alias=True) if task.quality_selection else {}
            exact = fid if fid not in ('', 'best') else ''
            selected = choose(candidates, intent, exact)
            info = {'id': snapshot['id'], 'title': task.title, 'webpage_url': task.url, 'extractor': 'NativeSnapshot', 'formats': candidates}
            if '.m3u8' in selected['url']:
                # Enumerate a master manifest instead of handing it to a segment downloader as a media playlist.
                import yt_dlp
                with sessions.cookie_file(site) as cookie:
                    options = YtDlpEngine._build_ydl_opts(config.proxy_url, {'Referer': raw})
                    if cookie: options['cookiefile'] = cookie
                    with yt_dlp.YoutubeDL(options) as engine: info = await asyncio.to_thread(engine.extract_info, selected['url'], download=False)
                info.update(id=snapshot['id'], title=task.title, webpage_url=task.url, _omni_native_source_id=selected['format_id'])
            with YtDlpEngine._lock: YtDlpEngine._cache[YtDlpEngine._key(task.url, site)] = (time.monotonic(), info)
            return await YtDlpEngine.download(task, progress, cancel)
        archive = work / 'source.zip'
        def fetch():
            with session.get(url, stream=True, timeout=30) as response:
                response.raise_for_status(); total = 0
                with archive.open('wb') as output:
                    for chunk in response.iter_content(65536):
                        if cancel.is_set(): raise RuntimeError('[CANCELLED] 用户取消')
                        total += len(chunk)
                        if total > 256 * 1024 * 1024: raise ValueError('帧包超过限制')
                        output.write(chunk)
        await asyncio.to_thread(fetch)
        frames = snapshot['frames']
        unpack_frames(archive, work / 'frames', frames)
        duration = sum(f['delay'] for f in frames) / 1000
        if chosen.ext == 'zip':
            output = work / 'result.zip'
            with zipfile.ZipFile(output, 'w') as z:
                for f in frames: z.write(work / 'frames' / f['file'], f['file'])
                z.writestr('frames.json', json.dumps({'artworkId': snapshot['id'], 'kind': 'UGOIRA', 'totalDurationMs': sum(f['delay'] for f in frames), 'frames': [{'file': f['file'], 'delayMs': f['delay']} for f in frames]}, ensure_ascii=False))
        else:
            concat = work / 'frames.ffconcat'
            concat.write_text(concat_text(frames), encoding='utf-8')
            output = work / f'result.{chosen.ext}'
            args = ['-y', '-safe', '0', '-f', 'concat', '-i', str(concat), '-t', f'{duration + .001:.3f}', '-fps_mode', 'vfr']
            if chosen.ext == 'gif': args += ['-vf', 'split[a][b];[a]palettegen[p];[b][p]paletteuse']
            else: args += ['-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264', '-bf', '0', '-pix_fmt', 'yuv420p', '-movflags', '+faststart']
            if not await FFmpegHelper._run_command(args + [str(output)]): raise RuntimeError('[POSTPROCESS_FAILED] Ugoira编码失败，帧包已保留')
            actual = await asyncio.to_thread(verify, output, None, duration)
            if abs(actual['duration'] - duration) > max(.05, duration * .01): raise RuntimeError('[POSTPROCESS_FAILED] Ugoira成品时长与帧清单不符')
        if cancel.is_set(): raise RuntimeError('[CANCELLED] 用户取消')
        (work / 'ready.json').write_text(json.dumps({'signature': YtDlpEngine.signature(task), 'file': output.name}), encoding='utf-8')
        return await YtDlpEngine.download(task, progress, cancel)


def unpack_frames(archive, directory, frames):
    directory.mkdir(parents=True, exist_ok=True)
    names = [f['file'] for f in frames]
    if not names or len(names) > 2000 or len(names) != len(set(names)): raise ValueError('无效帧列表')
    for f in frames:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', f['file']) or Path(f['file']).name != f['file'] or '\\' in f['file'] or ':' in f['file'] or f['file'] in ('.', '..') or not 0 < f['delay'] <= 60000: raise ValueError('无效帧路径或时长')
    with zipfile.ZipFile(archive) as z:
        if len(z.infolist()) > 2000 or sum(i.file_size for i in z.infolist()) > 512 * 1024 * 1024: raise ValueError('帧包解压超过限制')
        for name in names:
            with z.open(name) as src, (directory / name).open('wb') as dst:
                __import__('shutil').copyfileobj(src, dst)


def concat_text(frames):
    return 'ffconcat version 1.0\n' + ''.join(f"file 'frames/{f['file']}'\noption framerate 1000\nduration {f['delay'] / 1000:.3f}\n" for f in frames) + f"file 'frames/{frames[-1]['file']}'\noption framerate 1000\n"
