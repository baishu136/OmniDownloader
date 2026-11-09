"""Restore the validated native runtimes with pinned archive and binary hashes."""
from pathlib import Path
import hashlib
import io
import json
import zipfile
import requests

def prepare():
    base = Path(__file__).resolve().parents[1]
    directory = base / 'bin'
    directory.mkdir(exist_ok=True)
    manifest=json.loads((base/'runtime-lock.json').read_text(encoding='utf-8'))
    for name,spec in manifest.items():
        destination=directory/(name+'.exe')
        if destination.is_file():
            if hashlib.sha256(destination.read_bytes()).hexdigest()!=spec['binarySha256']:
                raise RuntimeError(f'{name} does not match runtime-lock.json; existing binary retained')
            continue
        response=requests.get(spec['url'],timeout=(15,120));response.raise_for_status()
        if hashlib.sha256(response.content).hexdigest()!=spec['archiveSha256']:
            raise RuntimeError(f'{name} archive checksum mismatch')
        with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
            data=archive.read(spec['archiveEntry'])
        if hashlib.sha256(data).hexdigest()!=spec['binarySha256']:
            raise RuntimeError(f'{name} executable checksum mismatch')
        temporary=destination.with_suffix('.download.tmp');temporary.write_bytes(data);temporary.replace(destination)
    return directory


if __name__ == '__main__': prepare()
