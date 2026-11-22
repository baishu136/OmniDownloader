import asyncio
import pytest
from fastapi import HTTPException
from core.batch_manager import BatchManager, links
from core.models import VideoMetadata
from engine.platforms import platform
import omni_server


def test_batch_link_deduplication_and_limits():
    assert links('分享 https://example.test/1。\nhttps://example.test/1\nBV1234567890') == ['https://example.test/1', 'BV1234567890']
    with pytest.raises(ValueError):
        links('\n'.join(f'https://example.test/{i}' for i in range(101)))


def test_unified_analyze_falls_back_only_for_media_errors(monkeypatch):
    async def video(req):
        raise HTTPException(400, '[NO_MEDIA] image')
    async def image(req):
        return {'resources': [{'id': '1'}]}
    monkeypatch.setattr(omni_server, 'analyze_url', video)
    monkeypatch.setattr(omni_server, 'analyze_image_post', image)
    req = omni_server.AnalyzeRequest(url='https://www.xiaohongshu.com/explore/abc')
    assert asyncio.run(omni_server.analyze_media(req))['kind'] == 'images'
    async def protected(req):
        raise HTTPException(400, '[AUTH_REQUIRED] sign in')
    monkeypatch.setattr(omni_server, 'analyze_url', protected)
    with pytest.raises(HTTPException):
        asyncio.run(omni_server.analyze_media(req))


def test_batch_retry_persistence_and_no_reenqueue(tmp_path, monkeypatch):
    import core.batch_manager as module
    monkeypatch.setattr(module, 'CONFIG_FILE', tmp_path / 'config.json')
    async def scenario():
        manager = BatchManager()
        calls = []
        async def submit(row, options):
            calls.append(row['url'])
            if row['url'].endswith('/2') and calls.count(row['url']) == 1:
                raise ValueError('network fixture')
            return ['task-' + row['id']]
        job = manager.create('https://example.test/1 https://example.test/2', {}, submit)
        await manager.workers[job['id']]
        assert [row['status'] for row in job['items']] == ['QUEUED', 'FAILED']
        manager.run(job['id'], submit)
        await manager.workers[job['id']]
        assert calls.count('https://example.test/1') == 1
        assert [row['status'] for row in job['items']] == ['QUEUED', 'QUEUED']
        restored = BatchManager()
        assert restored.jobs[job['id']] == job
        assert b'https://' not in restored.path.read_bytes()
    asyncio.run(scenario())


def test_batch_cancel_preserves_already_queued_rows(tmp_path, monkeypatch):
    import core.batch_manager as module
    monkeypatch.setattr(module, 'CONFIG_FILE', tmp_path / 'config.json')
    async def scenario():
        manager = BatchManager()
        entered = asyncio.Event()
        async def submit(row, options):
            if row['url'].endswith('/2'):
                entered.set()
                await asyncio.Event().wait()
            return ['first']
        job = manager.create('https://example.test/1 https://example.test/2 https://example.test/3', {}, submit)
        await entered.wait()
        worker = manager.workers[job['id']]
        manager.cancel(job['id'])
        with pytest.raises(asyncio.CancelledError):
            await worker
        assert [row['status'] for row in job['items']] == ['QUEUED', 'CANCELLED', 'CANCELLED']
        assert job['items'][0]['taskIds'] == ['first']
    asyncio.run(scenario())


def test_batch_automatically_archives_images_like_android(monkeypatch):
    async def analyze(req):
        return {'kind': 'images', 'data': {'url': req.url, 'platform': '小红书', 'title': 'images',
            'resources': [{'id': '1'}, {'id': '2'}]}}
    captured = []
    async def create(req):
        captured.extend(req.tasks)
        return {'tasks': [{'id': 'image-task'}]}
    monkeypatch.setattr(omni_server, 'analyze_media', analyze)
    monkeypatch.setattr(omni_server, 'create_download_tasks', create)
    ids = asyncio.run(omni_server.enqueue_batch_row({'id': 'auto-images', 'url': 'https://example.test/images'},
        {'type': 'VIDEO_WITH_AUDIO', 'audio': 'mp3', 'allEpisodes': False}))
    assert ids == ['image-task']
    assert len(captured) == 1
    assert captured[0].download_type.value == 'IMAGE_ZIP'
    assert captured[0].selected_image_ids == ['1', '2']
