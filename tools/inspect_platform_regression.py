"""Private, anonymous response capture. Never print response bodies or signed URLs."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows'))
from engine.media_resolver import session, UA

OUTPUT = ROOT / '.local/1.7.6/probes'
MOBILE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
CASES = {
    'douyin_video': 'https://v.douyin.com/KJbVdqyMw9c/',
    'douyin_images': 'https://v.douyin.com/_7ik2bQ5wnA/',
    'ks_video': 'https://www.kuaishou.com/short-video/3xqjmdgqyr7u7xc',
    'ks_images': 'https://v.kuaishou.com/J24whvPT',
    'ks_images_backup': 'https://v.kuaishou.com/njK7Y75J',
    'pinterest': 'https://pin.it/Q0K5LjMyK',
    'instagram': 'https://www.instagram.com/reel/DceA6G7ywLu/',
    'x_video': 'https://x.com/omocoro/status/2117617805009068518',
}


def inspect(entry):
    name, url, mode = entry
    with session() as client:
        if mode == 'mobile': client.headers['User-Agent'] = MOBILE
        try:
            r = client.get(url, timeout=(12, 25))
            (OUTPUT / f'{name}-{mode}.html').write_text(r.text, 'utf-8')
            row = dict(name=name, mode=mode, status=r.status_code, host=urlsplit(r.url).hostname,
                       path=urlsplit(r.url).path, bytes=len(r.content),
                       markers=[m for m in ('_ROUTER_DATA', 'RENDER_DATA', '__APOLLO_STATE__', '__INITIAL_STATE__', 'captcha', 'byted_acrawler') if m in r.text])
            (OUTPUT / f'{name}-{mode}.private.json').write_text(json.dumps({'url': r.url}, ensure_ascii=False), 'utf-8')
        except Exception as e: row = dict(name=name, mode=mode, error=type(e).__name__)
    print(json.dumps(row, ensure_ascii=False), flush=True)
    return row


if __name__ == '__main__':
    OUTPUT.mkdir(parents=True, exist_ok=True)
    selected = sys.argv[1:] or list(CASES)
    jobs = [(name, CASES[name], mode) for name in selected for mode in ('desktop', 'mobile')]
    with ThreadPoolExecutor(max_workers=3) as pool: rows = list(pool.map(inspect, jobs))
    (OUTPUT / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), 'utf-8')
