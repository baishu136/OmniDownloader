import asyncio
import threading
import http.server
from fastapi.testclient import TestClient
from core.models import DownloadTask, DownloadType, TaskStatus
from core.task_manager import task_manager
from engine.platforms import normalize, platform
from engine.service_features import select_update
from engine.ytdlp_engine import YtDlpEngine
from omni_server import app


def test_new_platform_keeps_existing_credential_file_numbers():
    from core.session_store import DOMAINS
    assert list(DOMAINS)[:3] == ['YouTube', 'X (Twitter)', '小红书']
    assert list(DOMAINS)[6] == '哔哩哔哩'
    assert list(DOMAINS)[-1] == '微博'


def test_catalog_uses_installed_extractors():
    with TestClient(app) as client:
        data = client.get('/api/platforms').json()
        assert {'Weibo', 'vimeo', 'Ixigua', 'Reddit'} <= set(data['extractors'])
        assert 'generic' not in data['extractors']


def test_updates_select_web_asset_and_ignore_android(monkeypatch):
    monkeypatch.setattr('engine.service_features.VERSION', '1.7.51')
    release = {'tag_name': 'v1.7.52', 'assets': [{'name': 'OmniDownloader_LATEST.apk'}]}
    assert select_update([release]) is None
    release['assets'].append({'name': 'OmniDownloader_Web-1.7.52.zip',
        'browser_download_url': 'https://github.com/baishu136/OmniDownloader/releases/download/v1.7.52/web.zip'})
    assert select_update([release])['version'] == '1.7.52'
    release['prerelease'] = True
    assert select_update([release]) is None


def test_weibo_identity_and_video_without_dimensions():
    assert platform('https://weibo.com/123/ABC') == '微博'
    assert platform('https://weibo.com.example.org/123/ABC') == '网络视频'
    assert normalize('https://weibo.com/123/ABC') == 'https://m.weibo.cn/detail/ABC'
    meta = YtDlpEngine._metadata({'id': 'abc', 'formats': [{
        'format_id': 'original', 'url': 'https://cdn.example/abc.mp4', 'ext': 'mp4',
    }]}, 'https://example.test/video', '网络视频')
    assert meta.available_video_formats[0].height == 0
    assert meta.available_video_formats[0].format_id == 'original'


def test_completed_collection_video_has_inline_range_response(tmp_path):
    path = tmp_path / 'episode.mp4'
    path.write_bytes(b'0123456789')
    task = DownloadTask(id='inline-episode', url='https://example.test/video', title='episode',
        downloadType=DownloadType.VIDEO_WITH_AUDIO, status=TaskStatus.COMPLETED, localFilePath=str(path))
    task_manager.tasks[task.id] = task
    try:
        with TestClient(app) as client:
            response = client.get('/api/tasks/inline-episode/view', headers={'Range': 'bytes=2-5'})
            assert response.status_code == 206
            assert response.content == b'2345'
            assert response.headers['content-type'] == 'video/mp4'
            assert response.headers['content-disposition'].startswith('inline')
            assert client.get('/api/tasks/missing/view').status_code == 404
    finally:
        task_manager.tasks.pop(task.id)


def test_generic_collection_resolves_each_selected_id(monkeypatch):
    info = {'entries': [{'id': str(index), 'title': str(index), 'formats': [{
        'format_id': 'http', 'url': f'https://cdn.example/{index}.mp4', 'ext': 'mp4'}]} for index in range(2)]}
    monkeypatch.setattr(YtDlpEngine, '_resolve', classmethod(lambda cls, *args: info))
    meta = YtDlpEngine._extract_sync('https://example.test/playlist', '网络视频')
    assert [item.media_id for item in meta.multi_media_list] == ['0', '1']
    assert len({item.url for item in meta.multi_media_list}) == 2
