import json
from unittest.mock import Mock

import pytest
import requests
from click.testing import CliRunner

from cli_anything.omnidownloader.core.tasks import get_task, task_path, wait_task
from cli_anything.omnidownloader.omnidownloader_cli import cli, main
from cli_anything.omnidownloader.utils.omnidownloader_backend import Backend, BackendError, redact


@pytest.mark.parametrize("url", ["", "ftp://host", "http://", "http://user:pass@host", "http://host?q=x", "http://host/#x"])
def test_server_validation(url):
    with pytest.raises(BackendError):
        Backend(url)


def test_redact_nested():
    assert redact({"config": {"bilibiliCookie": "secret"}, "items": [{"cookie": "abc"}]}) == {
        "config": {"bilibiliCookie": "***"}, "items": [{"cookie": "***"}]}


def test_dry_run_no_request():
    backend = Backend("http://localhost", dry_run=True)
    backend.http.request = Mock(side_effect=AssertionError("Must not send"))
    result = backend.request("POST", "/api/settings", {"bilibiliCookie": "secret"})
    assert result["body"]["bilibiliCookie"] == "***"
    assert result["dryRun"]


def test_connection_error():
    backend = Backend("http://localhost")
    backend.http.request = Mock(side_effect=requests.ConnectionError("private details"))
    with pytest.raises(BackendError, match="Start the Windows/Web service"):
        backend.request("GET", "/api/tasks")


def test_http_error():
    backend = Backend("http://localhost")
    response = Mock(ok=False, status_code=422, reason="Invalid")
    response.json.return_value = {"detail": "bad task"}
    backend.http.request = Mock(return_value=response)
    with pytest.raises(BackendError, match="HTTP 422: bad task"):
        backend.request("POST", "/api/download")
    assert backend.http.request.call_count == 1


def test_non_json():
    backend = Backend("http://localhost")
    response = Mock()
    response.__enter__ = Mock(return_value=response)
    response.__exit__ = Mock(return_value=False)
    response.json.side_effect = ValueError()
    backend._request = Mock(return_value=response)
    with pytest.raises(BackendError, match="non-JSON"):
        backend.request("GET", "/api/tasks")


@pytest.mark.parametrize("status", ["FAILED", "CANCELLED"])
def test_wait_failure(status):
    backend = Mock()
    backend.request.return_value = [{"id": "x", "status": status, "errorMessage": "bad"}]
    with pytest.raises(BackendError, match=status):
        wait_task(backend, "x", 1, .01)


def test_wait_completed():
    backend = Mock()
    backend.request.return_value = [{"id": "x", "status": "COMPLETED"}]
    assert wait_task(backend, "x", 1, .01)["status"] == "COMPLETED"


def test_wait_timeout():
    backend = Mock()
    backend.request.return_value = [{"id": "x", "status": "PENDING"}]
    with pytest.raises(BackendError, match="Timed out"):
        wait_task(backend, "x", .001, .001)


def test_missing_and_encoded_id():
    backend = Mock()
    backend.request.return_value = []
    with pytest.raises(BackendError, match="not found"):
        get_task(backend, "missing")
    assert task_path("a/b?c") == "/api/tasks/a%2Fb%3Fc"


def test_cli_dry_run_payload():
    result = CliRunner().invoke(cli, ["--json", "--dry-run", "download", "https://example.org/a.mp4", "--title", "测试"])
    assert result.exit_code == 0, result.output
    data = json.loads(result.output)
    assert data["body"]["tasks"][0]["downloadType"] == "VIDEO_WITH_AUDIO"


@pytest.mark.parametrize("args", [["analyze", " "], ["settings", "set"], ["settings", "set", "--max-concurrent", "0"], ["download", "https://example.org", "--title", "x", "--type", "COVER"], ["tasks", "wait", "x", "--interval", "0"]])
def test_cli_invalid_arguments(args):
    result = CliRunner().invoke(cli, args)
    assert result.exit_code != 0


def test_main_json_error(capsys):
    with pytest.raises(SystemExit) as result:
        main(["--json", "--server", "ftp://host", "status"])
    assert result.value.code == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "error" in json.loads(captured.err)


def test_fetch_dry_run_and_no_overwrite(tmp_path):
    backend = Backend("http://localhost", dry_run=True)
    target = tmp_path / "new" / "video.mp4"
    assert backend.fetch("/file", target)["dryRun"]
    assert not target.parent.exists()
    target.parent.mkdir()
    target.write_bytes(b"existing")
    with pytest.raises(BackendError, match="already exists"):
        backend.fetch("/file", target)
    assert target.read_bytes() == b"existing"


def test_fetch_empty_cleanup(tmp_path):
    backend = Backend("http://localhost")
    response = Mock()
    response.__enter__ = Mock(return_value=response)
    response.__exit__ = Mock(return_value=False)
    response.iter_content.return_value = []
    backend._request = Mock(return_value=response)
    with pytest.raises(BackendError, match="empty"):
        backend.fetch("/file", tmp_path / "empty.mp4")
    assert list(tmp_path.iterdir()) == []


def test_default_repl_quit(monkeypatch):
    from cli_anything.omnidownloader.utils.repl_skin import ReplSkin
    monkeypatch.setattr(ReplSkin, "create_prompt_session", lambda self: None)
    monkeypatch.setattr(ReplSkin, "get_input", lambda self, session: "quit")
    result = CliRunner().invoke(cli, [])
    assert result.exit_code == 0, result.output


def test_analysis_available_in_dry_run(monkeypatch):
    backend = Mock()
    backend.request.return_value = {"url": "https://example.org/sample.mp4"}
    monkeypatch.setattr("cli_anything.omnidownloader.omnidownloader_cli.Backend", lambda *args: backend)
    result = CliRunner().invoke(cli, ["--json", "--dry-run", "analyze", "https://example.org/sample.mp4"])
    assert result.exit_code == 0
    backend.request.assert_called_once_with("POST", "/api/analyze", {"url": "https://example.org/sample.mp4"}, read_only=True)


def test_clear_all_dry_run_lists_cancels(monkeypatch):
    original = Backend.request

    def transport(self, method, path, *args, **kwargs):
        if method == "GET":
            return [{"id": "active", "status": "DOWNLOADING"}, {"id": "done", "status": "COMPLETED"}]
        return original(self, method, path, *args, **kwargs)

    monkeypatch.setattr(Backend, "request", transport)
    result = CliRunner().invoke(cli, ["--json", "--dry-run", "tasks", "clear", "--all"])
    assert result.exit_code == 0
    data = json.loads(result.output)
    assert [op["path"] for op in data["operations"]] == ["/api/tasks/active/cancel", "/api/tasks/clear"]


def test_cookie_file_redacted_in_dry_run(tmp_path):
    cookie = tmp_path / "cookie.txt"
    cookie.write_text("SESSDATA=test-secret", encoding="utf-8")
    result = CliRunner().invoke(cli, ["--json", "--dry-run", "settings", "set", "--cookie-file", str(cookie)])
    assert result.exit_code == 0
    assert "test-secret" not in result.output
    assert json.loads(result.output)["body"]["bilibiliCookie"] == "***"
