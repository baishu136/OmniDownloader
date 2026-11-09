"""Shared Android/Windows resolver. Only resources belonging to one requested post.

Android runs this module in the bundled Python interpreter; Windows imports it.
Cookie files are scoped CookieJars, and are never included in the returned manifest.
"""
import argparse
import hashlib
import html
import http.cookiejar
import json
from pathlib import Path
import re
import sys
import time
from urllib.parse import parse_qs, unquote, urlencode, urljoin, urlsplit, urlunsplit
import zipfile
from concurrent.futures import ThreadPoolExecutor

import requests
from urllib3.exceptions import HTTPError as Urllib3HttpError

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36'
DOMAINS = {'抖音': ('douyin.com', 'iesdouyin.com'), '小红书': ('xiaohongshu.com', 'xhslink.cn', 'xhslink.com'),
           'Pixiv': ('pixiv.net',), 'X (Twitter)': ('x.com', 'twitter.com'), 'Pinterest': ('pinterest.com', 'pin.it'),
           '快手': ('kuaishou.com', 'chenzhongtech.com'), '哔哩哔哩': ('bilibili.com', 'b23.tv')}
MAX_IMAGES = 200


class MediaError(RuntimeError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(f'[{code}] {message}')


def session(cookie_file=None, proxy=''):
    client = requests.Session()
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
    retry = Retry(total=2, connect=2, read=1, status=1, backoff_factor=.4,
                  status_forcelist=(502, 503, 504), allowed_methods=frozenset(('GET', 'HEAD')))
    client.mount('https://', HTTPAdapter(max_retries=retry))
    client.mount('http://', HTTPAdapter(max_retries=retry))
    client.headers['User-Agent'] = UA
    if cookie_file:
        jar = http.cookiejar.MozillaCookieJar(cookie_file)
        jar.load(ignore_discard=True, ignore_expires=True)
        for item in list(jar):
            if item.expires == 0: item.expires = None
            elif item.is_expired(): jar.clear(item.domain, item.path, item.name)
        client.cookies.update(jar)
    if proxy:
        # Resolve destination names through SOCKS5, consistently with the app's
        # saved canonical socks5h URL. Do not patch global socket behavior.
        parts = urlsplit(proxy)
        if parts.scheme.lower() in ('socks', 'socks5'):
            proxy = urlunsplit(('socks5h', parts.netloc, parts.path, parts.query, parts.fragment))
        client.proxies.update(http=proxy, https=proxy)
    return client


def check_response(response):
    status = response.status_code
    if status == 429: raise MediaError('RATE_LIMITED', '平台请求受限，请稍后重试')
    if status in (401, 403): raise MediaError('AUTH_REQUIRED', f'平台要求有效会话或人工验证（HTTP {status}）')
    if status in (404, 410): raise MediaError('CONTENT_UNAVAILABLE', '目标内容不可用')
    response.raise_for_status()


def page(client, url):
    response = client.get(url, timeout=(12, 25), stream=True)
    check_response(response)
    data = bytearray()
    for chunk in response.iter_content(65536):
        data.extend(chunk)
        if len(data) > 12 * 1024 * 1024:
            response.close(); raise MediaError('EXTRACTOR_CHANGED', '页面超过单作品解析限制')
    response._content = bytes(data)
    path = urlsplit(response.url).path.lower()
    if '_$jsvmprt' in response.text and 'byted_acrawler' in response.text:
        raise MediaError('CHALLENGE_REQUIRED', '抖音返回浏览器验证页，请先在平台页面完成验证并更新会话')
    if any(word in path for word in ('/captcha', '/challenge', '/verify')):
        raise MediaError('CHALLENGE_REQUIRED', '请在平台页面完成人工验证')
    if '/login' in path: raise MediaError('AUTH_REQUIRED', '请登录对应平台后重新解析原链接')
    return response


def embedded_json(text, marker):
    match = re.search(marker, text)
    if not match: return None
    start = text.find('{', match.end())
    if start < 0: return None
    try: return json.JSONDecoder().raw_decode(text[start:])[0]
    except ValueError: return None


def embedded_js(text, marker):
    match = re.search(marker, text)
    if not match: return None
    end = text.find('</script>', match.end())
    source = text[match.end():end if end >= 0 else None].strip()
    if len(source) > 4 * 1024 * 1024: raise MediaError('EXTRACTOR_CHANGED', '页面状态超过解析限制')
    from yt_dlp.utils import js_to_json
    try: return json.JSONDecoder().raw_decode(js_to_json(source))[0]
    except (ValueError, TypeError): return None


def find_object(value, identifier, keys):
    if isinstance(value, dict):
        if any(str(value.get(key, '')) == identifier for key in keys): return value
        for child in value.values():
            match = find_object(child, identifier, keys)
            if match is not None: return match
    elif isinstance(value, list):
        for child in value:
            match = find_object(child, identifier, keys)
            if match is not None: return match
    return None


def content_id(url, platform):
    path = urlsplit(url).path
    pattern = {'抖音': r'/(?:video|note)/(\d+)', 'Pixiv': r'/artworks/(\d+)',
               '小红书': r'/(?:explore|discovery/item|item)/([a-zA-Z0-9]+)',
               'X (Twitter)': r'/status/(\d+)', 'Pinterest': r'/pin/(?:[^/]*--)?(\d+)',
               '快手': r'/(?:short-video|photo)/([a-zA-Z0-9]+)', '哔哩哔哩': r'/(?:opus/)?(\d+)'}[platform]
    match = re.search(pattern, path)
    if match: return match[1]
    if platform == '抖音': return (parse_qs(urlsplit(url).query).get('modal_id') or [''])[0]
    return ''


def resource(url, post_id, platform, width=0, height=0, original=False, identifier=None):
    if not isinstance(url, str) or urlsplit(url).scheme not in ('http', 'https'): return None
    path = urlsplit(url).path
    suffix = path.rsplit('/', 1)[-1].split('!')[0].split('~')[0]
    identity = identifier or hashlib.sha256(suffix.encode()).hexdigest()[:20]
    referer = {'Pixiv': 'https://www.pixiv.net/', '小红书': 'https://www.xiaohongshu.com/',
               '抖音': 'https://www.douyin.com/', 'X (Twitter)': 'https://x.com/', 'Pinterest': 'https://www.pinterest.com/',
               '快手': 'https://www.kuaishou.com/', '哔哩哔哩': 'https://www.bilibili.com/'}[platform]
    return {'id': f'{post_id}:{identity}', 'url': url, 'width': int(width or 0), 'height': int(height or 0),
            'original': bool(original), 'watermark': 'SERVER_ORIGINAL' if original else 'UNCONFIRMED', 'headers': {'Referer': referer}}


def choose_image(image):
    for key in ('original', 'urlOriginal', 'originalUrl', 'original_url'):
        value = image.get(key)
        if isinstance(value, dict): value = value.get('url')
        if isinstance(value, str) and value.startswith(('https://', 'http://')): return value, True
    infos = image.get('infoList') or []
    for item in infos:
        if item.get('imageScene') in ('ORIGINAL', 'WB_ORIGINAL') and item.get('url'): return item['url'], True
    for key in ('urlDefault', 'url', 'urlPre'):
        value = image.get(key)
        if isinstance(value, str) and value.startswith(('https://', 'http://')): return value, False
    urls = image.get('url_list') or image.get('urlList') or []
    return (urls[0], False) if urls else ('', False)


def find_douyin_item(value, identifier, depth=0):
    if depth > 25: return None
    if isinstance(value, dict):
        images = value.get('images') or (value.get('image_post_info') or {}).get('images')
        video = value.get('video') or {}
        playable = any(isinstance(address, dict) and address.get('url_list')
                       for key, address in video.items() if key.startswith('play_addr'))
        if str(value.get('aweme_id', value.get('awemeId', ''))) == identifier and (images or playable): return value
        children = value.values()
    elif isinstance(value, list): children = value
    else: return None
    for child in children:
        match = find_douyin_item(child, identifier, depth + 1)
        if match is not None: return match
    return None


def douyin_page_item(response, identifier):
    if content_id(response.url, '抖音') != identifier:
        raise MediaError('MEDIA_ID_MISMATCH', '抖音跳转到了其他作品')
    data = embedded_js(response.text, r'(?:window\.)?_ROUTER_DATA\s*=')
    if data is None:
        match = re.search(r'<script[^>]+id=["\']RENDER_DATA["\'][^>]*>(.*?)</script>', response.text, re.S)
        if match:
            try: data = json.loads(unquote(html.unescape(match[1])))
            except ValueError: pass
    return find_douyin_item(data, identifier)


def douyin_target(client, url):
    client.headers['User-Agent'] = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
    client.headers['Referer'] = 'https://www.douyin.com/'
    requested = content_id(url, '抖音')
    # Desktop canonical URLs do not redirect to the public mobile SSR endpoint.
    if requested and (urlsplit(url).hostname or '').endswith('douyin.com'):
        kind = 'note' if '/note/' in url else 'video'
        url = f'https://www.iesdouyin.com/share/{kind}/{requested}/'
    response = page(client, url)
    identifier = content_id(response.url, '抖音')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '分享页未返回目标抖音作品ID')
    if requested and requested != identifier: raise MediaError('MEDIA_ID_MISMATCH', '抖音跳转到了其他作品')
    item = douyin_page_item(response, identifier)
    if item is None:
        # Photo shares may use /video/ in public SSR even when their desktop
        # target is /note/. Inspect one alternate first-party route, same ID.
        alternate = 'video' if '/note/' in response.url else 'note'
        candidate = page(client, f'https://www.iesdouyin.com/share/{alternate}/{identifier}/')
        item = douyin_page_item(candidate, identifier)
        if item is not None: response = candidate
    # A target-specific web detail request can expose additional bitrate renditions.
    # The old feed endpoint returns random recommended posts and is never used.
    try:
        if item and (item.get('images') or (item.get('image_post_info') or {}).get('images')):
            return response.url, identifier, item
        detail = client.get('https://www.douyin.com/aweme/v1/web/aweme/detail/',
                            params={'aid': '6383', 'device_platform': 'webapp', 'aweme_id': identifier},
                            headers={'User-Agent': UA}, timeout=(10, 15))
        if detail.status_code == 200:
            target = find_douyin_item(detail.json().get('aweme_detail'), identifier)
            if target:
                if item and item.get('video'):
                    target.setdefault('video', {}).setdefault('bit_rate', []).extend((item.get('video') or {}).get('bit_rate') or [])
                item = target
    except (requests.RequestException, ValueError): pass
    if item is None: raise MediaError('BROWSER_ASSIST_REQUIRED', '抖音分享页仅返回作品ID，未提供目标详情；请在内置浏览器完成验证并打开目标作品后提取')
    return response.url, identifier, item


