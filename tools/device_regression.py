"""Repeat user-provided platform samples on a connected phone without exporting cookies."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def adb_path():
    if shutil.which('adb'): return shutil.which('adb')
    settings = (ROOT / 'local.properties').read_text(encoding='utf-8')
    directory = re.search(r'^sdk.dir=(.+)$', settings, re.M)[1].strip().replace('\\:', ':').replace('\\\\', '\\')
    return str(Path(directory) / 'platform-tools/adb.exe')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial')
    parser.add_argument('--cases', default='x_video,x_gif')
    parser.add_argument('--roles', default='primary,backup')
    parser.add_argument('--rounds', type=int, default=1)
    parser.add_argument('--route', default='app')
    parser.add_argument('--output', default='.local/test-results/device-live.jsonl')
    args = parser.parse_args()
    adb = adb_path()
    if not args.serial:
        listing = subprocess.check_output([adb, 'devices'], text=True)
        devices = [line.split()[0] for line in listing.splitlines() if line.endswith('\tdevice')]
        if len(devices) != 1: raise SystemExit('Select exactly one device with --serial')
        args.serial = devices[0]
    cases = json.loads((ROOT / 'testdata/platform-links.private.json').read_text('utf-8'))['cases']
    output = ROOT / args.output; output.parent.mkdir(parents=True, exist_ok=True)
    for round_index in range(args.rounds):
        for case in cases:
            if args.cases != 'all' and case['id'] not in args.cases.split(','): continue
            for role in args.roles.split(','):
                case_id = case['id'] + '.' + role
                command = 'am instrument -w ' + ' '.join('-e ' + shlex.quote(k) + ' ' + shlex.quote(v) for k, v in
                    {'class': 'com.omni.downloader.engine.LivePlatformRegressionTest', 'caseId': case_id, 'url': case[role], 'route': args.route}.items())
                command += ' com.omni.downloader.test/androidx.test.runner.AndroidJUnitRunner'
                try:
                    run = subprocess.run([adb, '-s', args.serial, 'shell', command], capture_output=True, timeout=240)
                    text = run.stdout.decode('utf-8', 'replace')
                except subprocess.TimeoutExpired:
                    text = 'OMNI_LIVE_RESULT=' + json.dumps({'caseId':case_id,'route':args.route,'success':False,'errorCode':'HARNESS_TIMEOUT'})
                found = re.search(r'OMNI_LIVE_RESULT=(\{[^\r\n]+\})', text)
                if not found:
                    row = {'caseId': case_id, 'route': args.route, 'success': False, 'errorCode': 'HARNESS_ERROR', 'detail': re.sub(r'https?://\S+', '<url>', text[-1400:])}
                else: row = json.loads(found[1])
                row.update(round=round_index + 1, recordedAt=datetime.now(timezone.utc).isoformat())
                with output.open('a', encoding='utf-8') as file: file.write(json.dumps(row, ensure_ascii=False) + '\n')
                print(json.dumps(row, ensure_ascii=False), flush=True)


if __name__ == '__main__': main()
