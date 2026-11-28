"""验证 Windows 客户端下载直存本地不经由浏览器触发，以及批量解析记录清空接口。"""

from pathlib import Path
from fastapi.testclient import TestClient


def test_client_source_persists_and_batches_can_be_cleared(monkeypatch, tmp_path):
    monkeypatch.setenv("OMNI_STATE_DIR", str(tmp_path))
    import importlib
    import core.config
    import core.task_manager
    import omni_server

    importlib.reload(core.config)
    importlib.reload(core.task_manager)
    importlib.reload(omni_server)

    async def fake_process(self, task_id):
        task = self.tasks.get(task_id)
        if task:
            task.status = core.models.TaskStatus.CANCELLED

    monkeypatch.setattr(omni_server.task_manager, "_process_task", fake_process.__get__(omni_server.task_manager, type(omni_server.task_manager)))

    client = TestClient(omni_server.app)

    # 1. 提交客户端来源下载任务，校验 clientSource 正确保留且能查询到
    resp = client.post(
        "/api/download",
        json={
            "tasks": [
                {
                    "url": "https://example.com/video",
                    "title": "本地直存视频",
                    "downloadType": "VIDEO_WITH_AUDIO",
                    "clientSource": "winui",
                }
            ]
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["tasks"][0]["clientSource"] == "winui"

    tasks_resp = client.get("/api/tasks")
    assert tasks_resp.status_code == 200
    assert tasks_resp.json()[0]["clientSource"] == "winui"

    # 2. 创建并清空批量解析记录
    omni_server.batch_manager.jobs["job-1"] = {
        "id": "job-1",
        "options": {"type": "VIDEO_WITH_AUDIO", "audio": "mp3", "allEpisodes": False, "clientSource": "winui"},
        "items": [{"id": "row-1", "url": "https://example.com/a", "status": "QUEUED", "note": "已加入", "taskIds": ["t1"]}],
    }
    omni_server.batch_manager.save()
    assert len(client.get("/api/batches").json()) == 1

    clear_resp = client.post("/api/batches/clear?clearAll=true")
    assert clear_resp.status_code == 200
    assert clear_resp.json()["cleared"] == 1
    assert client.get("/api/batches").json() == []


def test_web_tasks_js_never_auto_triggers_browser_download_for_client_tasks():
    root = Path(__file__).resolve().parents[1]
    tasks_js = (root / "web" / "static" / "js" / "tasks.js").read_text("utf-8")
    settings_js = (root / "web" / "static" / "js" / "settings.js").read_text("utf-8")

    assert "updated.clientSource === 'winui'" in tasks_js
    assert "localStorage.getItem('omni_auto_browser_download') === 'true'" in tasks_js
    assert "localStorage.getItem('omni_auto_browser_download') !== 'false'" not in tasks_js
    assert "localStorage.getItem('omni_auto_browser_download') !== 'false'" not in settings_js