def resolve_douyin(client, url):
    canonical, identifier, item = douyin_target(client, url)
    return douyin_images_from_item(item, canonical, identifier)


def douyin_images_from_item(item, canonical, identifier):
    if str(item.get('aweme_id', item.get('awemeId', ''))) != identifier:
        raise MediaError('MEDIA_ID_MISMATCH', '浏览器响应不属于目标抖音作品')
    images = item.get('images') or (item.get('image_post_info') or {}).get('images') or []
    if not images: raise MediaError('NO_MEDIA', '该作品不是可导出的图文内容，请使用视频页')
    resources = [resource(choose_image(image)[0], identifier, '抖音', image.get('width'), image.get('height'),
                          choose_image(image)[1], image.get('uri')) for image in images]
    return canonical, identifier, item.get('desc') or identifier, (item.get('author') or {}).get('nickname') or '', resources


def xhs_target(client, url):
    # Share tokens are served by the mobile content page; desktop navigation may
    # redirect even a public share to /login. This does not bypass login challenges.
    client.headers['User-Agent'] = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
    client.headers['Referer'] = 'https://www.xiaohongshu.com/'
    parts = urlsplit(url)
    if parts.path == '/login':
        target = (parse_qs(parts.query).get('redirectPath') or [''])[0]
        if target.startswith('/') and not target.startswith('//'): target = urljoin('https://www.xiaohongshu.com/', target)
        if not (urlsplit(target).hostname or '').endswith('.xiaohongshu.com'): raise MediaError('AUTH_REQUIRED', '登录包装缺少合法笔记地址')
        url = target
    requested_id = content_id(url, '小红书')
    response = page(client, url)
    identifier = content_id(response.url, '小红书')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '分享页未返回目标笔记ID')
    if requested_id and requested_id != identifier: raise MediaError('MEDIA_ID_MISMATCH', '分享页跳转到了其他笔记')
    # Convert JavaScript literals to JSON without evaluation or greedy HTML regex.
    # raw_decode reads one object, leaving following script assignments untouched.
    match = re.search(r'window\.__INITIAL_STATE__\s*=', response.text)
    state = None
    if match:
        from yt_dlp.utils import js_to_json
        end = response.text.find('</script>', match.end())
        payload = response.text[match.end():end if end >= 0 else None].strip()
        if len(payload) <= 4 * 1024 * 1024:
            try: state = json.JSONDecoder().raw_decode(js_to_json(payload))[0]
            except (ValueError, TypeError): pass
    if not isinstance(state, dict): raise MediaError('EXTRACTOR_CHANGED', '未能读取小红书笔记状态')
    detail = ((state or {}).get('note') or {}).get('noteDetailMap', {}).get(identifier) or {}
    legacy = detail.get('note') or detail
    mobile = ((state.get('noteData') or {}).get('data') or {}).get('noteData')
    note = None
    for candidate in (legacy, mobile):
        if not isinstance(candidate, dict) or not candidate: continue
        actual_id = candidate.get('noteId') or candidate.get('note_id')
        if actual_id == identifier or (candidate is legacy and not actual_id):
            note = candidate; break
    if not note: raise MediaError('MEDIA_ID_MISMATCH', '页面未包含目标笔记，拒绝使用推荐内容')
    return response.url, identifier, note


