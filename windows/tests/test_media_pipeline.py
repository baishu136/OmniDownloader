"""Exercise real HTTP transfers and bundled FFmpeg, without platform accounts."""
import asyncio
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import threading
import time
import uuid

import pytest

from core.config import config
from core.models import DownloadTask, DownloadType, QualitySelection
from engine.ffmpeg_helper import get_ffmpeg_path
from engine.media_verifier import probe
from engine.ytdlp_engine import YtDlpEngine


@pytest.fixture
def media_server(tmp_path):
    exe = get_ffmpeg_path()
    subprocess.run([exe, '-y', '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:r=25',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
                    '-t', '1', '-c:v', 'libx264', '-bf', '0', '-pix_fmt', 'yuv420p', '-c:a', 'aac',
                    str(tmp_path / 'fixture.mp4')], check=True, capture_output=True)
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *args): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(tmp_path)))
    worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
    yield f'http://127.0.0.1:{server.server_port}/fixture.mp4', tmp_path
    server.shutdown(); server.server_close(); worker.join(timeout=3)


def seed(url):
    info = {'id': 'fixture', 'title': 'fixture', 'extractor': 'Fixture', 'duration': 1,
            'formats': [{'format_id': 'source', 'url': url, 'ext': 'mp4', 'protocol': 'http',
                         'width': 320, 'height': 180, 'vcodec': 'avc1', 'acodec': 'mp4a'}]}
    YtDlpEngine._cache[YtDlpEngine._key(url, '未知平台')] = (time.monotonic(), info)
    # UrlSniffer's exact current generic label is intentionally discovered here.
    from engine.url_sniffer import UrlSniffer
    YtDlpEngine._cache[YtDlpEngine._key(url, UrlSniffer.identify_site(url))] = (time.monotonic(), info)


@pytest.mark.parametrize('kind,audio', [(DownloadType.VIDEO_WITH_AUDIO, True), (DownloadType.VIDEO_ONLY, False)])
def test_http_download_and_actual_output(media_server, monkeypatch, kind, audio):
    url, directory = media_server
    monkeypatch.setattr(config, 'download_dir', str(directory / 'exports'))
    seed(url)
    task = DownloadTask(id=uuid.uuid4().hex, url=url, title='real transfer', downloadType=kind,
                        selectedVideoFormatId='source', qualitySelection=QualitySelection(mode='EXACT_FORMAT'))
    result = asyncio.run(YtDlpEngine.download(task, lambda *a: None, asyncio.Event()))
    actual = probe(result)
    assert (actual['width'], actual['height'], actual['audio']) == (320, 180, audio)
    assert abs(actual['duration'] - 1) < .2


def test_export_failure_reuses_verified_output(media_server, monkeypatch):
    import shutil
    url, directory = media_server
    monkeypatch.setattr(config, 'download_dir', str(directory / 'exports'))
    seed(url)
    task = DownloadTask(id=uuid.uuid4().hex, url=url, title='retry', downloadType=DownloadType.VIDEO_WITH_AUDIO,
                        selectedVideoFormatId='source')
    original = shutil.copy2
    with monkeypatch.context() as patch:
        patch.setattr(shutil, 'copy2', lambda *a, **k: (_ for _ in ()).throw(OSError('fixture denied')))
        with pytest.raises(RuntimeError, match='EXPORT_FAILED'):
            asyncio.run(YtDlpEngine.download(task, lambda *a: None, asyncio.Event()))
    # A retry must not re-extract or redownload anything, even with the cache gone.
    YtDlpEngine._cache.clear()
    with monkeypatch.context() as patch:
        patch.setattr(YtDlpEngine, '_resolve', lambda *a: (_ for _ in ()).throw(AssertionError('must not parse again')))
        result = asyncio.run(YtDlpEngine.download(task, lambda *a: None, asyncio.Event()))
    assert Path(result).stat().st_size > 0


def test_uncached_direct_media_uses_real_generic_extractor(media_server, monkeypatch):
    url, directory = media_server
    monkeypatch.setattr(config, 'download_dir', str(directory / 'exports'))
    YtDlpEngine._cache.clear()
    task = DownloadTask(id=uuid.uuid4().hex, url=url, title='direct', downloadType=DownloadType.VIDEO_WITH_AUDIO)
    result = asyncio.run(YtDlpEngine.download(task, lambda *a: None, asyncio.Event()))
    assert probe(result)['audio']


