"""Stage the validated Windows folders and deterministic integrity manifests."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'release/1.7.19';OUT.mkdir(parents=True,exist_ok=True)
WINDOWS=ROOT/'windows'
for kind in ['Desktop','Web']:
    name='OmniDownloader_'+kind
    directory=WINDOWS/'dist/1.7.19'/name
    for document in ['runtime-lock.json','THIRD_PARTY_NOTICES.md','README.md']:
        shutil.copy2(WINDOWS/document,directory/document)
    shutil.copytree(WINDOWS/'licenses',directory/'licenses',dirs_exist_ok=True)
    for asset in ['parity.js','parity.css','strings.json','target-capture.js']:
        shutil.copy2(WINDOWS/'web/static'/asset,directory/'_internal/web/static'/asset)
    archive=WINDOWS/'release'/f'{name}-1.7.19.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as output:
        for path in sorted(directory.rglob('*')):
            if path.is_file():output.write(path,path.relative_to(directory.parent).as_posix())
    with zipfile.ZipFile(archive) as output:assert output.testzip() is None
    shutil.copy2(archive,OUT/archive.name)
    print('Staged',archive.name,archive.stat().st_size)
canonical=WINDOWS/'dist/OmniDownloader_Web'
for document in ['runtime-lock.json','THIRD_PARTY_NOTICES.md','README.md']:
    shutil.copy2(WINDOWS/document,canonical/document)
shutil.copytree(WINDOWS/'licenses',canonical/'licenses',dirs_exist_ok=True)
for asset in ['parity.js','parity.css','strings.json','target-capture.js']:
    shutil.copy2(WINDOWS/'web/static'/asset,canonical/'_internal/web/static'/asset)
assert hashlib.sha256((canonical/'OmniDownloader_Web.exe').read_bytes()).digest()==hashlib.sha256((WINDOWS/'dist/1.7.19/OmniDownloader_Web/OmniDownloader_Web.exe').read_bytes()).digest()
print('Formal Web executable and packaged release match')