def resolve_xhs(client, url):
    canonical, identifier, note = xhs_target(client, url)
    if note.get('type') == 'video': raise MediaError('NO_MEDIA', '这是视频笔记，图文页不把视频封面当图文')
    resources = []
    for image in note.get('imageList') or []:
        source, original = choose_image(image)
        resources.append(resource(source, identifier, '小红书', image.get('width'), image.get('height'), original, image.get('fileId')))
    # H5 image URLs can be smaller than the upload dimensions in page metadata.
    pending = [r for r in resources if r and not r['original']]
    def confirm(image):
        dimensions = probe_image_dimensions(client, image['url'], image['headers'])
        image['width'], image['height'] = dimensions or (0, 0)
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(confirm, pending[:30]))
    for image in pending[30:]: image['width'] = image['height'] = 0
    return canonical, identifier, note.get('title') or note.get('desc') or identifier, (note.get('user') or {}).get('nickname') or '', resources


def image_dimensions(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data) >= 24:
        return int.from_bytes(data[16:20], 'big'), int.from_bytes(data[20:24], 'big')
    if data[:6] in (b'GIF87a', b'GIF89a') and len(data) >= 10:
        return int.from_bytes(data[6:8], 'little'), int.from_bytes(data[8:10], 'little')
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        if data[12:16] == b'VP8X' and len(data) >= 30:
            return 1 + int.from_bytes(data[24:27], 'little'), 1 + int.from_bytes(data[27:30], 'little')
        if data[12:16] == b'VP8 ' and len(data) >= 30 and data[23:26] == b'\x9d\x01\x2a':
            return int.from_bytes(data[26:28], 'little') & 0x3fff, int.from_bytes(data[28:30], 'little') & 0x3fff
        if data[12:16] == b'VP8L' and len(data) >= 25 and data[20] == 0x2f:
            bits = int.from_bytes(data[21:25], 'little')
            return (bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1
    if data[:2] == b'\xff\xd8':
        at = 2
        while at + 4 <= len(data):
            if data[at] != 0xff: return None
            while at < len(data) and data[at] == 0xff: at += 1
            if at >= len(data): return None
            marker = data[at]; at += 1
            if marker in (0xd8, 0xd9) or 0xd0 <= marker <= 0xd7: continue
            if at + 2 > len(data): return None
            size = int.from_bytes(data[at:at+2], 'big')
            if size < 2 or at + size > len(data): return None
            if marker in (0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf) and size >= 7:
                return int.from_bytes(data[at+5:at+7], 'big'), int.from_bytes(data[at+3:at+5], 'big')
            at += size
    return None


def probe_image_dimensions(client, url, headers):
    # Isolated per-worker sessions avoid sharing mutable cookie state.
    with requests.Session() as probe:
        probe.trust_env = False
        probe.headers.update(client.headers); probe.proxies.update(client.proxies)
        probe.cookies.update(client.cookies)
        try:
            with probe.get(url, headers={**headers, 'Range': 'bytes=0-65535', 'Accept-Encoding': 'identity'},
                           timeout=(4, 5), stream=True) as response:
                if response.status_code not in (200, 206): return None
                dimensions = image_dimensions(response.raw.read(65536, decode_content=True))
                return dimensions if dimensions and all(0 < n <= 100000 for n in dimensions) else None
        except (requests.RequestException, Urllib3HttpError, OSError, ValueError): return None


def mp4_dimensions(data):
    def children(start, end):
        offset = start
        while offset + 8 <= end:
            size = int.from_bytes(data[offset:offset + 4], 'big')
            if size < 8 or size > end - offset: return None
            limit = offset + size; kind = data[offset + 4:offset + 8]
            if kind in (b'moov', b'trak'):
                value = children(offset + 8, limit)
                if value: return value
            elif kind == b'tkhd' and size >= 92:
                w = int.from_bytes(data[limit - 8:limit - 4], 'big') >> 16
                h = int.from_bytes(data[limit - 4:limit], 'big') >> 16
                if 0 < w <= 32768 and 0 < h <= 32768: return w, h
            offset = limit
    position = 4
    while True:
        position = data.find(b'moov', position)
        if position < 0: return None
        start = position - 4; size = int.from_bytes(data[start:position], 'big')
        if size >= 8 and start + size <= len(data):
            value = children(start, start + size)
            if value: return value
        position += 4


def sparse_mp4_dimensions(read_at):
    visited = 0
    def walk(offset, limit=None, depth=0):
        nonlocal visited
        if depth > 3: return None
        while visited < 128 and (limit is None or offset <= limit - 8):
            visited += 1
            header = read_at(offset, 16)
            if header is None or len(header) != 16: return None
            size, kind = int.from_bytes(header[:4], 'big'), header[4:8]
            header_size = 16 if size == 1 else 8
            if size == 1: size = int.from_bytes(header[8:16], 'big')
            if size == 0: size = limit - offset if limit is not None else 0
            end = offset + size
            if size < header_size or end > 2**63 - 1 or (limit is not None and end > limit): return None
            if kind in (b'moov', b'trak'):
                found = walk(offset + header_size, end, depth + 1)
                if found: return found
            elif kind == b'tkhd':
                version = read_at(offset + header_size, 1)
                minimum = {b'\0': 84, b'\1': 96}.get(version)
                if minimum is not None and size - header_size >= minimum:
                    fields = read_at(end - 8, 8)
                    if fields is None: return None
                    width = int.from_bytes(fields[:4], 'big') >> 16
                    height = int.from_bytes(fields[4:], 'big') >> 16
                    if 0 < width <= 32768 and 0 < height <= 32768: return width, height
            offset = end
        return None
    return walk(0)


def probe_original_mp4(client, url):
    # A long original may have several MiB of moov metadata after a >4GiB
    # mdat. Follow 64-bit box offsets instead of requiring the complete moov.
    deadline, blocks, total, requests_made = time.monotonic() + 12, {}, None, 0
    with requests.Session() as probe:
        probe.headers.update({k: v for k, v in client.headers.items() if k.lower() not in ('cookie', 'authorization')})
        probe.proxies.update(client.proxies)
        probe.verify, probe.trust_env = client.verify, client.trust_env
        def block(start):
            nonlocal total, requests_made
            if len(blocks) >= 8 or start < 0 or (total is not None and start >= total): return None
            for _ in range(2):
                remaining = deadline - time.monotonic()
                if remaining <= 0 or requests_made >= 12: return None
                requests_made += 1
                try:
                    with probe.get(url, headers={'Range': f'bytes={start}-{start + 65535}', 'Accept-Encoding': 'identity'},
                                   stream=True, timeout=(min(4, remaining), min(4, remaining))) as response:
                        if response.status_code in (408, 500, 502, 503, 504): continue
                        if response.status_code == 206:
                            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+|\*)', response.headers.get('Content-Range', ''))
                            if not match: return None
                            a, b = int(match[1]), int(match[2])
                            length = int(match[3]) if match[3] != '*' else None
                            if a != start or b < a or b - a >= 65536 or (length is not None and (b >= length or total not in (None, length))): return None
                            if length is not None: total = length
                        elif response.status_code == 200 and start == 0:
                            length = response.headers.get('Content-Length', '')
                            if length.isdigit(): total = int(length)
                        else: return None
                        data = response.raw.read(65536, decode_content=False)
                        if not data: return None
                        blocks[start] = data
                        return data
                except (requests.RequestException, Urllib3HttpError, OSError): pass
            return None
        def read_at(offset, count):
            if offset < 0 or not 1 <= count <= 16: return None
            output = bytearray()
            while len(output) < count:
                at = offset + len(output)
                cached = next(((a, data) for a, data in blocks.items() if a <= at < a + len(data)), None)
                start, data = cached if cached else (at, block(at))
                if not data: return None
                size = min(count - len(output), len(data) - (at - start))
                if size <= 0: return None
                output.extend(data[at - start:at - start + size])
            return bytes(output)
        return sparse_mp4_dimensions(read_at)


