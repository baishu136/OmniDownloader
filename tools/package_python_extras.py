"""Reproducible pure-Python extras for the Android Python 3.12 runtime.

Run in the locked Windows venv after modifying media_resolver.py.
Native extensions are deliberately excluded; charset-normalizer has a Python fallback.
"""
import hashlib
import importlib
import importlib.metadata
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {'requests': '2.34.2', 'urllib3': '2.8.0', 'certifi': '2026.10.23', 'idna': '3.20', 'charset_normalizer': '3.5.2'}


def build():
    browser = ROOT / 'app/src/main/assets/browser/douyin-images-capture.js'
    browser.parent.mkdir(parents=True, exist_ok=True)
    browser.write_bytes((ROOT / 'windows/web/douyin-images-capture.js').read_bytes())
    files = {'omni_media.py': (ROOT / 'windows/engine/media_resolver.py').read_bytes()}
    # requests' SOCKS adapter is optional; ship the pure-Python dependency so
    # Android image extraction uses the same SOCKS5 route as native/yt-dlp.
    assert importlib.metadata.version('PySocks') == '1.7.1'
    socks = Path(importlib.import_module('socks').__file__)
    files['socks.py'] = socks.read_bytes()
    dist = importlib.metadata.distribution('PySocks')
    for item in dist.files or []:
        if 'license' in str(item).lower():
            source = Path(dist.locate_file(item))
            if source.is_file(): files[f'licenses/PySocks/{source.name}'] = source.read_bytes()
    for name, version in PACKAGES.items():
        assert importlib.metadata.version(name.replace('_', '-')) == version, name
        folder = Path(importlib.import_module(name).__file__).parent
        for source in folder.rglob('*'):
            if source.is_file() and '__pycache__' not in source.parts and source.suffix not in ('.pyd', '.so', '.pyc'):
                files[f'{name}/{source.relative_to(folder).as_posix()}'] = source.read_bytes()
        dist = importlib.metadata.distribution(name.replace('_', '-'))
        for item in dist.files or []:
            if any(word in str(item).lower() for word in ('license', 'copying', 'notice')):
                source = Path(dist.locate_file(item))
                if source.is_file(): files[f'licenses/{name}/{source.name}'] = source.read_bytes()
    output = ROOT / 'app/src/main/assets/engine/python-extras.zip'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 11, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    output.with_suffix('.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '\n', encoding='ascii')
    print(f'{len(files)} files, {output.stat().st_size} bytes')


if __name__ == '__main__': build()
