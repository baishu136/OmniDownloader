"""Real isolated Windows application saves of locally supplied public regression cases."""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]
os.environ['OMNI_STATE_DIR']=str(ROOT/'.local/1.7.6/windows-save-state')
sys.path.insert(0,str(ROOT/'windows'))
sys.path.insert(0,str(ROOT/'tools'))
from platform_acceptance import cases, safe_error
from core.config import config
from core.models import DownloadTask, DownloadType
from omni_server import analyze_url, analyze_image_post, AnalyzeRequest
from engine.media_errors import code, sanitize
from engine.images import ImageEngine
from engine.platforms import PlatformEngine
from engine.ytdlp_engine import YtDlpEngine
from engine.media_verifier import probe


async def run():
    parser=argparse.ArgumentParser();parser.add_argument('--cases',required=True);args=parser.parse_args()
    output=ROOT/'.local/1.7.6/windows-saves.jsonl'
    config.download_dir=str(ROOT/'.local/1.7.6/windows-saved-media')
    selected=[c for c in cases() if c['id'] in args.cases.split(',')]
    for case in selected:
        row=dict(caseId=case['id']+'.primary',platform=case['platform'],parseSuccess=False,downloadStatus='NOT_ATTEMPTED',recordedAt=datetime.now(timezone.utc).isoformat())
        try:
            if case['expectedKind']=='IMAGE_POST':
                post=await analyze_image_post(AnalyzeRequest(url=case['primary']))
                row.update(parseSuccess=True,imageCount=len(post['resources']))
                task=DownloadTask(id=uuid.uuid4().hex,url=post['url'],title='QA-'+case['id'],downloadType=DownloadType.IMAGE_ZIP,
                                  imagePlatform=post['platform'],selectedImageIds=[r['id'] for r in post['resources']])
                path=Path(await ImageEngine.download(task,lambda *a:None,asyncio.Event()))
                with zipfile.ZipFile(path) as bundle:
                    files=[i for i in bundle.namelist() if i!='manifest.json']
                    row.update(archiveCount=len(files),gifCount=sum(bundle.read(i).startswith((b'GIF87a',b'GIF89a')) for i in files),zipValid=bundle.testzip() is None)
            else:
                meta=await analyze_url(AnalyzeRequest(url=case['primary']))
                row.update(parseSuccess=True,videoFormats=len(meta.available_video_formats))
                chosen=max(meta.available_video_formats,key=lambda f:f.width*f.height)
                row.update(selectedSize=[chosen.width,chosen.height])
                if meta.expected_duration_ms>600000:
                    row['downloadStatus']='SKIPPED_DURATION_BUDGET';raise RuntimeError('[QA_BUDGET] Sample exceeds ten minutes')
                task=DownloadTask(id=uuid.uuid4().hex,url=meta.url,title='QA-'+case['id'],downloadType=DownloadType.VIDEO_ONLY if case['platform']=='Pixiv' else DownloadType.VIDEO_WITH_AUDIO,
                                  selectedVideoFormatId=chosen.format_id,selectedResolution=chosen.resolution_label,mediaId=meta.media_id)
                engine=PlatformEngine if case['platform'] in ('快手','抖音','小红书','Pixiv','小黑盒') else YtDlpEngine
                path=Path(await engine.download(task,lambda *a:None,asyncio.Event()))
                row['actual']=probe(path)
            row.update(downloadStatus='PASS',bytes=path.stat().st_size)
            path.unlink()
        except Exception as error:
            detail=getattr(error,'detail',str(error));row.update(errorCode=code(detail),error=safe_error(sanitize(detail)))
            if row['parseSuccess'] and row['downloadStatus']=='NOT_ATTEMPTED':row['downloadStatus']='FAIL'
        with output.open('a',encoding='utf-8') as file:file.write(json.dumps(row,ensure_ascii=False)+'\n')
        print(json.dumps(row,ensure_ascii=False),flush=True)


if __name__=='__main__':asyncio.run(run())
