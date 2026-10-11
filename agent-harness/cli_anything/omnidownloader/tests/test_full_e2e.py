"""Tests against a real isolated OmniDownloader HTTP service, never a fake API."""
import functools
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading

import pytest


def _resolve_cli(name):
    path = shutil.which(name)
    if path:
        print(f"[_resolve_cli] Using installed command: {path}")
        return [path]
    if os.environ.get("CLI_ANYTHING_FORCE_INSTALLED") == "1":
        raise RuntimeError(f"{name} not installed; pip install -e ./agent-harness")
    return [sys.executable, "-m", "cli_anything.omnidownloader"]


@pytest.fixture(scope="module")
def run_cli():
    server = os.environ.get("OMNI_TEST_SERVER")
    if not server:
        pytest.fail("OMNI_TEST_SERVER must point to an isolated real backend; see TEST.md")
    base = _resolve_cli("cli-anything-omnidownloader")

    def run(*args, check=True):
        result = subprocess.run(base + ["--server", server, "--json", *args],
                                capture_output=True, text=True, encoding="utf-8", timeout=90,
                                env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        if check:
            assert result.returncode == 0, result.stderr
            return json.loads(result.stdout)
        return result
    return run


def test_status_and_settings(run_cli):
    assert run_cli("status")["status"] == "running"
    original = run_cli("settings", "show")
    try:
        run_cli("settings", "set", "--theme", "dark")
        assert run_cli("settings", "show")["theme"] == "dark"
    finally:
        run_cli("settings", "set", "--theme", original["theme"])


def test_relay_roundtrip(run_cli):
    created = run_cli("relay", "add", "CLI integration test", "https://example.org/cli-test")
    site_id = created["site"]["id"]
    try:
        assert any(s["id"] == site_id for s in run_cli("relay", "list")["relaySites"])
    finally:
        run_cli("relay", "delete", site_id)
    assert not any(s["id"] == site_id for s in run_cli("relay", "list")["relaySites"])


def test_dry_run_no_task(run_cli):
    before = run_cli("tasks", "list")
    result = run_cli("--dry-run", "download", "http://example.org/test.mp4", "--title", "dry")
    assert result["dryRun"]
    assert run_cli("tasks", "list") == before


def test_missing_task_json_error(run_cli):
    result = run_cli("tasks", "info", "missing-cli-test", check=False)
    assert result.returncode != 0
    assert result.stdout == ""
    assert "not found" in json.loads(result.stderr)["error"]


def test_real_download_fetch(run_cli, tmp_path):
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    source = tmp_path / "sample.mp4"
    subprocess.run([ffmpeg, "-y", "-f", "lavfi", "-i", "color=c=red:s=64x64:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(source)], check=True, capture_output=True)
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(tmp_path))
    media_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=media_server.serve_forever, daemon=True)
    thread.start()
    task_id = None
    original = run_cli("settings", "show")
    try:
        run_cli("settings", "set", "--download-dir", str(tmp_path / "server-output"), "--proxy-url", "")
        url = f"http://127.0.0.1:{media_server.server_port}/sample.mp4"
        metadata = run_cli("analyze", url)
        assert metadata["url"] == url
        assert metadata["availableVideoFormats"]
        created = run_cli("download", metadata["url"], "--title", "CLI真实下载")
        task_id = created["tasks"][0]["id"]
        completed = run_cli("tasks", "wait", task_id, "--wait-timeout", "45", "--interval", ".1")
        assert completed["status"] == "COMPLETED"
        output = tmp_path / "fetched.mp4"
        fetched = run_cli("tasks", "fetch", task_id, "--output", str(output))
        assert fetched["bytes"] == output.stat().st_size > 100
        assert b"ftyp" in output.read_bytes()[:32]
        assert output.read_bytes() == source.read_bytes()
        decoded = subprocess.run([ffmpeg, "-i", str(output), "-f", "rawvideo", "-pix_fmt", "rgb24", "-frames:v", "1", "pipe:1"], check=True, capture_output=True).stdout
        assert len(decoded) == 64 * 64 * 3
        assert sum(decoded[0::3]) / (64 * 64) > 200
        assert sum(decoded[1::3]) / (64 * 64) < 30
        print(f"\n  MP4: {output} ({output.stat().st_size:,} bytes)")
        rejected = run_cli("tasks", "fetch", task_id, "--output", str(output), check=False)
        assert rejected.returncode != 0
        assert "already exists" in json.loads(rejected.stderr)["error"]
    finally:
        if task_id:
            run_cli("tasks", "delete", task_id, "--delete-file")
        run_cli("settings", "set", "--download-dir", original["downloadDir"], "--proxy-url", original["proxyUrl"])
        media_server.shutdown()
        media_server.server_close()
