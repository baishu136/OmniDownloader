"""Bounded Android save acceptance and isolated Windows parser comparison.

Uses only locally supplied private fixtures. Output never contains resource URLs/cookies.
This is a test harness; production behavior/configuration is unchanged.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from device_regression import adb_path


def cases():
    data = json.loads((ROOT / 'testdata/platform-links.private.json').read_text('utf-8'))['cases']
    extra = ROOT / 'testdata/xhs-reported-2026-11-03.private.json'
    if extra.exists():
        item = json.loads(extra.read_text('utf-8'))['case']
        data.append(dict(item, primary=item['url']))
    reported = ROOT / 'testdata/reported-2026-11-04.private.json'
    if reported.exists(): data.extend(json.loads(reported.read_text('utf-8'))['cases'])
    return data


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def safe_error(text):
    return re.sub(r'https?://[^\s\'\"]+', '<url>', text)[-1600:]


def worker(case_id, role):
    os.environ['OMNI_STATE_DIR'] = str(ROOT / '.local/platform-acceptance/windows-empty-state')
    sys.path.insert(0, str(ROOT / 'windows'))
    import asyncio
    from omni_server import analyze_url, analyze_image_post, AnalyzeRequest
    from engine.media_errors import code, sanitize
    case = next(c for c in cases() if c['id'] == case_id)
    row = dict(caseId=f'{case_id}.{role}', platform=case['platform'], expectedKind=case['expectedKind'],
               route='windows_current_source_anonymous', parseSuccess=False, recordedAt=timestamp())
    import time
    started = time.monotonic()
    async def inspect():
        if case['expectedKind'] == 'IMAGE_POST':
            post = await analyze_image_post(AnalyzeRequest(url=case[role]))
            row.update(parseSuccess=True, mediaId=post['contentId'], imageCount=len(post['resources']))
        else:
            meta = await analyze_url(AnalyzeRequest(url=case[role]))
            row.update(parseSuccess=True, mediaId=meta.media_id, videoFormats=len(meta.available_video_formats),
                       children=len(meta.multi_media_list), isGif=meta.is_gif)
            if case['expectedKind'] == 'COLLECTION' and len(meta.multi_media_list) < 2:
                row.update(collectionExpanded=False, errorCode='COLLECTION_NOT_EXPANDED')
    try:
        asyncio.run(inspect())
    except Exception as error:
        detail = getattr(error, 'detail', str(error))
        row.update(errorCode=code(detail), error=safe_error(sanitize(detail)))
    row['elapsedMs'] = int((time.monotonic() - started) * 1000)
    print('OMNI_WINDOWS=' + json.dumps(row, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=('android', 'windows'), required=True)
    parser.add_argument('--serial', default='emulator-5582')
    parser.add_argument('--output', required=True)
    parser.add_argument('--cases', default='all')
    parser.add_argument('--parse-only', action='store_true')
    parser.add_argument('--best-quality', action='store_true')
    parser.add_argument('--timeout-seconds', type=int, default=210)
    parser.add_argument('--reuse-task-id')
    parser.add_argument('--worker-case')
    parser.add_argument('--worker-role')
    parser.add_argument('--bridge-host-proxy', action='store_true')
    args = parser.parse_args()
    if not 60 <= args.timeout_seconds <= 900: parser.error('timeout must be 60..900 seconds')
    if args.worker_case:
        worker(args.worker_case, args.worker_role)
        return
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    selected = [c for c in cases() if args.cases == 'all' or c['id'] in args.cases.split(',')]
    existing = [json.loads(line) for line in output.read_text('utf-8').splitlines()] if output.exists() else []
    complete = {row['caseId'] for row in existing}
    def emit(row):
        row.setdefault('recordedAt', timestamp())
        with output.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
        print(json.dumps(row, ensure_ascii=False), flush=True)
    def roles(case):
        return [role for role in ('primary', 'backup') if role in case]
    if args.mode == 'windows':
        def run(case, role):
            try:
                command = [sys.executable, str(Path(__file__)), '--mode', 'windows', '--output', str(output),
                           '--worker-case', case['id'], '--worker-role', role]
                proc = subprocess.run(command, capture_output=True, timeout=120)
                match = re.search(r'OMNI_WINDOWS=(\{[^\r\n]+\})', proc.stdout.decode('utf-8', 'replace'))
                if match:
                    return json.loads(match[1])
                return dict(caseId=f'{case["id"]}.{role}', platform=case['platform'], expectedKind=case['expectedKind'], errorCode='HARNESS_ERROR', parseSuccess=False)
            except subprocess.TimeoutExpired:
                return dict(caseId=f'{case["id"]}.{role}', platform=case['platform'], expectedKind=case['expectedKind'], errorCode='TIMEOUT_120S', parseSuccess=False)
        with ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(run, case, role) for case in selected for role in roles(case)
                    if f'{case["id"]}.{role}' not in complete]
            for job in as_completed(jobs):
                emit(job.result())
    else:
        adb = adb_path()
        bridge = ''
        if args.bridge_host_proxy:
            from urllib.request import getproxies
            from urllib.parse import urlsplit
            parsed = urlsplit(getproxies().get('https', ''))
            if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1') or not parsed.port or parsed.username:
                raise SystemExit('A local HTTP system proxy without credentials is required for the emulator bridge')
            bridge = f'http://10.0.2.2:{parsed.port}'
        overseas = {'YouTube', 'TikTok', 'X (Twitter)', 'Instagram', 'Facebook', 'Pinterest', 'Pixiv'}
        for case in selected:
            downloaded = any(row['caseId'].startswith(case['id'] + '.') and row.get('downloadStatus') == 'PASS' for row in existing)
            for role in roles(case):
                case_id = f'{case["id"]}.{role}'
                if case_id in complete:
                    continue
                options = {'class': 'com.omni.downloader.engine.PlatformAcceptanceTest', 'caseId': case_id,
                           'url': case[role], 'expectedKind': case['expectedKind'], 'download': str(not downloaded and not args.parse_only).lower()}
                if args.best_quality: options['bestQuality'] = 'true'
                if args.reuse_task_id: options['reuseTaskId'] = args.reuse_task_id
                if bridge and case['platform'] in overseas:
                    options['proxy'] = bridge
                command = 'am instrument -w ' + ' '.join('-e ' + shlex.quote(k) + ' ' + shlex.quote(v) for k, v in options.items())
                command += ' com.omni.downloader.test/androidx.test.runner.AndroidJUnitRunner'
                try:
                    proc = subprocess.run([adb, '-s', args.serial, 'shell', command], capture_output=True, timeout=args.timeout_seconds)
                    match = re.search(r'OMNI_ACCEPTANCE=(\{[^\r\n]+\})', proc.stdout.decode('utf-8', 'replace'))
                    if not match:
                        row = dict(caseId=case_id, platform=case['platform'], expectedKind=case['expectedKind'],
                                   parseSuccess=False, errorCode='HARNESS_ERROR', downloadStatus='NOT_VERIFIED')
                    else:
                        row = json.loads(match[1])
                        row['error'] = safe_error(row.get('error', ''))
                except subprocess.TimeoutExpired:
                    subprocess.run([adb, '-s', args.serial, 'shell', 'am', 'force-stop', 'com.omni.downloader'], capture_output=True)
                    row = dict(caseId=case_id, platform=case['platform'], expectedKind=case['expectedKind'],
                               parseSuccess=False, errorCode=f'TIMEOUT_{args.timeout_seconds}S', downloadStatus='NOT_VERIFIED')
                downloaded |= row.get('downloadStatus') == 'PASS'
                emit(row)


if __name__ == '__main__':
    main()
