"""Discover the installed engine and check official Windows releases."""
import functools
import re
import time
from urllib.parse import urlsplit
import requests
import yt_dlp

VERSION = '1.7.60'


@functools.lru_cache(maxsize=1)
def extractor_catalog():
    from yt_dlp.extractor import gen_extractor_classes
    return sorted(
        {ie.IE_NAME for ie in gen_extractor_classes() if ie.working() and not ie.IE_NAME.startswith('generic')},
        key=str.casefold,
    )


def select_update(releases):
    current = tuple(map(int, VERSION.split('.')))
    for release in releases:
        if release.get('draft') or release.get('prerelease'):
            continue
        match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)', release.get('tag_name', ''))
        if not match or tuple(map(int, match.groups())) <= current:
            continue
        asset = next((item for item in release.get('assets', [])
                      if item.get('name', '').startswith('OmniDownloader_Web')
                      and item['name'].endswith('.zip')), None)
        url = asset.get('browser_download_url', '') if asset else ''
        if urlsplit(url).hostname == 'github.com' and url.startswith('https://github.com/baishu136/OmniDownloader/releases/download/'):
            return {'version': '.'.join(match.groups()), 'url': url}
    return None


_update_cache = None


def check_update():
    global _update_cache
    if _update_cache and time.monotonic() - _update_cache[0] < 3600:
        return _update_cache[1]
    response = requests.get('https://api.github.com/repos/baishu136/OmniDownloader/releases',
                            headers={'Accept': 'application/vnd.github+json'}, timeout=12)
    response.raise_for_status()
    result = {'currentVersion': VERSION, 'update': select_update(response.json())}
    _update_cache = (time.monotonic(), result)
    return result


def has_video(format):
    return not format.get('has_drm') and urlsplit(format.get('url') or '').scheme in ('http', 'https') and (
        format.get('vcodec') not in ('none', None, '') or format.get('height') or format.get('width')
        or (format.get('vcodec') != 'none' and format.get('ext') in ('mp4', 'webm', 'mkv', 'mov', 'flv', 'ts', 'avi', 'ogv')))
