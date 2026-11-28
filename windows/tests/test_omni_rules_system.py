"""Omni Download Rules 核心规则系统、安全防护、合集筛选、去重索引与管道集成测试。"""

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from core.models import DownloadType, FormatOption, TaskStatus, VideoMetadata
from core.rules import (
    DownloadRule,
    RuleExecutionAction,
    RuleFallbackStrategy,
    RuleTrigger,
    apply_external_import_safety_guard,
    export_rules_bundle,
    parse_and_validate_rule_json,
    resolve_safe_subpath,
    resolve_task_export_target,
    rule_engine,
    rule_store,
)
import core.config as core_config
import omni_server


@pytest.fixture()
def isolated_rule_state(tmp_path, monkeypatch):
    """隔离所有规则配置、任务与批次状态，确保测试不污染真实用户环境。"""
    import core.batch_manager as batch_mod
    import core.task_manager as tm_mod

    monkeypatch.setattr(core_config, "CONFIG_FILE", tmp_path / "config.json")
    monkeypatch.setattr(batch_mod, "CONFIG_FILE", tmp_path / "config.json")
    monkeypatch.setattr(tm_mod, "TASKS_STORAGE_FILE", tmp_path / "tasks.json")
    monkeypatch.setattr(core_config.config, "download_dir", str(tmp_path / "downloads"))
    monkeypatch.setattr(core_config.config, "follow_default_dir", True)
    (tmp_path / "downloads").mkdir(parents=True, exist_ok=True)

    # 重置 rule_store 加载目录指针以加载隔离沙盒
    rule_store._loaded_dir = None
    rule_store.update_settings({"rulesSystemEnabled": True, "clipboardWatchEnabled": False, "clipboardPaused": False})
    yield tmp_path


def test_rule_json_validation_security_and_import_guard(isolated_rule_state):
    # 1. 错误语法精确定位
    with pytest.raises(ValueError, match="JSON 语法错误"):
        parse_and_validate_rule_json('{"name": "bad", ')

    # 2. 禁止越权目录与路径穿越
    with pytest.raises(ValueError):
        parse_and_validate_rule_json(json.dumps({
            "name": "escape",
            "files": {"categoryDir": "../../Windows/System32"}
        }))
    with pytest.raises(ValueError):
        parse_and_validate_rule_json(json.dumps({
            "name": "abs",
            "files": {"categoryDir": "C:\\temp"}
        }))
    with pytest.raises(ValueError):
        parse_and_validate_rule_json(json.dumps({
            "name": "cmd",
            "files": {"filenameTemplate": "$(calc.exe)"}
        }))

    # 3. 禁止包含 Cookie/Token 等敏感信息
    with pytest.raises(ValueError, match="Cookie|Token|禁止"):
        parse_and_validate_rule_json(json.dumps({
            "name": "leak",
            "platformOverrides": {"cookie": "SESSDATA=secret123"}
        }))

    # 4. 外部导入规则默认强制禁止 auto_download -> 降级 confirm，且导出完全不含敏感信息
    imported, warns = parse_and_validate_rule_json(json.dumps({
        "schemaVersion": 1,
        "rules": [{
            "name": "外源4K自动下载",
            "execution": {"action": "auto_download"},
            "platformOverrides": {"windows": {"note": "ok"}}
        }]
    }))
    assert not warns
    safe_rules, notes = apply_external_import_safety_guard(imported)
    assert len(safe_rules) == 1
    assert safe_rules[0].execution.action == RuleExecutionAction.CONFIRM
    assert safe_rules[0].imported_untrusted is True
    assert any("确认后下载" in n for n in notes)

    bundle = export_rules_bundle(safe_rules)
    serialized = json.dumps(bundle, ensure_ascii=False)
    assert "SESSDATA" not in serialized
    assert "importedUntrusted" not in serialized


def test_resolve_safe_subpath_sandbox_boundary(isolated_rule_state):
    root = isolated_rule_state / "downloads"
    ok_path = resolve_safe_subpath(root, "哔哩哔哩/教程合集")
    assert ok_path.is_relative_to(root.resolve())

    with pytest.raises(ValueError):
        resolve_safe_subpath(root, "../outside")

    fake_task = SimpleNamespace(
        download_type=DownloadType.VIDEO_WITH_AUDIO,
        output_dir_override="D:\\ForbiddenDir",
        filename_override="",
        duplicate_policy="rename",
    )
    with pytest.raises(ValueError, match="SECURITY_VIOLATION"):
        resolve_task_export_target(fake_task, "test", ".mp4")