def xhs_video_formats(note, client=None):
    if note.get('type') == 'normal': raise MediaError('NO_MEDIA', '这是图文笔记，请使用图文下载页')
    video = note.get('video') or {}; streams = (video.get('media') or {}).get('stream') or {}
    formats = []
    for codec, entries in streams.items():
        for entry in entries if isinstance(entries, list) else [entries] if isinstance(entries, dict) else []:
            for source in [entry.get('masterUrl')] + (entry.get('backupUrls') or []):
                if not isinstance(source, str) or urlsplit(source).scheme not in ('http', 'https'): continue
                formats.append(dict(format_id=source, url=source, width=int(entry.get('width') or 0), height=int(entry.get('height') or 0),
                                    vcodec=codec, acodec='unknown', fps=int(entry.get('fps') or 0), note=entry.get('qualityType') or codec))
    origin = (video.get('consumer') or {}).get('originVideoKey') or ''
    if client is not None and re.fullmatch(r'[a-zA-Z0-9_/-]+(?:\.[a-zA-Z0-9]+)?', origin) and '..' not in origin:
        source = 'https://sns-video-bd.xhscdn.com/' + origin
        dimensions = probe_original_mp4(client, source)
        if dimensions:
            formats.append(dict(format_id=source, url=source, width=dimensions[0], height=dimensions[1], fps=0,
                                vcodec='unknown', acodec='unknown', note='平台原始视频源 · 已探测尺寸'))
        else:
            formats.append(dict(format_id=source, url=source, width=0, height=0, fps=0, vcodec='unknown',
                                acodec='unknown', format_note='原始源（尺寸待确认）'))
    return formats


def resolve_pixiv(client, url):
    identifier = content_id(url, 'Pixiv')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '需要单个Pixiv作品链接')
    client.headers['Referer'] = f'https://www.pixiv.net/artworks/{identifier}'
    detail = page(client, f'https://www.pixiv.net/ajax/illust/{identifier}').json()
    if detail.get('error'): raise MediaError('CONTENT_UNAVAILABLE', 'Pixiv作品当前不可访问，请验证会话和作品权限')
    data = detail.get('body') or {}
    if str(data.get('id')) != identifier: raise MediaError('MEDIA_ID_MISMATCH', 'Pixiv响应作品ID不匹配')
    if data.get('illustType') == 2: raise MediaError('NO_MEDIA', '这是Ugoira动图，请使用视频页')
    reply = page(client, f'https://www.pixiv.net/ajax/illust/{identifier}/pages').json()
    if reply.get('error'): raise MediaError('AUTH_REQUIRED', '当前会话无法取得原图列表')
    pages = reply.get('body') or []
    resources = [resource((image.get('urls') or {}).get('original'), identifier, 'Pixiv', image.get('width'), image.get('height'), True, f'p{index}') for index, image in enumerate(pages)]
    return url, identifier, data.get('title') or identifier, data.get('userName') or '', resources


def kuaishou_cache_photo_id(key):
    # This is the platform's serialized SSR cache key, not executable JavaScript.
    unescaped = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), key)
    decoded = ''.join(chr(ord(c) - 1) for c in unescaped if ord(c) > 0)
    value = re.search(r'(?:^|-)photoId-tusjoh\.([^-]+)', decoded)
    return ''.join(chr(ord(c) - 1) for c in value[1]) if value else ''


