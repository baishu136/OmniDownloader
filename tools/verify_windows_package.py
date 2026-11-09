"""Exercise running packaged EXEs with isolated states and synthetic local media."""
import os
import functools
from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
import requests
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
VERSION=os.environ.get('OMNI_VERIFY_VERSION','1.7.19')
QA=ROOT/f'scratch/package-media-{VERSION}';QA.mkdir(parents=True,exist_ok=True)
exe=ROOT/f'windows/dist/{VERSION}/OmniDownloader_Web/_internal/bin/ffmpeg.exe'
subprocess.run([str(exe),'-y','-f','lavfi','-i','testsrc2=size=320x180:rate=15','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','1','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac',str(QA/'fixture.mp4')],check=True,capture_output=True)
class Handler(SimpleHTTPRequestHandler):
    def log_message(self,*args):pass
server=ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(QA)))
worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
report=[]
try:
    url=f'http://127.0.0.1:{server.server_port}/fixture.mp4'
    for port in (58120,58121):
        base=f'http://127.0.0.1:{port}';client=requests.Session();client.trust_env=False;client.headers['Origin']=base
        assert client.get(base+'/openapi.json').json()['info']['version']==VERSION
        response=client.post(base+'/api/settings',json={'downloadDir':str(QA/f'exports-{port}')},timeout=5);response.raise_for_status()
        response=client.post(base+'/api/analyze',json={'url':url},timeout=30);response.raise_for_status();metadata=response.json()
        assert metadata['availableVideoFormats']
        items=[{'url':url,'title':f'packaged-{mode}','downloadType':mode} for mode in ('GIF','VIDEO_WITH_AUDIO')]
        response=client.post(base+'/api/download',json={'tasks':items},timeout=5);response.raise_for_status();created=response.json()['tasks'];ids={t['id'] for t in created}
        deadline=time.monotonic()+60
        while True:
            rows=[t for t in client.get(base+'/api/tasks',timeout=5).json() if t['id'] in ids]
            if all(t['status'] in ('COMPLETED','FAILED','CANCELLED','WAITING_FOR_AUTH') for t in rows):break
            if time.monotonic()>deadline:raise TimeoutError('packaged media transfer')
            time.sleep(.25)
        assert all(t['status']=='COMPLETED' for t in rows),[(t['downloadType'],t['status'],t.get('errorMessage')) for t in rows]
        for task in rows:
            path=Path(task['localFilePath']);assert path.is_file()
            if task['downloadType']=='GIF':
                with Image.open(path) as animation:assert animation.format=='GIF' and animation.n_frames>1
            else:
                subprocess.run([str(exe),'-v','error','-i',str(path),'-f','null','-'],check=True,capture_output=True)
        report.append({'port':port,'version':VERSION,'analysis':True,'gifFramesDecoded':True,'videoDecoded':True})
    destination=ROOT/f'release/{VERSION}/qa/windows-package-media.json';destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
finally:server.shutdown();server.server_close();worker.join(timeout=3)