def test_unknown_audio_metadata_does_not_reject_a_real_silent_loop(media_server, monkeypatch):
    from engine.url_sniffer import UrlSniffer
    url, directory = media_server
    silent = directory / 'silent.mp4'
    subprocess.run([get_ffmpeg_path(), '-y', '-i', str(directory / 'fixture.mp4'), '-an', '-c:v', 'copy', str(silent)], check=True, capture_output=True)
    silent.replace(directory / 'fixture.mp4')
    monkeypatch.setattr(config, 'download_dir', str(directory / 'silent-export'))
    info = {'id':'loop','title':'loop','extractor':'Fixture','duration':1,
            'formats':[{'format_id':'http','url':url,'ext':'mp4','protocol':'http','width':0,'height':0,'vcodec':None,'acodec':None}]}
    YtDlpEngine._cache[YtDlpEngine._key(url, UrlSniffer.identify_site(url))] = (time.monotonic(), info)
    task=DownloadTask(id=uuid.uuid4().hex,url=url,title='silent loop',downloadType=DownloadType.VIDEO_WITH_AUDIO,selectedVideoFormatId='http')
    result=asyncio.run(YtDlpEngine.download(task,lambda *a:None,asyncio.Event()))
    actual=probe(result)
    assert actual['width']==320 and not actual['audio']


def test_native_hls_source_enumerates_manifest_before_download(media_server, monkeypatch):
    from core.models import FormatOption
    from core.session_store import sessions
    from engine.platforms import PlatformEngine
    source_url, directory = media_server
    subprocess.run([get_ffmpeg_path(), '-y', '-i', str(directory / 'fixture.mp4'), '-c', 'copy',
                    '-f', 'hls', '-hls_time', '1', str(directory / 'stream.m3u8')], check=True, capture_output=True)
    url = 'https://www.xiaoheihe.cn/app/bbs/link/fixture'
    monkeypatch.setattr(config, 'download_dir', str(directory / 'hls-export'))
    PlatformEngine._sources[url] = {'urls': [source_url.replace('fixture.mp4', 'stream.m3u8')],
        'formats': [FormatOption(formatId='native-source', resolutionLabel='HLS')], 'site': '小黑盒', 'frames': [],
        'id': 'fixture', 'generation': sessions.status('小黑盒')['generation'], 'created': time.monotonic()}
    task = DownloadTask(id=uuid.uuid4().hex, url=url, title='native-hls', downloadType=DownloadType.VIDEO_WITH_AUDIO,
                        selectedVideoFormatId='native-source', qualitySelection=QualitySelection(mode='EXACT_FORMAT'))
    result = asyncio.run(PlatformEngine.download(task, lambda *a: None, asyncio.Event()))
    assert probe(result)['width'] == 320


def test_real_8k_priority_and_4k_ceiling(media_server, monkeypatch):
    url, directory = media_server
    for name, dimensions in [('4k', '3840x2160'), ('8k', '7680x4320')]:
        subprocess.run([get_ffmpeg_path(), '-y', '-f', 'lavfi', '-i', f'color=c=blue:s={dimensions}:r=1',
                        '-frames:v', '1', '-c:v', 'libx264', '-preset', 'ultrafast', '-threads', '2', '-pix_fmt', 'yuv420p',
                        str(directory / f'{name}.mp4')], check=True, capture_output=True)
    from engine.url_sniffer import UrlSniffer
    info = {'id': 'quality-fixture', 'title': 'quality', 'duration': 1, 'extractor': 'Fixture', 'formats': [
        {'format_id': name, 'width': w, 'height': h, 'vcodec': 'avc1', 'acodec': 'none', 'ext': 'mp4',
         'url': url.replace('fixture.mp4', name + '.mp4')} for name, w, h in [('4k', 3840, 2160), ('8k', 7680, 4320)]]}
    monkeypatch.setattr(config, 'download_dir', str(directory / 'high-resolution'))
    YtDlpEngine._cache[YtDlpEngine._key(url, UrlSniffer.identify_site(url))] = (time.monotonic(), info)
    for policy, dimensions in [(QualitySelection(), (7680, 4320)), (QualitySelection(mode='MAX_HEIGHT', maxHeight=2160), (3840, 2160))]:
        task = DownloadTask(id=uuid.uuid4().hex, url=url, title='quality', downloadType=DownloadType.VIDEO_ONLY, qualitySelection=policy)
        result = asyncio.run(YtDlpEngine.download(task, lambda *a: None, asyncio.Event()))
        actual = probe(result)
        assert (actual['width'], actual['height']) == dimensions


def test_ugoira_variable_delays_and_odd_dimensions(tmp_path):
    from PIL import Image
    from engine.platforms import concat_text
    frames = [{'file': '0.png', 'delay': 80}, {'file': '1.png', 'delay': 230}, {'file': '2.png', 'delay': 390}]
    directory = tmp_path / 'frames'; directory.mkdir()
    for f, color in zip(frames, ['red', 'green', 'blue']): Image.new('RGB', (101, 99), color).save(directory / f['file'])
    concat = tmp_path / 'frames.ffconcat'; concat.write_text(concat_text(frames), encoding='utf-8')
    output = tmp_path / 'ugoira.mp4'
    subprocess.run([get_ffmpeg_path(), '-y', '-safe', '0', '-f', 'concat', '-i', str(concat), '-t', '0.701',
                    '-fps_mode', 'vfr', '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264', '-bf', '0', '-pix_fmt', 'yuv420p', str(output)],
                   check=True, capture_output=True)
    actual = probe(output)
    assert (actual['width'], actual['height']) == (102, 100)
    assert abs(actual['duration'] - .700) < .05