def test_quality_fallback_and_collection_filtering_and_dedup(isolated_rule_state):
    rule = DownloadRule.model_validate({
        "name": "合集精选 1080p 规则",
        "priority": 10,
        "triggers": ["collection", "batch", "clipboard", "manual"],
        "match": {
            "platforms": ["哔哩哔哩"],
            "mediaTypes": ["video"],
            "titleKeywords": ["教程"],
        },
        "collection": {
            "mode": "episodes",
            "episodes": "1, 3-4",
            "excludeKeywords": ["花絮"],
            "skipDownloaded": True,
        },
        "download": {"mode": "VIDEO_WITH_AUDIO"},
        "quality": {
            "strategy": "target_resolution",
            "resolution": 2160,
            "codec": "av01",
            "minFps": 60,
            "fallback": "closest_lower",
        },
        "files": {
            "categoryDir": "{platform}/{author}",
            "collectionSubfolder": True,
            "filenameTemplate": "{episodeIndex}_{title}",
            "duplicatePolicy": "skip",
        },
        "execution": {"action": "auto_download", "maxTasks": 50},
    })
    rule_store.upsert_rule(rule)

    sample_formats = [
        FormatOption(formatId="1080_avc", resolutionLabel="1080p", width=1920, height=1080, fps=30, vcodec="avc1.640032", ext="mp4"),
        FormatOption(formatId="720_avc", resolutionLabel="720p", width=1280, height=720, fps=30, vcodec="avc1.4d401f", ext="mp4"),
    ]
    meta = VideoMetadata(
        url="https://www.bilibili.com/video/BV1xx411c7mD",
        mediaId="BV1xx411c7mD:101",
        title="Python 教程合集",
        author="OmniDev",
        siteName="哔哩哔哩",
        expectedDurationMs=600_000,
        availableVideoFormats=sample_formats,
        multiMediaList=[
            VideoMetadata(url="https://www.bilibili.com/video/BV1xx411c7mD?p=1", mediaId="BV1xx411c7mD:101", title="P1 入门", author="OmniDev", siteName="哔哩哔哩", availableVideoFormats=sample_formats),
            VideoMetadata(url="https://www.bilibili.com/video/BV1xx411c7mD?p=2", mediaId="BV1xx411c7mD:102", title="P2 跳过未选", author="OmniDev", siteName="哔哩哔哩", availableVideoFormats=sample_formats),
            VideoMetadata(url="https://www.bilibili.com/video/BV1xx411c7mD?p=3", mediaId="BV1xx411c7mD:103", title="P3 花絮", author="OmniDev", siteName="哔哩哔哩", availableVideoFormats=sample_formats),
            VideoMetadata(url="https://www.bilibili.com/video/BV1xx411c7mD?p=4", mediaId="BV1xx411c7mD:104", title="P4 进阶", author="OmniDev", siteName="哔哩哔哩", availableVideoFormats=sample_formats),
        ],
    )

    async def mock_analyze(url: str):
        return {"kind": "video", "data": meta}

    # 1. Dry-run 应筛选出 P1 和 P4（P2 未在 episodes 内，P3 命中排除词 花絮），并记录缺失 2160p/60fps/AV1 的明确回退警告
    plan = asyncio.run(rule_engine.evaluate_url(
        meta.url,
        trigger=RuleTrigger.COLLECTION,
        analyze_media_fn=mock_analyze,
        dry_run=True,
    ))
    assert plan["matched"] is True
    assert len(plan["plannedTasks"]) == 2
    assert [t["episodeIndex"] for t in plan["plannedTasks"]] == [1, 4]
    assert any("帧率" in w or "编码" in w or "分辨率" in w for w in plan["warnings"])
    assert "01_P1 入门" in plan["plannedTasks"][0]["filenameOverride"]
    assert "哔哩哔哩" in plan["plannedTasks"][0]["outputDirOverride"]

    # 2. 跨入口去重：登记 P1 已下载文件后，再从 clipboard 入口进来 P1 自动被跳过，仅剩 P4
    existing_file = Path(plan["plannedTasks"][0]["plannedPath"])
    existing_file.parent.mkdir(parents=True, exist_ok=True)
    existing_file.write_bytes(b"video-content")
    p0 = plan["plannedTasks"][0]
    rule_store.record_dedup_entry(
        p0["dedupKey"],
        platform=p0["platform"],
        media_id=meta.media_id,
        episode_id=p0["mediaId"],
        download_type=p0["downloadType"],
        quality_strategy=p0["qualitySignature"],
        output_format=p0["outputFormat"],
        title=p0["title"],
        url=p0["url"],
        task_id="t-done-1",
        local_file_path=str(existing_file),
        status="COMPLETED",
    )

    plan2 = asyncio.run(rule_engine.evaluate_url(
        meta.url,
        trigger=RuleTrigger.CLIPBOARD,
        analyze_media_fn=mock_analyze,
        dry_run=True,
    ))
    assert [t["episodeIndex"] for t in plan2["plannedTasks"]] == [4]
    assert any(s["index"] == 1 and "已下载" in s["reason"] for s in plan2["skippedItems"])

    # 即便规则未启用合集级 skipDownloaded，全局去重校验仍会将其记录在 dedupSkipped 中防重
    rule_no_skip = rule.model_copy(deep=True)
    rule_no_skip.collection.skip_downloaded = False
    rule_store.upsert_rule(rule_no_skip)
    plan2_dedup = asyncio.run(rule_engine.evaluate_url(
        meta.url,
        trigger=RuleTrigger.CLIPBOARD,
        analyze_media_fn=mock_analyze,
        dry_run=True,
    ))
    assert [t["episodeIndex"] for t in plan2_dedup["plannedTasks"]] == [4]
    assert len(plan2_dedup["dedupSkipped"]) == 1

    # 3. 删掉磁盘已有文件后，去重索引失效并允许重下
    existing_file.unlink()
    plan3 = asyncio.run(rule_engine.evaluate_url(
        meta.url,
        trigger=RuleTrigger.BATCH,
        analyze_media_fn=mock_analyze,
        dry_run=True,
    ))
    assert [t["episodeIndex"] for t in plan3["plannedTasks"]] == [1, 4]