def kuaishou_atlas(text, url):
    identifier = content_id(url, '快手')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '快手分享页缺少作品ID')
    data = embedded_js(text, r'(?:window\.)?INIT_STATE\s*=') or {}
    numeric = (parse_qs(urlsplit(url).query).get('shareObjectId') or [''])[0]
    for key, value in data.items():
        if not isinstance(value, dict) or not isinstance(value.get('photo'), dict): continue
        photo, atlas = value['photo'], value.get('atlas') or {}
        eid = kuaishou_cache_photo_id(key)
        if eid != identifier: continue
        if numeric and str(photo.get('photoId')) != numeric:
            raise MediaError('MEDIA_ID_MISMATCH', '快手图文响应与分享作品ID不一致')
        resources = []
        cdns = [i.get('cdn', '') for i in atlas.get('cdnList') or []] + (atlas.get('cdn') or [])
        host = next((h for h in cdns if re.fullmatch(r'[a-zA-Z0-9.-]+', h) and h.endswith(('.yximgs.com', '.kwaicdn.com', '.kwimgs.com'))), '')
        for index, source in enumerate(atlas.get('list') or []):
            if not isinstance(source, str): continue
            if source.startswith('/') and host: source = 'https://' + host + source
            size = (atlas.get('size') or [])[index] if index < len(atlas.get('size') or []) else {}
            resources.append(resource(source, identifier, '快手', size.get('w'), size.get('h'), True, f'p{index}'))
        if not resources: raise MediaError('NO_MEDIA', '目标快手作品没有图集，请使用视频下载')
        return url, identifier, photo.get('caption') or identifier, photo.get('userName') or '', resources
    raise MediaError('BROWSER_ASSIST_REQUIRED', '快手未返回目标图集详情，请在浏览器完成验证后重新解析')


def resolve_kuaishou_images(client, url):
    requested = content_id(url, '快手')
    if requested and not (urlsplit(url).hostname or '').endswith('chenzhongtech.com'):
        url = f'https://www.kuaishou.com/fw/photo/{requested}'
    response = page(client, url)
    host = urlsplit(response.url).hostname or ''
    if not any(host == d or host.endswith('.' + d) for d in DOMAINS['快手']):
        raise MediaError('MEDIA_ID_MISMATCH', '快手分享链接跳转到了非平台页面')
    actual = content_id(response.url, '快手')
    if requested and actual != requested: raise MediaError('MEDIA_ID_MISMATCH', '快手分享页跳转到了其他作品')
    return kuaishou_atlas(response.text, response.url)


def bilibili_dynamic_images(data, identifier, url):
    if data.get('code') != 0:
        code = 'AUTH_REQUIRED' if data.get('code') in (-101, -403) else 'CONTENT_UNAVAILABLE'
        raise MediaError(code, 'B站动态暂不可访问，请确认链接及会话权限')
    item = (data.get('data') or {}).get('item') or {}
    if str(item.get('id_str')) != identifier: raise MediaError('MEDIA_ID_MISMATCH', 'B站动态ID不匹配')
    modules = item.get('modules') or {}
    dynamic = modules.get('module_dynamic') or {}
    major = dynamic.get('major') or {}
    # Never pull forwarded originals, recommendations, avatars or video covers.
    if item.get('type') == 'DYNAMIC_TYPE_FORWARD': raise MediaError('NO_MEDIA', '转发动态请使用原动态链接')
    pictures = (major.get('draw') or {}).get('items') or (major.get('opus') or {}).get('pics') or []
    resources = [resource(p.get('src') or p.get('url'), identifier, '哔哩哔哩', p.get('width'), p.get('height'), True, f'p{i}') for i, p in enumerate(pictures)]
    title = ((dynamic.get('desc') or {}).get('text') or ((major.get('opus') or {}).get('summary') or {}).get('text') or f'B站动态 {identifier}')
    return url, identifier, title, (modules.get('module_author') or {}).get('name') or '', resources


def resolve_bilibili_images(client, url):
    response = page(client, url) if (urlsplit(url).hostname or '') == 'b23.tv' else None
    canonical = response.url if response is not None else url
    identifier = content_id(canonical, '哔哩哔哩')
    if not identifier: raise MediaError('NO_MEDIA', '需要B站单条动态或opus图文链接')
    client.headers['Referer'] = 'https://www.bilibili.com/'
    data = page(client, f'https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id={identifier}').json()
    values = bilibili_dynamic_images(data, identifier, canonical)
    if not any(values[-1]):
        response = page(client, f'https://www.bilibili.com/opus/{identifier}')
        state = embedded_js(response.text, r'(?:window\.)?__INITIAL_STATE__\s*=')
        if not isinstance(state, dict): raise MediaError('EXTRACTOR_CHANGED', 'B站未返回可识别的图文正文')
        return bilibili_opus_images(state, identifier, canonical)
    return values


def bilibili_opus_images(state, identifier, url):
    detail = state.get('detail') or {}
    if str(state.get('id')) != identifier or str(detail.get('id_str')) != identifier:
        raise MediaError('MEDIA_ID_MISMATCH', 'B站opus正文与目标动态ID不一致')
    resources = []; title = (detail.get('basic') or {}).get('title') or identifier; author = ''
    for module in detail.get('modules') or []:
        if module.get('module_blocked') or module.get('module_paywall'):
            raise MediaError('ACCESS_DENIED', '该B站图文要求对应账户权限，未取得完整正文')
        author = (module.get('module_author') or {}).get('name') or author
        for paragraph in (module.get('module_content') or {}).get('paragraphs') or []:
            for image in (paragraph.get('pic') or {}).get('pics') or []:
                resources.append(resource(image.get('url'), identifier, '哔哩哔哩', image.get('width'), image.get('height'), True, f'p{len(resources)}'))
    return url, identifier, title, author, resources


def ydl_options(cookie_file, proxy):
    options = {'quiet': True, 'no_warnings': False, 'socket_timeout': 20, 'extractor_retries': 1}
    if cookie_file: options['cookiefile'] = cookie_file
    if proxy: options['proxy'] = proxy
    return options


def twitter_status(url, cookie_file=None, proxy='', public_only=False):
    import yt_dlp
    identifier = content_id(url, 'X (Twitter)')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '需要单条X帖子链接')
    errors = []
    if not public_only:
        try:
            with yt_dlp.YoutubeDL(ydl_options(cookie_file, proxy)) as engine:
                extractor = engine.get_info_extractor('Twitter')
                extractor.initialize()
                status = extractor._extract_status(identifier)
                if str(status.get('id_str', status.get('id', ''))) == identifier and (status.get('extended_entities') or {}).get('media'):
                    return status, 'graphql', identifier
        except Exception as error:
            message = str(error)
            if any(word in message.lower() for word in ('private', 'protected', 'suspended', 'login required', 'not authorized')): raise
            errors.append(type(error).__name__)
    # Public, official syndication endpoint, isolated from the account cookie jar.
    # Calling the endpoint directly avoids yt-dlp's authenticated GraphQL preflight.
    with yt_dlp.YoutubeDL(ydl_options(None, proxy)) as engine:
        extractor = engine.get_info_extractor('Twitter')
        status = extractor._download_json('https://cdn.syndication.twimg.com/tweet-result', identifier,
            headers={'User-Agent': 'Googlebot'}, query={'id': identifier, 'token': extractor._generate_syndication_token(identifier)})
    if not isinstance(status, dict) or str(status.get('id_str', status.get('id', ''))) != identifier:
        raise MediaError('MEDIA_UNCONFIRMED', '官方公开接口未确认目标媒体；不会改用其他帖子')
    status['_omni_media'] = status.get('mediaDetails') or []
    return status, 'public_syndication', identifier


