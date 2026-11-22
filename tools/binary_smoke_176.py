"""Verify only the explicitly selected isolated packaged Windows process."""
import argparse
import json
from pathlib import Path
import sys
import time
import zipfile
import requests

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'windows'))
from engine.media_verifier import probe


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',required=True,type=int);args=parser.parse_args()
    base=f'http://127.0.0.1:{args.port}'
    client=requests.Session();client.trust_env=False
    def request(method,path,**kwargs):
        r=client.request(method,base+path,timeout=150,**kwargs);r.raise_for_status();return r
    row={'version':request('GET','/openapi.json').json()['info']['version'],'exeStartup':'PASS_LOCAL_API','visibleGUI':'NOT_VERIFIED_NO_TARGETABLE_WINDOW'}
    html=request('GET','/').text
    row['htmlImageViewer']=all(s in html for s in ('function openImageViewer','image-browser-capture','B站动态'))
    request('POST','/api/settings',json={'downloadDir':str(ROOT/'.local/1.7.6/windows-binary-saved')})
    case=json.loads((ROOT/'testdata/reported-2026-11-04.private.json').read_text('utf-8'))['cases']
    for case_id in ('reported_ks_video','reported_bili_dynamic'):
        case_item=next(c for c in case if c['id']==case_id)
        if case_item['expectedKind']=='IMAGE_POST':
            data=request('POST','/api/images/analyze',json={'url':case_item['primary']}).json()
            task={'url':data['url'],'title':'QA-binary-images','downloadType':'IMAGE_ZIP','imagePlatform':data['platform'],'selectedImageIds':[r['id'] for r in data['resources']]}
        else:
            data=request('POST','/api/analyze',json={'url':case_item['primary']}).json()
            f=max(data['availableVideoFormats'],key=lambda f:f['width']*f['height'])
            task={'url':data['url'],'title':'QA-binary-video','downloadType':'VIDEO_WITH_AUDIO','selectedVideoFormatId':f['formatId'],'mediaId':data['mediaId'],'qualitySelection':{'mode':'BEST_AVAILABLE'}}
        created=request('POST','/api/download',json={'tasks':[task]}).json()['tasks'][0]
        current=created
        deadline=time.monotonic()+180
        while time.monotonic()<deadline and current['status'] not in ('COMPLETED','FAILED','WAITING_FOR_AUTH','CANCELLED'):
            time.sleep(.5)
            current=next(t for t in request('GET','/api/tasks').json() if t['id']==created['id'])
        result={'status':current['status']}
        if current['status']=='COMPLETED':
            file=Path(current['localFilePath']);result['bytes']=file.stat().st_size
            assert file.resolve().is_relative_to((ROOT/'.local/1.7.6/windows-binary-saved').resolve())
            if file.suffix=='.zip':
                with zipfile.ZipFile(file) as z:
                    members=[i for i in z.namelist() if i!='manifest.json']
                    result.update(images=len(members),gifs=sum(z.read(i).startswith((b'GIF87a',b'GIF89a')) for i in members),zipValid=z.testzip() is None)
            else:result['actual']=probe(file)
            file.unlink()
        else:result['errorCode']=current.get('errorCode')
        row[case_id]=result
        print(case_id,json.dumps(result,ensure_ascii=False),flush=True)
    (ROOT/'.local/1.7.6/windows-binary-smoke.json').write_text(json.dumps(row,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
