"""测试 GPU 硬件加速能力探测、自动软件回退、-c copy 无损保留及异步持久化机制。"""

import asyncio
import json
import subprocess
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from core.config import config
from core.models import DownloadTask, DownloadType, TaskStatus
from core.task_manager import task_manager
from engine import gpu_accel
from engine.ffmpeg_helper import FFmpegHelper, get_ffmpeg_path
from engine.media_verifier import probe
import omni_server


def test_merge_and_strip_preserve_lossless_stream_copy(monkeypatch):
    recorded_cmds = []

    async def fake_run(cls, args):
        recorded_cmds.append(list(args))
        return True

    monkeypatch.setattr(FFmpegHelper, "_run_command", classmethod(fake_run))
    assert asyncio.run(FFmpegHelper.merge_video_and_audio("v.mp4", "a.m4a", "out.mp4"))
    assert asyncio.run(FFmpegHelper.strip_audio("v.mp4", "mute.mp4"))

    assert "-c" in recorded_cmds[0] and "copy" in recorded_cmds[0]
    assert "libx264" not in recorded_cmds[0] and "h264_nvenc" not in recorded_cmds[0]
    assert "-c:v" in recorded_cmds[1] and "copy" in recorded_cmds[1] and "-an" in recorded_cmds[1]


def test_hardware_encoder_runtime_failure_falls_back_to_software(monkeypatch, tmp_path):
    gpu_accel.clear_probe_cache()
    fake_nvenc = gpu_accel.EncodeProfile("nvidia", "nvenc", "h264_nvenc", "yuv420p", True)
    sw = gpu_accel._SOFTWARE_PROFILE
    monkeypatch.setattr(gpu_accel, "resolve_h264_encode_attempts", lambda _=None: [fake_nvenc, sw])

    calls = []

    async def fake_run(cls, args):
        calls.append(list(args))
        # 模拟首个硬件编码器运行失败，第二次软件 libx264 回退成功
        return "libx264" in args

    monkeypatch.setattr(FFmpegHelper, "_run_command", classmethod(fake_run))
    ok = asyncio.run(
        FFmpegHelper._encode_h264_with_fallback(
            ["-i", "in.mp4"], str(tmp_path / "out.mp4"), allow_hardware=True
        )
    )
    assert ok is True
    assert len(calls) == 2
    assert "h264_nvenc" in calls[0]
    assert "libx264" in calls[1]
    assert "h264_nvenc" in gpu_accel._RUNTIME_FAILED_ENCODERS
    gpu_accel.clear_probe_cache()


def test_gif_to_video_works_across_hardware_and_software_modes(tmp_path, monkeypatch):
    ffmpeg_exe = get_ffmpeg_path()
    assert ffmpeg_exe
    gif_src = tmp_path / "hd.gif"
    subprocess.run(
        [ffmpeg_exe, "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=12", "-t", "0.6", str(gif_src)],
        check=True, capture_output=True
    )

    monkeypatch.setattr(config, "save", lambda: None)
    status_auto = gpu_accel.get_gpu_status(ffmpeg_exe, force_refresh=True)
    assert "adapters" in status_auto
    assert "ffmpegThreads" in status_auto

    for enabled in (True, False):
        gpu_accel.clear_probe_cache()
        monkeypatch.setattr(config, "gpu_acceleration_enabled", enabled)
        monkeypatch.setattr(config, "gpu_preferred_vendor", "auto" if enabled else "software")
        out_mp4 = tmp_path / f"out_{'gpu' if enabled else 'cpu'}.mp4"
        assert asyncio.run(FFmpegHelper.gif_to_video(str(gif_src), str(out_mp4)))
        info = probe(out_mp4)
        assert info["width"] == 320 and info["height"] == 240 and info["duration"] > 0


def test_gpu_settings_and_status_api(monkeypatch):
    srv_config = omni_server.config
    monkeypatch.setattr(srv_config, "save", lambda: None)
    orig_enabled = srv_config.gpu_acceleration_enabled
    orig_vendor = srv_config.gpu_preferred_vendor
    try:
        with TestClient(omni_server.app) as client:
            st = client.get("/api/gpu/status")
            assert st.status_code == 200
            body = st.json()
            assert "statusCode" in body and "activeEncoder" in body

            upd = client.post("/api/settings", json={"gpuAccelerationEnabled": False, "gpuPreferredVendor": "intel"})
            assert upd.status_code == 200
            assert upd.json()["config"]["gpuAccelerationEnabled"] is False
            assert srv_config.gpu_acceleration_enabled is False
            assert srv_config.gpu_preferred_vendor == "intel"

            bad = client.post("/api/settings", json={"gpuPreferredVendor": "invalid_gpu"})
            assert bad.status_code == 400

            client.post("/api/settings", json={"gpuAccelerationEnabled": True, "gpuPreferredVendor": "auto"})
            s2 = client.get("/api/settings").json()
            assert s2["gpuAccelerationEnabled"] is True
            assert s2["gpuPreferredVendor"] == "auto"
            assert "gpuStatus" in s2
    finally:
        srv_config.gpu_acceleration_enabled = orig_enabled
        srv_config.gpu_preferred_vendor = orig_vendor


def test_task_manager_bounded_sse_and_async_save(tmp_path, monkeypatch):
    import core.task_manager as tm_mod
    storage = tmp_path / "tasks.dat"
    monkeypatch.setattr(tm_mod, "TASKS_STORAGE_FILE", storage)
    tm = omni_server.task_manager
    task = DownloadTask(
        id="persist-check-1",
        url="https://example.test/v.mp4",
        title="persist",
        downloadType=DownloadType.VIDEO_WITH_AUDIO,
        status=TaskStatus.COMPLETED,
    )
    tm.tasks[task.id] = task
    try:
        tm.flush_persisted_tasks_sync()
        assert storage.is_file() and storage.read_bytes().startswith(b"OMNI3:")

        async def _check_sse():
            q = await tm.register_listener()
            assert q.maxsize == 256
            for i in range(300):
                await tm.broadcast_event({"type": "ping", "seq": i})
            assert q.qsize() == 256
            tm.unregister_listener(q)

        asyncio.run(_check_sse())
    finally:
        tm.tasks.pop(task.id, None)
