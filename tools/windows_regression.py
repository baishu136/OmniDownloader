"""Exercise Windows application parsing routes with the private shared fixtures."""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows'))
os.environ.setdefault('OMNI_STATE_DIR', str(ROOT / '.local/windows-regression'))


async def run():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', default='all')
    parser.add_argument('--roles', default='primary,backup')
    parser.add_argument('--rounds', type=int, default=1)
    args = parser.parse_args()
    from omni_server import analyze_url, analyze_image_post, AnalyzeRequest
    from engine.media_errors import sanitize, code
    cases = json.loads((ROOT / 'testdata/platform-links.private.json').read_text('utf-8'))['cases']
    output = ROOT / '.local/test-results/windows-live.jsonl'; output.parent.mkdir(parents=True, exist_ok=True)
    for round_index in range(args.rounds):
        for case in cases:
            if args.cases != 'all' and case['id'] not in args.cases.split(','): continue
            for role in args.roles.split(','):
                from engine.ytdlp_engine import YtDlpEngine
                YtDlpEngine._cache.clear()  # Each iteration must exercise the network, not a cache hit.
                started = time.monotonic()
                row = {'caseId': case['id'] + '.' + role, 'round': round_index + 1, 'recordedAt': datetime.now(timezone.utc).isoformat()}
                try:
                    request = AnalyzeRequest(url=case[role])
                    if case['expectedKind'] == 'IMAGE_POST':
                        post = await analyze_image_post(request)
                        row.update(success=True, mediaId=post['contentId'], imageCount=len(post['resources']), route=post['route'])
                    else:
                        meta = await analyze_url(request)
                        row.update(success=True, mediaId=meta.media_id, videoFormats=len(meta.available_video_formats),
                            children=len(meta.multi_media_list), maxPixels=max((f.width * f.height for f in meta.available_video_formats), default=0))
                except Exception as error:
                    detail = getattr(error, 'detail', str(error))
                    row.update(success=False, errorCode=code(detail), error=sanitize(detail)[-1800:])
                row['elapsedMs'] = int((time.monotonic() - started) * 1000)
                with output.open('a', encoding='utf-8') as file: file.write(json.dumps(row, ensure_ascii=False) + '\n')
                print(json.dumps(row, ensure_ascii=False), flush=True)


if __name__ == '__main__': asyncio.run(run())