def test_enqueue_batch_row_mixed_rules_and_disabled_fallback(isolated_rule_state, monkeypatch):
    # 规则 1：忽略包含“广告”的链接；规则 2：YouTube 自动下载音频 MP3；其他未命中走默认批量流程
    rule_store.upsert_rule(DownloadRule.model_validate({
        "name": "忽略广告链接",
        "priority": 10,
        "triggers": ["batch"],
        "match": {"titleKeywords": ["广告"]},
        "execution": {"action": "ignore"},
    }))
    rule_store.upsert_rule(DownloadRule.model_validate({
        "name": "YouTube 提取音频",
        "priority": 20,
        "triggers": ["batch"],
        "match": {"domains": ["youtube.com", "youtu.be"]},
        "download": {"mode": "AUDIO_ONLY"},
        "quality": {"audioFormat": "flac"},
        "files": {"categoryDir": "音乐提取"},
        "execution": {"action": "auto_download"},
    }))

    async def fake_analyze(req):
        if "ad" in req.url:
            return {"kind": "video", "data": VideoMetadata(url=req.url, title="这是一条广告宣传", siteName="网络视频")}
        if "youtube.com" in req.url:
            return {"kind": "video", "data": VideoMetadata(url=req.url, mediaId="yt_123", title="LoFi Mix", siteName="YouTube", author="Chilled")}
        return {"kind": "video", "data": VideoMetadata(url=req.url, mediaId="other_1", title="普通作品", siteName="TikTok")}

    created_batches = []
    async def fake_create(req):
        created_batches.append(req.tasks)
        return {"tasks": [{"id": f"task-{i}"} for i in range(len(req.tasks))]}

    monkeypatch.setattr(omni_server, "analyze_media", fake_analyze)
    monkeypatch.setattr(omni_server, "create_download_tasks", fake_create)

    # A. 广告链接被忽略
    row_ad = {"id": "r1", "url": "https://example.test/ad"}
    ids_ad = asyncio.run(omni_server.enqueue_batch_row(row_ad, {"type": "VIDEO_WITH_AUDIO", "audio": "mp3", "allEpisodes": False}))
    assert ids_ad == []
    assert row_ad.get("_rule_status") == "IGNORED"

    # B. YouTube 链接被规则接管转为 FLAC 音频 + 不可变快照 + 分类目录
    row_yt = {"id": "r2", "url": "https://www.youtube.com/watch?v=123"}
    ids_yt = asyncio.run(omni_server.enqueue_batch_row(row_yt, {"type": "VIDEO_WITH_AUDIO", "audio": "mp3", "allEpisodes": False}))
    assert ids_yt == ["task-0"]
    assert created_batches[-1][0].download_type == DownloadType.AUDIO_ONLY
    assert created_batches[-1][0].audio_format.value == "flac"
    assert "音乐提取" in created_batches[-1][0].output_dir_override
    assert created_batches[-1][0].rule_snapshot["ruleName"] == "YouTube 提取音频"

    # C. 后续修改或删除规则不影响前面已生成任务里的不可变快照参数
    rule_store.delete_rule(created_batches[-1][0].rule_snapshot["ruleId"])
    assert created_batches[-1][0].rule_snapshot["ruleName"] == "YouTube 提取音频"

    # D. 未命中规则回退原始批量选项
    row_tk = {"id": "r3", "url": "https://www.tiktok.com/@u/video/1"}
    ids_tk = asyncio.run(omni_server.enqueue_batch_row(row_tk, {"type": "VIDEO_ONLY", "audio": "mp3", "allEpisodes": False}))
    assert ids_tk == ["task-0"]
    assert created_batches[-1][0].download_type == DownloadType.VIDEO_ONLY
    assert created_batches[-1][0].rule_snapshot is None

    # E. 关闭规则系统后，之前命中的链接也完全回归原始手动逻辑
    rule_store.update_settings({"rulesSystemEnabled": False})
    row_ad2 = {"id": "r4", "url": "https://example.test/ad"}
    ids_ad2 = asyncio.run(omni_server.enqueue_batch_row(row_ad2, {"type": "VIDEO_WITH_AUDIO", "audio": "mp3", "allEpisodes": False}))
    assert ids_ad2 == ["task-0"]


