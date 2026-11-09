from fastapi.testclient import TestClient
from omni_server import app


def test_runtime_and_session_api_are_available_without_secrets():
    with TestClient(app) as client:
        response = client.get('/api/engine/diagnostics')
        assert response.status_code == 200
        assert response.json()['denoAvailable']
        assert response.json()['ytDlp'] == '2026.10.26'
        assert client.get('/api/sessions').status_code == 200
        settings = client.get('/api/settings').json()
        assert settings['bilibiliCookie'] == ''


def test_session_mutation_refuses_cross_origin_and_bad_files():
    with TestClient(app) as client:
        payload = {'platform': 'X (Twitter)', 'cookies': 'invalid'}
        assert client.post('/api/sessions', json=payload, headers={'Origin': 'https://untrusted.test'}).status_code == 403
        assert client.post('/api/sessions', json=payload).status_code == 400
        assert client.post('/api/tasks/nonexistent/retry').status_code == 409


def test_cancelled_task_cannot_become_failed_from_late_engine_error(monkeypatch):
    import asyncio
    from core.models import DownloadTask, DownloadType, TaskStatus
    from core.task_manager import task_manager
    from engine.ytdlp_engine import YtDlpEngine
    task = DownloadTask(id='cancel-fixture', url='https://example.test/fixture.mp4', title='fixture', downloadType=DownloadType.VIDEO_WITH_AUDIO)
    task_manager.tasks[task.id] = task
    event = asyncio.Event()
    task_manager.cancel_events[task.id] = event
    async def cancelled_download(*args):
        event.set()
        raise RuntimeError('[CANCELLED] fixture late error')
    monkeypatch.setattr(YtDlpEngine, 'download', cancelled_download)
    try:
        asyncio.run(task_manager._process_task(task.id))
        assert task.status == TaskStatus.CANCELLED
    finally:
        task_manager.tasks.pop(task.id, None)
        task_manager.cancel_events.pop(task.id, None)
