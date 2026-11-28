"""下载计划生成器：负责规则画质映射与回退、路径模板安全渲染及不可变参数快照构建。"""

from __future__ import annotations

import copy
import re
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from core.config import config
from core.models import AudioFormat, DownloadType, FormatOption, QualitySelection
from core.rules.schema import (
    DownloadRule,
    RuleFallbackStrategy,
    resolve_safe_subpath,
    sanitize_filename_segment,
)
from core.rules.store import compute_dedup_key
from engine.quality import dimension as format_dimension, score as format_score


def resolve_quality_for_formats(
    rule: DownloadRule,
    formats: List[FormatOption],
) -> Tuple[QualitySelection, Optional[FormatOption], str, List[str]]:
    """
    根据规则的质量策略，在可用视频流上执行严格评估或按回退策略降级选择，
    同时返回可追溯的回退告警提示。
    """
    q = rule.quality
    warnings: List[str] = []

    selection = QualitySelection(
        mode="TARGET_TIER" if (q.strategy == "target_resolution" and q.resolution > 0) else "BEST_AVAILABLE",
        maxHeight=q.resolution if (q.strategy == "target_resolution" and q.resolution > 0) else 0,
        targetFps=q.min_fps,
        codecPreference=q.codec,
        container=q.container,
        allowLowerQuality=(q.fallback != RuleFallbackStrategy.EXACT_OR_FAIL),
        dynamicRangePreference=q.dynamic_range if q.dynamic_range in ("HDR", "SDR") else "auto",
    )

    if not formats:
        return selection, None, q.fallback.value, warnings

    raw_candidates: List[Dict[str, Any]] = []
    format_map: Dict[str, FormatOption] = {}
    for fmt in formats:
        fid = str(fmt.format_id)
        format_map[fid] = fmt
        raw_candidates.append(
            {
                "format_id": fid,
                "width": int(fmt.width or 0),
                "height": int(fmt.height or 0),
                "fps": int(fmt.fps or 0),
                "vcodec": fmt.vcodec or "",
                "acodec": fmt.acodec or "",
                "ext": fmt.ext or "mp4",
                "dynamic_range": fmt.dynamic_range or "",
                "quality": float(fmt.video_bitrate_kbps or 0),
                "dimensions_verified": bool(fmt.dimensions_verified),
            }
        )

    def _filter_step(
        cands: List[Dict[str, Any]],
        *,
        use_res: bool,
        use_codec: bool,
        use_fps: bool,
        use_dr: bool,
        strict_exact_res: bool,
    ) -> List[Dict[str, Any]]:
        out = list(cands)
        if use_dr and q.dynamic_range in ("HDR", "SDR"):
            has_dr_meta = any(bool(str(c.get("dynamic_range") or "").strip()) for c in out)
            if has_dr_meta:
                if q.dynamic_range == "SDR":
                    out = [c for c in out if str(c.get("dynamic_range") or "").upper() in ("", "SDR")]
                else:
                    out = [c for c in out if str(c.get("dynamic_range") or "").upper() not in ("", "SDR")]
        if use_codec and q.codec != "auto":
            has_codec_meta = any(bool(str(c.get("vcodec") or "").strip()) for c in out)
            if has_codec_meta:
                aliases = {
                    "avc1": ("avc1", "h264", "avc"),
                    "hev1": ("hev1", "hvc1", "hevc", "h265"),
                    "av01": ("av01", "av1"),
                    "vp9": ("vp9", "vp09"),
                }.get(q.codec, (q.codec,))
                out = [
                    c for c in out
                    if any(str(c.get("vcodec") or "").lower().startswith(a) for a in aliases)
                ]
        if use_fps and q.min_fps > 0:
            has_fps_meta = any((c.get("fps") or 0) > 0 for c in out)
            if has_fps_meta:
                out = [c for c in out if (c.get("fps") or 0) >= q.min_fps]
        if use_res and q.strategy == "target_resolution" and q.resolution > 0:
            sized = [c for c in out if format_dimension(c) > 0]
            if sized:
                if strict_exact_res:
                    out = [c for c in sized if format_dimension(c) == q.resolution]
                else:
                    lower_or_eq = [c for c in sized if format_dimension(c) <= q.resolution]
                    out = lower_or_eq if lower_or_eq else []
        return out

    # 检测缺失元数据提示（即使后续命中也提前告知）
    if q.min_fps > 0 and not any((f.fps or 0) > 0 for f in formats):
        warnings.append("目标平台未提供帧率元数据，已跳过最低帧率约束")
    if q.codec != "auto" and not any(bool((f.vcodec or "").strip()) for f in formats):
        warnings.append("目标平台未提供编码信息，已忽略编码偏好")
    if q.dynamic_range in ("HDR", "SDR") and not any(bool((f.dynamic_range or "").strip()) for f in formats):
        warnings.append("目标平台未标注 HDR/SDR 动态范围，已忽略动态范围偏好")

    # 1. 严格匹配尝试
    strict_res = (q.fallback == RuleFallbackStrategy.EXACT_OR_FAIL)
    picked = _filter_step(
        raw_candidates,
        use_res=True,
        use_codec=True,
        use_fps=True,
        use_dr=True,
        strict_exact_res=strict_res,
    )
    if picked:
        # 若 target_resolution 且允许回退，优先尽量接近目标高度而不是直接全无
        best = max(picked, key=format_score)
        best_fmt = format_map[best["format_id"]]
        if q.strategy == "target_resolution" and q.resolution > 0:
            actual_h = format_dimension(best)
            if actual_h and actual_h != q.resolution:
                warnings.append(f"目标分辨率 {q.resolution}p 不可用，已自动选定最近清晰度 {best_fmt.resolution_label}")
        return selection, best_fmt, "matched", warnings

    if q.fallback == RuleFallbackStrategy.EXACT_OR_FAIL:
        raise ValueError("[QUALITY_UNAVAILABLE] 当前媒体未提供满足规则精确质量/编码/帧率要求的流")

    # 2. 按回退策略逐级优雅降级并明确提示
    steps = [
        ("fps", dict(use_res=True, use_codec=True, use_fps=False, use_dr=True, strict_exact_res=False), "未命中帧率要求，已放宽帧率限制"),
        ("dynamic_range", dict(use_res=True, use_codec=True, use_fps=False, use_dr=False, strict_exact_res=False), "未命中 HDR/SDR 要求，已放宽动态范围限制"),
        ("codec", dict(use_res=True, use_codec=False, use_fps=False, use_dr=False, strict_exact_res=False), f"未命中首选编码 {q.codec}，已回退到通用编码"),
        ("resolution", dict(use_res=False, use_codec=False, use_fps=False, use_dr=False, strict_exact_res=False), "未命中目标分辨率上限，已按回退策略自动适配可用画质"),
    ]

    for field_name, kwargs, note in steps:
        cand = _filter_step(raw_candidates, **kwargs)
        if cand:
            if field_name == "resolution" and q.fallback == RuleFallbackStrategy.CLOSEST_LOWER and q.resolution > 0:
                below = [c for c in cand if 0 < format_dimension(c) <= q.resolution]
                if below:
                    cand = below
                else:
                    # 无更低分辨率时取最低可访问分辨率，避免失控下载超高规格
                    sized = [c for c in cand if format_dimension(c) > 0]
                    if sized:
                        best = min(sized, key=format_score)
                        best_fmt = format_map[best["format_id"]]
                        warnings.append(f"{note}（选定 {best_fmt.resolution_label}）")
                        selection = selection.model_copy(update={"max_height": 0, "codec_preference": "auto", "target_fps": 0, "dynamic_range_preference": "auto", "mode": "BEST_AVAILABLE"})
                        return selection, best_fmt, "fallback", warnings

            best = max(cand, key=format_score)
            best_fmt = format_map[best["format_id"]]
            warnings.append(f"{note}（选定 {best_fmt.resolution_label}）")
            selection_updates: Dict[str, Any] = {"allow_lower_quality": True}
            if not kwargs["use_fps"]:
                selection_updates["target_fps"] = 0
            if not kwargs["use_dr"]:
                selection_updates["dynamic_range_preference"] = "auto"
            if not kwargs["use_codec"]:
                selection_updates["codec_preference"] = "auto"
            if not kwargs["use_res"]:
                selection_updates["max_height"] = 0
                selection_updates["mode"] = "BEST_AVAILABLE"
            selection = selection.model_copy(update=selection_updates)
            return selection, best_fmt, "fallback", warnings

    # 保底：使用最高分流
    best_raw = max(raw_candidates, key=format_score)
    best_fmt = format_map[best_raw["format_id"]]
    warnings.append(f"已按最高可用兜底（{best_fmt.resolution_label}）")
    selection = selection.model_copy(
        update={
            "mode": "BEST_AVAILABLE",
            "max_height": 0,
            "target_fps": 0,
            "codec_preference": "auto",
            "dynamic_range_preference": "auto",
            "allow_lower_quality": True,
        }
    )
    return selection, best_fmt, "fallback", warnings