def test_clipboard_api_ignores_noise_stops_on_auth_and_legacy_task_compat(isolated_rule_state, monkeypatch):
    rule_store.upsert_rule(DownloadRule.model_validate({
        "name": "剪贴板高画质捕获",
        "priority": 10,
        "triggers": ["clipboard"],
        "match": {"domains": ["bilibili.com"]},
        "execution": {"action": "auto_download"},
    }))

    async def fake_analyze(req):
        if "locked" in req.url:
            raise HTTPException(400, "[AUTH_REQUIRED] 请先登录")
        return {"kind": "video", "data": VideoMetadata(url=req.url, mediaId="BV1abc123456", title="好视频", siteName="哔哩哔哩")}

    monkeypatch.setattr(omni_server, "analyze_media", fake_analyze)
    client = TestClient(omni_server.app)

    # 1. 无链接日常剪贴板内容不匹配且不报错、不污染审计日志
    r_noise = client.post("/api/rules/execute", json={"text": "今天下午三点开会", "trigger": "clipboard"})
    assert r_noise.status_code == 200
    assert r_noise.json()["matched"] is False
    assert rule_store.list_audit_logs() == []

    # 2. 遇到鉴权拦截时立刻中止后续处理并标记 authBlocked
    r_auth = client.post("/api/rules/execute", json={
        "text": "https://www.bilibili.com/video/BV_locked https://www.bilibili.com/video/BV_next",
        "trigger": "clipboard",
    })
    assert r_auth.status_code == 200
    body = r_auth.json()
    assert body["authBlocked"] is True
    assert len(body["results"]) == 1
    assert body["results"][0]["blockedByAuthOrSource"] is True

    # 3. 验证旧版 tasks.json（无任何 ruleSnapshot / outputDirOverride 字段）反序列化兼容
    from core.models import DownloadTask
    legacy = DownloadTask.model_validate({
        "id": "old01",
        "url": "https://example.test/v",
        "title": "legacy",
        "downloadType": "VIDEO_WITH_AUDIO",
    })
    assert legacy.output_dir_override == ""
    assert legacy.duplicate_policy == "rename"
    assert legacy.rule_snapshot is None
