"""One-off bounded anonymous regression diagnostics; raw media stays private."""
import json
from pathlib import Path
import sys
from concurrent.futures import ThreadPoolExecutor
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows'))
from engine.media_resolver import session, embedded_json, find_object, UA
OUT = ROOT / '.local/1.7.6/probes'
MOBILE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'


def job(name, url, ua=MOBILE):
    try:
        with session() as c:
            c.headers.update({'User-Agent': ua, 'Referer': 'https://www.douyin.com/'})
            r = c.get(url, timeout=(10, 20))
            (OUT / (name + '.html')).write_text(r.text, 'utf-8')
            print(json.dumps(dict(name=name, status=r.status_code, bytes=len(r.content),
                                  target='aweme_id' in r.text, videoInfo='videoInfoRes' in r.text), ensure_ascii=False), flush=True)
    except Exception as e: print(name, type(e).__name__, flush=True)


if __name__ == '__main__':
    jobs = [
        ('dy-video-bare', 'https://www.iesdouyin.com/share/video/7563102486479883556/'),
        ('dy-note-bare', 'https://www.iesdouyin.com/share/note/7625568178885657747/'),
        ('dy-note-video-route', 'https://www.iesdouyin.com/share/video/7625568178885657747/'),
        ('dy-video-detail', 'https://www.douyin.com/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id=7563102486479883556', UA),
        ('dy-note-detail', 'https://www.douyin.com/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id=7625568178885657747', UA),
    ]
    with ThreadPoolExecutor(max_workers=2) as pool: list(pool.map(lambda args: job(*args), jobs))