def render_template_string(template: str, variables: Dict[str, str], *, is_filename: bool = False) -> str:
    """安全渲染文件名或子目录模板变量，每个变量值先单独净化的清洗防注入与穿越。"""
    safe_vars = {
        k: sanitize_filename_segment(str(v or ""), default="", max_len=80)
        for k, v in variables.items()
    }
    rendered = template or ""
    for k, v in safe_vars.items():
        rendered = rendered.replace("{" + k + "}", v)
    rendered = re.sub(r"\{[a-zA-Z0-9_]+\}", "", rendered)
    if is_filename:
        return sanitize_filename_segment(rendered, default=safe_vars.get("title") or "media", max_len=120)
    return rendered


def build_output_plan(
    rule: DownloadRule,
    *,
    download_type: str,
    platform: str,
    title: str,
    author: str,
    media_id: str,
    collection_title: str = "",
    episode_index: int = 0,
    resolution_label: str = "",
    ext: str = "mp4",
) -> Tuple[str, str, str]:
    """
    计算安全输出目录相对路径、最终清洗文件基名与完整预览路径：
    返回 (output_dir_override, filename_override, preview_full_path)。
    """
    base_root = Path(config.resolve_download_dir(download_type)).resolve()
    variables = {
        "platform": platform or "unknown",
        "author": author or "unknown",
        "title": title or "media",
        "id": media_id or "id",
        "mediaId": media_id or "id",
        "collectionTitle": collection_title or title or "collection",
        "episodeIndex": f"{episode_index:02d}" if episode_index > 0 else "",
        "resolution": resolution_label or "",
        "ext": ext or "mp4",
    }

    sub_segments: List[str] = []
    if rule.files.category_dir:
        rendered_cat = render_template_string(rule.files.category_dir, variables, is_filename=False)
        if rendered_cat.strip("/\\ "):
            sub_segments.append(rendered_cat.strip("/\\ "))

    if rule.files.collection_subfolder and collection_title:
        col_tpl = rule.files.collection_dir_template or "{collectionTitle}"
        rendered_col = render_template_string(col_tpl, variables, is_filename=False)
        col_clean = sanitize_filename_segment(rendered_col, default="collection", max_len=80)
        if col_clean:
            sub_segments.append(col_clean)

    rel_dir_expr = "/".join(sub_segments)
    resolved_dir = resolve_safe_subpath(base_root, rel_dir_expr) if rel_dir_expr else base_root

    filename_tpl = rule.files.filename_template or "{title}"
    # 若属于合集且模板只是默认 {title} 而未体现序号，保持清晰且避免冲突
    file_base = render_template_string(filename_tpl, variables, is_filename=True)
    if download_type == DownloadType.COVER.value and not file_base.endswith("_cover"):
        file_base = f"{file_base}_cover"

    preview_path = str(resolved_dir / f"{file_base}.{ext.lstrip('.') or 'mp4'}")
    output_dir_override = str(resolved_dir) if rel_dir_expr else ""
    return output_dir_override, file_base, preview_path