def resolve_twitter_images(url, cookie_file, proxy):
    status, route, identifier = twitter_status(url, cookie_file, proxy)
    media = status.get('_omni_media') or (status.get('extended_entities') or {}).get('media') or []
    resources = []
    for item in media:
        if item.get('type') != 'photo': continue
        source = item.get('media_url_https') or item.get('media_url')
        if not source: continue
        parts = urlsplit(source)
        query = parse_qs(parts.query); query['name'] = ['orig']
        source = urlunsplit(parts._replace(query=urlencode(query, doseq=True)))
        size = item.get('original_info') or {}
        resources.append(resource(source, identifier, 'X (Twitter)', size.get('width'), size.get('height'), True, str(item.get('id_str') or item.get('media_key') or parts.path)))
    if not resources: raise MediaError('NO_MEDIA', '目标帖子没有自己的图片；视频和引用帖不作为图片替代')
    return url, identifier, status.get('full_text') or status.get('text') or identifier, (status.get('user') or {}).get('name') or '', resources, route


def resolve_pinterest(url, cookie_file, proxy):
    import yt_dlp
    client = session(cookie_file, proxy)
    response = page(client, url)
    identifier = content_id(response.url, 'Pinterest')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '短链接未定位到单个Pin')
    with yt_dlp.YoutubeDL(ydl_options(cookie_file, proxy)) as engine:
        extractor = engine.get_info_extractor('Pinterest')
        data = extractor._call_api('Pin', identifier, {'id': identifier, 'field_set_key': 'detailed'}).get('data') or {}
    if str(data.get('id')) != identifier: raise MediaError('MEDIA_ID_MISMATCH', 'Pin响应ID不匹配')
    if (data.get('videos') or {}).get('video_list'): raise MediaError('NO_MEDIA', '这是视频Pin，请使用视频页')
    image = (data.get('images') or {}).get('orig') or {}
    resources = [resource(image.get('url'), identifier, 'Pinterest', image.get('width'), image.get('height'), True, 'original')]
    return response.url, identifier, data.get('title') or data.get('grid_title') or identifier, (data.get('closeup_attribution') or {}).get('full_name') or '', resources


def resolve_images(url, platform, cookie_file=None, proxy=''):
    if platform not in DOMAINS: raise MediaError('NO_MEDIA', '图文页支持抖音、快手、B站动态、Pixiv、小红书、X、Pinterest')
    client = session(cookie_file, proxy)
    route = 'native'
    if platform == 'Pixiv': values = resolve_pixiv(client, url)
    elif platform == '抖音': values = resolve_douyin(client, url)
    elif platform == '小红书': values = resolve_xhs(client, url)
    elif platform == 'Pinterest': values = resolve_pinterest(url, cookie_file, proxy)
    elif platform == '快手': values = resolve_kuaishou_images(client, url)
    elif platform == '哔哩哔哩': values = resolve_bilibili_images(client, url)
    else:
        values = resolve_twitter_images(url, cookie_file, proxy)
        route = values[-1]; values = values[:-1]
    canonical, identifier, title, author, images = values
    seen = set(); resources = []
    for item in images:
        if item and item['id'] not in seen: seen.add(item['id']); resources.append(item)
    if not resources: raise MediaError('NO_MEDIA', '目标作品没有可确认的图片资源')
    if len(resources) > MAX_IMAGES: raise MediaError('NO_MEDIA', '单作品图片数超过当前处理上限')
    return {'platform': platform, 'url': canonical, 'contentId': identifier, 'contentKind': 'IMAGE_POST',
            'title': title[:240], 'author': author, 'resources': resources, 'route': route, 'resolvedAt': int(time.time() * 1000)}


def douyin_video_formats(item):
    video = item.get('video') or {}
    variants = [(rate.get(key) or {}, rate.get('gear_name') or '', rate.get('bit_rate') or 0)
                for rate in video.get('bit_rate') or []
                for key in ('play_addr', 'play_addr_265', 'play_addr_h264', 'play_addr_h265')]
    variants += [(video.get(key) or {}, key, 0) for key in ('play_addr', 'play_addr_h264', 'play_addr_265', 'play_addr_h265')]
    formats, seen = [], set()
    for address, name, bitrate in variants:
        for url in address.get('url_list') or []:
            if not isinstance(url, str) or urlsplit(url).scheme not in ('https', 'http') or url in seen: continue
            seen.add(url)
            formats.append(dict(format_id=url, url=url, ext='mp4', vcodec='hevc' if '265' in name else 'unknown', acodec='unknown',
                                width=address.get('width') or 0,
                                height=address.get('height') or 0, tbr=bitrate / 1000,
                                format_note=name, http_headers={'Referer': 'https://www.douyin.com/'}))
    return sorted(formats, key=lambda f: (f['width'] * f['height'], f['tbr']), reverse=True)


