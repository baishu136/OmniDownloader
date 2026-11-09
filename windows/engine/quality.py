"""Deterministic selection. Resolution labels never serve as format identities."""
import re


def label(width, height):
    edge = min(width, height) if width and height else max(width, height)
    tier = {4320: '8K', 3240: '6K', 2880: '5K', 2160: '4K'}.get(edge, f'{edge}p')
    return f'{tier} · {width}×{height}' if width and height else tier


def dimension(f):
    w, h = int(f.get('width') or 0), int(f.get('height') or 0)
    return min(w, h) if w and h else max(w, h)


def score(f):
    w, h = int(f.get('width') or 0), int(f.get('height') or 0)
    return (w * h, dimension(f), float(f.get('fps') or 0), float(f.get('quality') or 0), str(f.get('format_id', '')))


def height_from_legacy(value):
    text = value.lower()
    for k, h in [('8k', 4320), ('6k', 3240), ('5k', 2880), ('4k', 2160), ('2k', 1440)]:
        if k in text:
            return h
    m = re.search(r'(\d{3,5})p', text)
    return int(m[1]) if m else 0


def choose(formats, intent=None, exact_id=''):
    intent = intent or {}
    candidates = [f for f in formats if f.get('vcodec') != 'none' and (dimension(f) > 0 or f.get('vcodec') or (f.get('url') and f.get('ext') in ('mp4', 'webm', 'mkv', 'mov'))) and not f.get('has_drm')]
    codec = intent.get('codecPreference', 'auto')
    dynamic = intent.get('dynamicRangePreference', 'auto')
    if dynamic != 'auto':
        candidates = [f for f in candidates if (str(f.get('dynamic_range') or '').upper() == 'SDR' if dynamic == 'SDR'
                      else bool(f.get('dynamic_range')) and str(f['dynamic_range']).upper() != 'SDR')]
    if codec != 'auto':
        candidates = [f for f in candidates if str(f.get('vcodec', '')).startswith(codec)]
    fps = intent.get('targetFps', 0)
    if fps:
        candidates = [f for f in candidates if (f.get('fps') or 0) >= fps]
    if exact_id:
        match = next((f for f in candidates if str(f.get('format_id')) == str(exact_id)), None)
        if match:
            return match
        if not intent.get('allowLowerQuality', False):
            raise ValueError('[QUALITY_UNAVAILABLE] 指定格式已失效，请重新选择')
    limit = intent.get('maxHeight') or 0
    if limit:
        candidates = [f for f in candidates if 0 < dimension(f) <= limit and
                      (intent.get('mode') != 'TARGET_TIER' or intent.get('allowLowerQuality', False) or dimension(f) == limit)]
    originals = [f for f in candidates if not f.get('is_upscaled')]
    if originals:
        candidates = originals
    if not candidates:
        raise ValueError('[QUALITY_UNAVAILABLE] 没有满足画质条件的可访问视频流')
    return max(candidates, key=score)
