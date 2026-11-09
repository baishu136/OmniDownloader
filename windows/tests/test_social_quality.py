import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

import requests

from engine.media_resolver import probe_original_mp4, sparse_mp4_dimensions, xhs_video_formats, resolve_site_video


def box(name, payload):
    return (len(payload) + 8).to_bytes(4, 'big') + name + payload


def original_fixture():
    ftyp = box(b'ftyp', bytes(8))
    mdat_size = 20_000_000_000
    moov_at, moov_size = len(ftyp) + mdat_size, 4_000_000
    track = box(b'tkhd', bytes(76) + (2160 << 16).to_bytes(4, 'big') + (3840 << 16).to_bytes(4, 'big'))
    segments = {0: ftyp + (1).to_bytes(4, 'big') + b'mdat' + mdat_size.to_bytes(8, 'big'),
                moov_at: moov_size.to_bytes(4, 'big') + b'moov' + (moov_size - 8).to_bytes(4, 'big') + b'trak' + track}
    return segments, moov_at + moov_size


def test_sparse_reader_seeks_over_large_mdat_without_needing_complete_moov():
    segments, total = original_fixture()
    reads = []
    def read(at, count):
        reads.append((at, count))
        for start, data in segments.items():
            if start <= at and at + count <= start + len(data): return data[at - start:at - start + count]
        return None
    assert sparse_mp4_dimensions(read) == (2160, 3840)
    assert max(at for at, _ in reads) > 2**32
    assert sum(size for _, size in reads) < 256
    assert sparse_mp4_dimensions(lambda at, count: (0).to_bytes(16, 'big')[:count]) is None


def test_http_sparse_probe_retries_transient_failure_and_handles_clamped_ranges():
    segments, total = original_fixture()
    calls = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            assert 'Cookie' not in self.headers and 'Authorization' not in self.headers
            start = int(self.headers['Range'].split('=')[1].split('-')[0])
            calls.append(start)
            if len(calls) == 1:
                self.send_response(503); self.send_header('Content-Length', '0'); self.end_headers(); return
            end = min(start + 1023, total - 1)
            data = bytearray(end - start + 1)
            for at, content in segments.items():
                a, b = max(start, at), min(end + 1, at + len(content))
                if a < b: data[a - start:b - start] = content[a - at:b - at]
            self.send_response(206)
            self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def log_message(self, *args): pass
    server = HTTPServer(('127.0.0.1', 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
    try:
        with requests.Session() as client:
            client.trust_env = False
            client.headers['Cookie'] = 'test-only-must-not-leak=1'
            client.headers['Authorization'] = 'test-only-must-not-leak'
            assert probe_original_mp4(client, f'http://127.0.0.1:{server.server_port}/original.mp4') == (2160, 3840)
        assert 3 <= len(calls) <= 5
        assert max(calls) > 2**32
    finally:
        server.shutdown(); server.server_close(); worker.join(2)


def note():
    return {'type': 'video', 'noteId': 'fixture', 'video': {'consumer': {'originVideoKey': 'fixture/original.mp4'},
        'media': {'stream': {'h264': [{'masterUrl': 'https://cdn.test/low.mp4', 'width': 720, 'height': 1280, 'duration': 3000}]}}}}


def test_original_is_preserved_without_inventing_dimensions_when_probe_fails():
    with patch('engine.media_resolver.probe_original_mp4', return_value=None):
        formats = xhs_video_formats(note(), object())
    assert len(formats) == 2
    assert formats[-1]['width'] == formats[-1]['height'] == 0
    assert formats[-1]['format_note'] == '原始源（尺寸待确认）'
    assert all(item['format_id'] == item['url'] for item in formats)


def test_shared_fallback_keeps_verified_4k_instead_of_only_h5_720():
    with patch('engine.media_resolver.xhs_target', return_value=('https://www.xiaohongshu.com/explore/fixture', 'fixture', note())), \
         patch('engine.media_resolver.probe_original_mp4', return_value=(2160, 3840)):
        result = resolve_site_video('https://xhslink.cn/fixture', '小红书')
    assert result['id'] == 'fixture'
    assert any(item['width'] == 2160 and item['height'] == 3840 for item in result['formats'])
    assert result['quality_warning'] == ''
    assert result['duration'] == 3