def resolve_site_video(url, platform, cookie_file=None, proxy=''):
    with session(cookie_file, proxy) as client:
        if platform == '小红书':
            canonical, identifier, note = xhs_target(client, url)
            formats = xhs_video_formats(note, client)
            if not formats: raise MediaError('MEDIA_UNCONFIRMED', '目标笔记未提供视频流')
            video = note.get('video') or {}
            streams = (video.get('media') or {}).get('stream') or {}
            duration = max((entry.get('duration') or 0 for entries in streams.values()
                            for entry in (entries if isinstance(entries, list) else [entries]) if isinstance(entry, dict)), default=0)
            warning = '原始视频源暂未确认尺寸，当前列表不代表该作品的最高画质。' if any(f.get('format_note') == '原始源（尺寸待确认）' for f in formats) else ''
            return dict(id=identifier, title=note.get('title') or note.get('desc') or identifier,
                        uploader=(note.get('user') or {}).get('nickname') or '', webpage_url=canonical,
                        duration=duration / 1000, formats=formats, extractor='OmniXiaohongshu', quality_warning=warning,
                        http_headers={'Referer': 'https://www.xiaohongshu.com/', 'User-Agent': client.headers['User-Agent']})
        if platform == '抖音':
            canonical, identifier, item = douyin_target(client, url)
            if item.get('images') or (item.get('image_post_info') or {}).get('images'):
                raise MediaError('NO_MEDIA', '这是图文作品，请使用图文下载页')
            formats = douyin_video_formats(item)
            if not formats: raise MediaError('MEDIA_UNCONFIRMED', '目标作品未提供视频流，请在内置浏览器验证后提取')
            return dict(id=identifier, title=item.get('desc') or identifier, uploader=(item.get('author') or {}).get('nickname') or '',
                        webpage_url=canonical, duration=(item.get('duration') or (item.get('video') or {}).get('duration') or 0) / 1000,
                        formats=formats, extractor='OmniDouyin')
        if platform == 'Pinterest':
            return pinterest_video(client, url, cookie_file, proxy)
    raise MediaError('NO_MEDIA', '该平台没有专用视频解析器')


def pinterest_video(client, url, cookie_file=None, proxy=''):
    import yt_dlp
    response = page(client, url)
    identifier = content_id(response.url, 'Pinterest')
    if not identifier: raise MediaError('MEDIA_ID_MISMATCH', '短链接未定位到单个Pin')
    with yt_dlp.YoutubeDL(ydl_options(cookie_file, proxy)) as engine:
        ie = engine.get_info_extractor('Pinterest')
        data = ie._call_api('Pin', identifier, {'id': identifier, 'field_set_key': 'detailed'}).get('data') or {}
        return pinterest_video_data(data, identifier, response.url, ie)


def pinterest_video_data(data, identifier, url, extractor):
    if str(data.get('id')) != identifier: raise MediaError('MEDIA_ID_MISMATCH', 'Pin响应ID不匹配')
    groups = [data.get('videos') or {}]
    for page_data in (data.get('story_pin_data') or {}).get('pages') or []:
        groups.append(page_data.get('video') or {})
        groups.extend(block.get('video') or {} for block in page_data.get('blocks') or [])
    formats, seen, hls_sources = [], set(), []
    for group in groups:
        for key, item in (group.get('video_list') or {}).items():
            source = item.get('url') or ''
            if urlsplit(source).scheme not in ('http', 'https') or source in seen: continue
            seen.add(source)
            fid = 'pin_' + hashlib.sha256(source.encode()).hexdigest()[:16]
            if '.m3u8' in urlsplit(source).path:
                hls_sources.append((fid, item))
            else:
                formats.append(dict(format_id=fid, url=source, width=item.get('width'), height=item.get('height'),
                                    ext='mp4', vcodec='unknown', acodec='unknown'))
    # A manifest failure must not discard independently supplied MP4 renditions.
    errors = []
    for fid, item in hls_sources:
        try:
            parsed = extractor._extract_m3u8_formats(item['url'], identifier, 'mp4', m3u8_id=fid, fatal=True)
            formats.extend(parsed)
        except Exception as error: errors.append(error)
    if not formats:
        if errors: raise MediaError('NETWORK_ERROR', '无法读取Pinterest视频CDN清单，请检查当前网络或代理后重试') from errors[0]
        raise MediaError('NO_MEDIA', '目标Pin未提供视频媒体')
    return dict(id=identifier, title=data.get('title') or data.get('grid_title') or identifier,
                webpage_url=url, extractor='OmniPinterest', formats=formats,
                thumbnail=((data.get('images') or {}).get('orig') or {}).get('url'))


def resolve_x_video(url, cookie_file=None, proxy=''):
    import yt_dlp
    status, route, identifier = twitter_status(url, cookie_file, proxy, public_only=True)
    entries = []
    with yt_dlp.YoutubeDL(ydl_options(None, proxy)) as engine:
        extractor = engine.get_info_extractor('Twitter')
        for index, media in enumerate(status.get('_omni_media') or [], 1):
            if media.get('type') not in ('video', 'animated_gif'): continue
            formats = []
            for variant in (media.get('video_info') or {}).get('variants') or []:
                source = variant.get('url')
                if not source: continue
                if '.m3u8' in source:
                    formats.extend(extractor._extract_m3u8_formats(source, identifier, 'mp4', fatal=False))
                elif variant.get('content_type') == 'video/mp4':
                    size = re.search(r'/(\d+)x(\d+)/', source)
                    formats.append({'format_id': 'http-' + str(variant.get('bitrate') or len(formats)), 'url': source,
                        'width': int(size[1]) if size else 0, 'height': int(size[2]) if size else 0,
                        'ext': 'mp4', 'protocol': 'https', 'vcodec': 'unknown', 'acodec': 'none' if media.get('type') == 'animated_gif' else 'unknown',
                        'http_headers': {'Referer': 'https://x.com/'}})
            if not formats: continue
            media_id = str(media.get('id_str') or media.get('media_key') or '')
            if not media_id:
                match = re.search(r'/(?:ext_tw_video|amplify_video|tweet_video)/(\d+)', formats[0]['url'])
                media_id = match[1] if match else identifier + ':' + str(index)
            entries.append({'id': media_id, 'title': (status.get('text') or identifier)[:160], 'uploader': (status.get('user') or {}).get('name') or '',
                'webpage_url': re.sub(r'/video/\d+', '', urlsplit(url)._replace(query='').geturl()) + f'/video/{index}',
                'formats': formats, 'duration': (media.get('video_info') or {}).get('duration_millis', 0) / 1000,
                '_omni_post_id': identifier, '_omni_route': route, '_omni_is_gif': media.get('type') == 'animated_gif', 'extractor': 'TwitterPublic'})
    if not entries: raise MediaError('MEDIA_UNCONFIRMED', '公开接口未返回目标视频；可能需要有效会话或稍后重试')
    selected = re.search(r'/video/(\d+)', urlsplit(url).path)
    if selected:
        exact = next((entry for entry in entries if entry['webpage_url'].endswith('/video/' + selected[1])), None)
        if exact is None: raise MediaError('MEDIA_ID_MISMATCH', '指定的媒体位置不含视频')
        return exact
    return entries[0] if len(entries) == 1 else {'_type': 'playlist', 'id': identifier, 'title': identifier, 'entries': entries}


