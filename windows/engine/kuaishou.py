"""Parse the target Kuaishou Apollo photo entity, never recommendation URLs."""
import json
import re
from urllib.parse import urlsplit


def parse_page(text, url):
    from yt_dlp.utils import js_to_json
    match = re.search(r'/(?:short-video|photo)/([^/?]+)', urlsplit(url).path)
    if not match: raise RuntimeError('[MEDIA_ID_MISMATCH] 未确认快手作品ID')
    identifier = match[1]
    marker = re.search(r'(?:window\.)?__APOLLO_STATE__\s*=', text)
    if not marker: raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 快手未返回内容页，请在浏览器登录并播放目标作品')
    end = text.find('</script>', marker.end())
    payload = text[marker.end():end if end >= 0 else None].strip()
    if len(payload) > 4 * 1024 * 1024: raise RuntimeError('[EXTRACTOR_CHANGED] 快手页面状态过大')
    try: state = json.JSONDecoder().raw_decode(js_to_json(payload))[0]
    except (ValueError, TypeError) as error: raise RuntimeError('[EXTRACTOR_CHANGED] 无法读取快手页面状态') from error
    entities = state.get('defaultClient') or state
    photo = entities.get('VisionVideoDetailPhoto:' + identifier) or {}
    if photo.get('id') != identifier: raise RuntimeError('[MEDIA_ID_MISMATCH] 页面没有目标快手作品，拒绝推荐视频')
    def unwrap(value):
        if not isinstance(value, dict): return {}
        if value.get('type') == 'json': return value.get('json') if isinstance(value.get('json'), dict) else {}
        if value.get('type') == 'id': return entities.get(value.get('id')) or {}
        return value
    def valid(source):
        if not isinstance(source, str): return False
        p = urlsplit(source)
        return p.scheme in ('https', 'http') and p.hostname and not p.username
    formats = []
    def append(manifest, codec):
        for adaptation in manifest.get('adaptationSet') or []:
            for item in adaptation.get('representation') or []:
                backup = item.get('backupUrl') or []
                if isinstance(backup, str): backup = [backup]
                for source in [item.get('url')] + backup:
                    if not valid(source) or any(f['url'] == source for f in formats): continue
                    formats.append(dict(url=source, width=int(item.get('width') or 0), height=int(item.get('height') or 0),
                                        fps=int(float(item.get('frameRate') or 0)), vcodec=item.get('codecs') or codec))
    for codec, value in unwrap(photo.get('videoResource')).items():
        if isinstance(value, dict): append(value, codec)
    append(unwrap(photo.get('manifest')), 'h264')
    append(unwrap(photo.get('manifestH265')), 'hevc')
    if not formats:
        formats = [dict(url=photo[key], width=0, height=0, fps=0, vcodec='unknown')
                   for key in ('photoUrl', 'photoH265Url') if valid(photo.get(key))]
    if not formats: raise RuntimeError('[BROWSER_ASSIST_REQUIRED] 目标作品未返回媒体，请在浏览器播放后捕获')
    return dict(id=identifier, title=photo.get('caption') or identifier, thumbnail=photo.get('coverUrl') or '',
                duration_ms=int(photo.get('duration') or 0), formats=formats)
