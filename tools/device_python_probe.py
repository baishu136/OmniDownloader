"""Diagnose the phone's installed Python/network without reading or exporting account data.

This is a runtime probe, not an APK UI/instrumentation acceptance test.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
from device_regression import adb_path, ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', default='x_video,x_gif')
    parser.add_argument('--rounds', type=int, default=1)
    args = parser.parse_args()
    adb = adb_path()
    dump = subprocess.check_output([adb, 'shell', 'dumpsys', 'package', 'com.omni.downloader'], text=True)
    native = re.search(r'legacyNativeLibraryDir=(\S+)', dump)[1] + '/arm64'
    base = '/data/user/0/com.omni.downloader/no_backup/youtubedl-android'
    home = base + '/packages/python/usr'
    environment = {'PYTHONHOME': home, 'PYTHONPATH':base + '/yt-dlp/yt-dlp',
        'LD_LIBRARY_PATH':home + '/lib:' + base + '/packages/ffmpeg/usr/lib',
        'SSL_CERT_FILE':home + '/etc/tls/cert.pem', 'REQUESTS_CA_BUNDLE':home + '/etc/tls/cert.pem', 'PYTHONIOENCODING':'utf-8'}
    cases = json.loads((ROOT / 'testdata/platform-links.private.json').read_text('utf-8'))['cases']
    output = ROOT / '.local/test-results/device-runtime-probe.jsonl'; output.parent.mkdir(parents=True, exist_ok=True)
    for round_index in range(args.rounds):
        for case in cases:
            if case['id'] not in args.cases.split(','): continue
            for role in ('primary','backup'):
                # Return only diagnostics: signed media URLs never leave the app sandbox.
                script = f'''import json,time,omni_media as m
started=time.monotonic()
row={{'caseId':{case['id'] + '.' + role!r},'route':'installed_python_vpn_no_account','round':{round_index + 1}}}
try:
 data=m.resolve_images({case[role]!r},{case['platform']!r}) if {case['expectedKind']!r}=='IMAGE_POST' else m.resolve_x_video({case[role]!r})
 row.update(success=True,mediaId=data.get('id',data.get('contentId','')),videoFormats=len(data.get('formats',[])),imageCount=len(data.get('resources',[])))
except Exception as error: row.update(success=False,errorCode=getattr(error,'code','NETWORK_OR_EXTRACTOR'),error=type(error).__name__)
row['elapsedMs']=int((time.monotonic()-started)*1000)
print(json.dumps(row))'''
                # Android toybox env treats '=' inside /data/app's base64 path as an assignment.
                command = ['run-as','com.omni.downloader','/system/bin/env',*[key+'='+value for key,value in environment.items()],
                    '/system/bin/sh','-c','exec "$@"','omni-python',native+'/libpython.so','-c',script]
                try:
                    run = subprocess.run([adb,'shell',' '.join(shlex.quote(value) for value in command)],capture_output=True,timeout=180)
                    rows = [line for line in run.stdout.decode('utf-8','replace').splitlines() if line.startswith('{')]
                    row = json.loads(rows[-1]) if rows else {'caseId':case['id']+'.'+role,'success':False,'errorCode':'PROBE_UNAVAILABLE'}
                except subprocess.TimeoutExpired: row={'caseId':case['id']+'.'+role,'success':False,'errorCode':'PROBE_TIMEOUT'}
                with output.open('a',encoding='utf-8') as file: file.write(json.dumps(row,ensure_ascii=False)+'\n')
                print(json.dumps(row,ensure_ascii=False),flush=True)


if __name__ == '__main__': main()
