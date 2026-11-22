"""Release-APK UI regression; does not rely on debug classes surviving R8.

Only the target app's nodes are inspected. Stops when another app owns the screen.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from device_regression import adb_path, ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', default='x_video,x_gif')
    parser.add_argument('--roles', default='primary,backup')
    parser.add_argument('--rounds', type=int, default=1)
    args = parser.parse_args()
    adb = adb_path(); package = 'com.omni.downloader'
    def shell(*values, timeout=25):
        return subprocess.run([adb,'shell',' '.join(shlex.quote(v) for v in values)],capture_output=True,timeout=timeout)
    def tree():
        shell('rm','-f','/sdcard/omni-ui-regression.xml')
        dump = shell('uiautomator','dump','/sdcard/omni-ui-regression.xml')
        raw = shell('cat','/sdcard/omni-ui-regression.xml').stdout
        if not raw.strip() or dump.returncode != 0: return []
        try: data = ET.fromstring(raw)
        except ET.ParseError: return []
        return [n for n in data.iter('node') if n.get('package') == package]
    def click(node):
        nums=list(map(int,re.findall(r'\d+',node.get('bounds'))))
        shell('input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2))
    cases=json.loads((ROOT/'testdata/platform-links.private.json').read_text('utf-8'))['cases']
    output=ROOT/'.local/test-results/device-release-ui.jsonl';output.parent.mkdir(parents=True,exist_ok=True)
    for round_index in range(args.rounds):
        for case in cases:
            if args.cases!='all' and case['id'] not in args.cases.split(','): continue
            for role in args.roles.split(','):
                started=time.monotonic(); row={'caseId':case['id']+'.'+role,'route':'release_ui','round':round_index+1,'recordedAt':datetime.now(timezone.utc).isoformat()}
                try:
                    shell('am','force-stop',package)
                    if case['expectedKind']=='IMAGE_POST':
                        shell('am','start','-a','android.intent.action.SEND','-t','text/plain','-n',package+'/.ui.MainActivity',
                            '--es','android.intent.extra.TEXT',case[role],'--es','com.omni.downloader.TARGET','images')
                    else:
                        shell('am','start','-a','android.intent.action.SEND','-t','text/plain','-n',package+'/.ui.MainActivity','--es','android.intent.extra.TEXT',case[role])
                    while time.monotonic()-started<100:
                        nodes=tree()
                        if not nodes:
                            if time.monotonic()-started < 15: continue
                            raise RuntimeError('TARGET_UI_UNAVAILABLE')
                        labels=[n.get('text','') for n in nodes]
                        error=next((re.search(r'\[([A-Z_]+)\]',v) for v in labels if re.search(r'\[([A-Z_]+)\]',v)),None)
                        if error: row.update(success=False,errorCode=error[1]); break
                        if '解析未能成功' in labels:
                            row.update(success=False,errorCode='EXTRACTOR_ERROR'); break
                        photo = case['expectedKind'] == 'IMAGE_POST'
                        correct_input = any(n.get('class') == 'android.widget.EditText' and n.get('text') == case[role] for n in nodes)
                        confirmed = (not photo and '下载模式' in labels) or (photo and correct_input and any('张图片 · 已选' in v for v in labels))
                        if confirmed:
                            row.update(success=True,kind=case['expectedKind'])
                            count=next((re.search(r'(\d+) 张图片',v) for v in labels if '张图片 · 已选' in v),None)
                            if count: row['imageCount']=int(count[1])
                            if round_index==0 and role=='primary':
                                screenshot=ROOT/'.local/test-results'/f'android-{case["id"]}.png'
                                screenshot.write_bytes(subprocess.check_output([adb,'exec-out','screencap','-p']))
                            break
                    else: row.update(success=False,errorCode='UI_TIMEOUT')
                except Exception as error: row.update(success=False,errorCode=str(error) if str(error) in ('TARGET_UI_UNAVAILABLE','INPUT_TRANSPORT_ERROR') else type(error).__name__)
                row['elapsedMs']=int((time.monotonic()-started)*1000)
                with output.open('a',encoding='utf-8') as file:file.write(json.dumps(row,ensure_ascii=False)+'\n')
                print(json.dumps(row,ensure_ascii=False),flush=True)
                if row.get('errorCode')=='TARGET_UI_UNAVAILABLE': return
    shell('rm','-f','/sdcard/omni-ui-regression.xml')


if __name__=='__main__': main()
