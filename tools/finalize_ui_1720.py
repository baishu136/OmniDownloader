"""Stage the validated UI release without modifying user state or running executables."""
from pathlib import Path
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'release/1.7.20'
for kind in ['Desktop','Web']:
    name='OmniDownloader_'+kind
    folder=ROOT/'windows/dist/1.7.20'/name
    for document in ['README.md','THIRD_PARTY_NOTICES.md','runtime-lock.json']:
        shutil.copy2(ROOT/'windows'/document,folder/document)
    shutil.copytree(ROOT/'windows/licenses',folder/'licenses',dirs_exist_ok=True)
    archive=ROOT/'windows/release'/f'{name}-1.7.20.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as output:
        for path in sorted(folder.rglob('*')):
            if path.is_file(): output.write(path,path.relative_to(folder.parent).as_posix())
    with zipfile.ZipFile(archive) as output: assert output.testzip() is None
    shutil.copy2(archive,OUT/archive.name)
    print('Staged',archive.name,archive.stat().st_size)