def image_extension(prefix):
    if prefix.startswith(b'\xff\xd8\xff'): return 'jpg'
    if prefix.startswith(b'\x89PNG\r\n\x1a\n'): return 'png'
    if prefix[:6] in (b'GIF87a', b'GIF89a'): return 'gif'
    if prefix.startswith(b'RIFF') and prefix[8:12] == b'WEBP': return 'webp'
    if prefix[4:8] == b'ftyp' and any(brand in prefix[8:40] for brand in (b'avif', b'avis')): return 'avif'
    raise MediaError('POSTPROCESS_FAILED', '服务器未返回可确认的原始图片；拒绝保存网页或错误页')


def download_images(url, platform, selected_ids, output, archive=False, cookie_file=None, proxy='', cancel=None, progress=None, post=None):
    """Refresh the SAME post, match selected stable IDs and preserve original bytes."""
    if post is not None and (post.get('url') != url or post.get('platform') != platform):
        raise MediaError('MEDIA_ID_MISMATCH', '图片快照与当前作品不匹配')
    post = post or resolve_images(url, platform, cookie_file, proxy)
    by_id = {item['id']: item for item in post['resources']}
    if not selected_ids or len(set(selected_ids)) != len(selected_ids) or any(i not in by_id for i in selected_ids):
        raise MediaError('MEDIA_ID_MISMATCH', '所选图片在原作品中已不可定位，请重新解析')
    if not archive and len(selected_ids) != 1: raise MediaError('NO_MEDIA', '单图片任务必须只选择一张')
    directory = Path(output); directory.mkdir(parents=True, exist_ok=True)
    client = session(cookie_file, proxy)
    files = []; total = 0
    try:
        for index, identifier in enumerate(selected_ids):
            if cancel and cancel.is_set(): raise MediaError('CANCELLED', '用户取消')
            image = by_id[identifier]
            partial = directory / f'image-{index:03}.part'
            size = 0; prefix = bytearray()
            with client.get(image['url'], headers=image['headers'], timeout=(12, 30), stream=True) as response:
                check_response(response)
                with partial.open('wb') as stream:
                    for chunk in response.iter_content(65536):
                        if cancel and cancel.is_set(): raise MediaError('CANCELLED', '用户取消')
                        size += len(chunk); total += len(chunk)
                        if size > 100 * 1024 * 1024 or total > 1024 * 1024 * 1024:
                            raise MediaError('POSTPROCESS_FAILED', '图片超过单张100MB或单任务1GB限制')
                        if len(prefix) < 64: prefix.extend(chunk[:64-len(prefix)])
                        stream.write(chunk)
            extension = image_extension(bytes(prefix))
            if size < 16: raise MediaError('POSTPROCESS_FAILED', '原图文件不完整')
            destination = directory / f'{post["contentId"]}_{index + 1:03}.{extension}'
            partial.replace(destination); files.append(destination)
            if progress: progress((index + 1) / len(selected_ids) * 95)
        if archive:
            target = directory / f'{post["contentId"]}_images.zip'
            temporary = target.with_suffix('.zip.part')
            with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_STORED) as bundle:
                for image in files: bundle.write(image, image.name)
                # Portable manifest contains no signed URLs, credentials or share parameters.
                bundle.writestr('manifest.json', json.dumps({'platform': platform, 'contentId': post['contentId'],
                    'images': [{'id': identifier, 'file': path.name, 'bytes': path.stat().st_size,
                                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'source': by_id[identifier]['watermark']}
                               for identifier, path in zip(selected_ids, files)]}, ensure_ascii=False, indent=2))
            with zipfile.ZipFile(temporary) as bundle:
                if bundle.testzip(): raise MediaError('POSTPROCESS_FAILED', '图片归档校验失败')
            temporary.replace(target)
            for image in files: image.unlink()
        else: target = files[0]
        return {'path': str(target), 'bytes': target.stat().st_size, 'count': len(selected_ids), 'contentId': post['contentId']}
    finally:
        client.close()
        for partial in directory.glob('*.part'): partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--action', choices=('images', 'images-state', 'x-video', 'site-video', 'probe', 'download-images'), required=True)
    parser.add_argument('--url', default='')
    parser.add_argument('--platform', default='')
    parser.add_argument('--cookies')
    parser.add_argument('--proxy', default='')
    parser.add_argument('--ids', default='[]')
    parser.add_argument('--output')
    parser.add_argument('--archive', action='store_true')
    parser.add_argument('--state-file')
    parser.add_argument('--manifest')
    args = parser.parse_args()
    try:
        if args.action == 'probe':
            import socks
            result = {'python': sys.version.split()[0], 'requests': requests.__version__, 'socks': socks.__version__}
        elif args.action == 'images': result = resolve_images(args.url, args.platform, args.cookies, args.proxy)
        elif args.action == 'download-images':
            post = None
            if args.manifest:
                path = Path(args.manifest)
                if path.stat().st_size > 4 * 1024 * 1024: raise MediaError('EXTRACTOR_CHANGED', '图片快照过大')
                post = json.loads(path.read_text('utf-8'))
            result = download_images(args.url, args.platform, json.loads(args.ids), args.output, args.archive, args.cookies, args.proxy, post=post)
        elif args.action == 'images-state':
            if args.platform != '抖音': raise MediaError('NO_MEDIA', '该平台尚不支持浏览器图文提取')
            path = Path(args.state_file)
            if path.stat().st_size > 4 * 1024 * 1024: raise MediaError('EXTRACTOR_CHANGED', '浏览器作品数据过大')
            canonical, identifier, title, author, images = douyin_images_from_item(json.loads(path.read_text('utf-8')), args.url, content_id(args.url, args.platform))
            images = [r for r in images if r]
            if not images or len(images) > MAX_IMAGES: raise MediaError('NO_MEDIA', '目标作品没有可导出的图片')
            result = dict(platform=args.platform, url=canonical, contentId=identifier, title=title[:240], author=author,
                          resources=images, route='first_party_browser', resolvedAt=int(time.time() * 1000))
        elif args.action == 'site-video': result = resolve_site_video(args.url, args.platform, args.cookies, args.proxy)
        else: result = resolve_x_video(args.url, args.cookies, args.proxy)
        print(json.dumps({'ok': True, 'result': result}, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'ok': False, 'code': getattr(error, 'code', 'EXTRACTOR_CHANGED'), 'error': str(error)}, ensure_ascii=False))
        return 2
    return 0


if __name__ == '__main__': raise SystemExit(main())
