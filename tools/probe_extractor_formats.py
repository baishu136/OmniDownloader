"""Private live extraction diagnostics; do not emit signed URLs or credentials."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows'))
import yt_dlp
from engine.media_resolver import session, content_id
OUT = ROOT / '.local/1.7.6/probes'


def run(name, url):
    class Logger:
        def debug(self, *a): pass
        def warning(self, *a): pass
        def error(self, *a): pass
    try:
        with yt_dlp.YoutubeDL({'quiet': True, 'logger': Logger(), 'socket_timeout': 18, 'extractor_retries': 1}) as y:
            if name == 'pinterest':
                with session() as c: final = c.get(url, timeout=20).url
                data = y.get_info_extractor('Pinterest')._call_api('Pin', content_id(final,'Pinterest'), {'id': content_id(final,'Pinterest'), 'field_set_key': 'detailed'})
                (OUT / 'pinterest-api.private.json').write_text(json.dumps(data), 'utf-8')
            data = y.extract_info(url, download=False)
            (OUT / (name+'-metadata.private.json')).write_text(json.dumps(data), 'utf-8')
            entries = data.get('entries') or [data]
            print(name, json.dumps([{'id': i.get('id'), 'duration': i.get('duration'), 'formats': [{k:f.get(k) for k in ('format_id','width','height','vcodec','acodec','ext')} for f in i.get('formats',[])]} for i in entries]), flush=True)
    except Exception as e: print(name, type(e).__name__, str(e).split(';')[0][:260], flush=True)


if __name__ == '__main__':
    cases = [('instagram','https://www.instagram.com/reel/DceA6G7ywLu/'),('pinterest','https://pin.it/Q0K5LjMyK'),('x','https://x.com/omocoro/status/2117617805009068518')]
    with ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(lambda a:run(*a),cases))
