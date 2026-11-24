"""Build one portable WinUI client containing its shared local web/core host.

Never stop an existing application or delete another release's directory.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[1]
WINDOWS=ROOT/'windows'
VERSION='1.7.65'

def run(*args):subprocess.run([str(a) for a in args],cwd=ROOT,check=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--skip-engine',action='store_true');args=parser.parse_args()
    if not args.skip_engine:run(sys.executable,WINDOWS/'build_release.py','--mode','web')
    engine=WINDOWS/'dist'/VERSION/'OmniDownloader_Web'
    if not (engine/'OmniDownloader_Web.exe').is_file():raise SystemExit('Build the shared engine first')
    run('dotnet','test',WINDOWS/'winui3/tests/OmniDownloader.WinUI.Tests/OmniDownloader.WinUI.Tests.csproj','-c','Release','-v','minimal')
    output=WINDOWS/'dist'/VERSION/'OmniDownloader'
    run('dotnet','publish',WINDOWS/'winui3/src/OmniDownloader.WinUI/OmniDownloader.WinUI.csproj','-c','Release','-r','win-x64','-p:Platform=x64','--self-contained','true','-p:WindowsAppSDKSelfContained=true','-o',output,'-v','minimal')
    shutil.copytree(engine,output/'OmniDownloader_Web',dirs_exist_ok=True)
    guide=ROOT/'docs/UNIFIED_WINDOWS_1.7.65.md'
    if guide.exists():shutil.copy2(guide,output/'使用说明.md')
    (output/'edition.json').write_text(json.dumps({'version':VERSION,'edition':'Windows unified','entry':'OmniDownloader_WinUI.exe','web':'http://127.0.0.1:58000','core':'OmniDownloader_Web/OmniDownloader_Web.exe'},indent=2)+'\n',encoding='utf-8')
    archive=WINDOWS/'release'/f'OmniDownloader-{VERSION}-Windows-x64.zip';archive.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(output.rglob('*')):
            if path.is_file():z.write(path,path.relative_to(output.parent))
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    (archive.parent/f'SHA256SUMS-{VERSION}.txt').write_text(f'{digest}  {archive.name}\n',encoding='utf-8')
    print('Unified portable package:',archive,'SHA256',digest,flush=True)

if __name__=='__main__':main()