def quality_signature_for_rule(rule: DownloadRule, download_type: str) -> str:
    q = rule.quality
    if download_type == DownloadType.AUDIO_ONLY.value:
        return f"audio:{q.audio_format}:{q.audio_bitrate_kbps}"
    if download_type in (DownloadType.IMAGE.value, DownloadType.IMAGE_ZIP.value, DownloadType.COVER.value, DownloadType.GIF.value):
        return download_type.lower()
    if q.strategy == "target_resolution" and q.resolution > 0:
        return f"{q.resolution}p|{q.codec}|{q.min_fps}|{q.dynamic_range}"
    return f"best|{q.codec}|{q.min_fps}|{q.dynamic_range}"


def build_rule_snapshot(
    rule: DownloadRule,
    *,
    trigger: str,
    quality_desc: str,
    planned_path: str,
    dedup_key: str,
    warnings: List[str],
) -> Dict[str, Any]:
    """生成写入任务的不可变规则参数快照，后续修改或删除规则均不影响该任务。"""
    return copy.deepcopy(
        {
            "ruleId": rule.id,
            "ruleName": rule.name,
            "schemaVersion": 1,
            "trigger": trigger,
            "appliedAt": int(time.time() * 1000),
            "qualityDesc": quality_desc,
            "plannedPath": planned_path,
            "duplicatePolicy": rule.files.duplicate_policy.value,
            "dedupKey": dedup_key,
            "warnings": list(warnings),
        }
    )
