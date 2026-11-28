"""Omni Download Rules 核心执行管线：解析前匹配 -> 媒体解析 -> 解析后筛选 -> 计划生成 -> 去重校验 -> 任务入队。"""

from __future__ import annotations

import copy
import re
import time
from typing import Any, Awaitable, Callable, Dict, List, Optional, Set, Tuple

from core.models import AudioFormat, DownloadType, FormatOption, VideoMetadata
from core.rules.matcher import (
    MediaMatchContext,
    filter_collection_items,
    match_rule_post_parse,
    match_rule_pre_parse,
    parse_duration_seconds,
)
from core.rules.planner import (
    build_output_plan,
    build_rule_snapshot,
    quality_signature_for_rule,
    resolve_quality_for_formats,
)
from core.rules.schema import (
    DownloadRule,
    RuleExecutionAction,
    RuleTrigger,
)
from core.rules.store import compute_dedup_key, rule_store
from engine.url_sniffer import UrlSniffer


AnalyzeCallable = Callable[[str], Awaitable[Dict[str, Any]]]


def _classify_error_code(error: Exception) -> str:
    detail = str(getattr(error, "detail", error) or "")
    for tag in ("AUTH_REQUIRED", "CHALLENGE_REQUIRED", "ACCESS_DENIED", "PREVIEW_ONLY", "MEDIA_UNCONFIRMED", "CONTENT_UNAVAILABLE", "QUALITY_UNAVAILABLE"):
        if tag in detail:
            return tag
    return "ERROR"


class RuleEngine:
    """规则评估与下载计划编排引擎（无状态依赖循环，可独立测试与 Dry-Run 预览）。"""

    @staticmethod
    def _candidate_rules(trigger: RuleTrigger, rule_id: str = "") -> List[DownloadRule]:
        rules = rule_store.list_rules()
        if rule_id:
            target = next((r for r in rules if r.id == rule_id), None)
            return [target] if target else []
        return [r for r in rules if r.enabled]

    @classmethod
    async def evaluate_url(
        cls,
        raw_url: str,
        *,
        trigger: RuleTrigger,
        analyze_media_fn: AnalyzeCallable,
        rule_id: str = "",
        rule_mode: str = "auto",  # auto | specific | bypass
        active_task_ids: Optional[Set[str]] = None,
        dry_run: bool = False,
        record_audit: bool = True,
        fallback_options: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """
        执行完整管道或 Dry-Run 预览。
        当规则系统禁用、rule_mode='bypass' 或没有任何规则匹配时，返回 matched=False，交由原有流程处理。
        """
        settings = rule_store.get_settings()
        if rule_mode == "bypass" or (not dry_run and not settings.rules_system_enabled):
            return {
                "matched": False,
                "bypassed": True,
                "reason": "规则系统未开启或已指定跳过规则",
                "evaluations": [],
                "plannedTasks": [],
            }

        extracted_url = UrlSniffer.extract_url(raw_url) or (raw_url or "").strip()
        if not extracted_url:
            raise ValueError("链接不能为空")

        candidates = cls._candidate_rules(trigger, rule_id=rule_id if rule_mode == "specific" or rule_id else "")
        if not candidates:
            return {
                "matched": False,
                "bypassed": False,
                "reason": "当前无已启用且匹配的规则" if not rule_id else f"未找到指定规则 ({rule_id})",
                "evaluations": [],
                "plannedTasks": [],
            }

        platform_hint = UrlSniffer.identify_site(extracted_url)
        evaluations: List[Dict[str, Any]] = []
        pre_passed: List[DownloadRule] = []

        # === 第 1 步：解析前匹配 ===
        for r in candidates:
            # 当用户显式指定具体规则时，允许忽略其 enabled/trigger 限制仅用于预览/显式执行，但仍提示原因
            if rule_id and rule_mode == "specific":
                pre_passed.append(r)
                evaluations.append({
                    "ruleId": r.id,
                    "ruleName": r.name,
                    "priority": r.priority,
                    "preMatched": True,
                    "preReason": "已由用户显式指定规则",
                    "postMatched": None,
                    "reasons": [],
                })
                continue

            ok_pre, pre_reason = match_rule_pre_parse(r, trigger, extracted_url, platform_hint)
            evaluations.append({
                "ruleId": r.id,
                "ruleName": r.name,
                "priority": r.priority,
                "preMatched": ok_pre,
                "preReason": pre_reason,
                "postMatched": None,
                "reasons": [] if ok_pre else [pre_reason],
            })
            if ok_pre:
                pre_passed.append(r)

        if not pre_passed:
            return {
                "matched": False,
                "bypassed": False,
                "reason": "未通过解析前条件匹配（域名/URL/平台或触发入口不匹配）",
                "evaluations": evaluations,
                "plannedTasks": [],
            }

        # === 第 2 步：媒体解析（复用了现有媒体解析入口）===
        try:
            parsed = await analyze_media_fn(extracted_url)
        except Exception as exc:
            err_code = _classify_error_code(exc)
            detail = str(getattr(exc, "detail", exc) or "")
            is_auth_block = err_code in ("AUTH_REQUIRED", "CHALLENGE_REQUIRED", "ACCESS_DENIED", "MEDIA_UNCONFIRMED", "CONTENT_UNAVAILABLE")
            audit_status = "blocked_auth" if is_auth_block else "failed"
            if record_audit and not dry_run:
                rule_store.append_audit(
                    trigger=trigger.value,
                    url=extracted_url,
                    platform=platform_hint,
                    rule_id=pre_passed[0].id,
                    rule_name=pre_passed[0].name,
                    status=audit_status,
                    reason=detail[:300],
                )
            return {
                "matched": True,
                "matchedRule": pre_passed[0].to_safe_export_dict(),
                "error": detail,
                "errorCode": err_code,
                "blockedByAuthOrSource": is_auth_block,
                "status": audit_status,
                "evaluations": evaluations,
                "plannedTasks": [],
            }

        kind = parsed.get("kind", "video")
        data = parsed.get("data")
        ctx = cls._build_context(trigger, extracted_url, kind, data, fallback_platform=platform_hint)

        # === 第 3 步：解析后完整匹配 ===
        matched_rule: Optional[DownloadRule] = None
        post_warnings: List[str] = []

        eval_map = {e["ruleId"]: e for e in evaluations}
        for r in pre_passed:
            if rule_id and rule_mode == "specific" and not r.enabled:
                # 显式强制指定了某规则执行测试/单次下载时仍允许继续校验匹配条件
                temp_r = r.model_copy(deep=True)
                temp_r.enabled = True
                if ctx.trigger not in temp_r.triggers:
                    temp_r.triggers.append(ctx.trigger)
                ok_post, failures, warns = match_rule_post_parse(temp_r, ctx)
            else:
                ok_post, failures, warns = match_rule_post_parse(r, ctx)

            ev = eval_map.get(r.id)
            if ev is not None:
                ev["postMatched"] = ok_post
                ev["reasons"] = failures if not ok_post else []
                ev["warnings"] = warns

            if ok_post and matched_rule is None:
                matched_rule = r
                post_warnings = list(warns)
                if not dry_run:
                    break

        if matched_rule is None:
            if record_audit and not dry_run and trigger == RuleTrigger.CLIPBOARD:
                # 剪贴板仅针对命中的规则运行；不完整命中也不污染常规历史
                pass
            elif record_audit and not dry_run and rule_id:
                rule_store.append_audit(
                    trigger=trigger.value,
                    url=ctx.url,
                    platform=ctx.platform,
                    title=ctx.title,
                    rule_id=rule_id,
                    rule_name=pre_passed[0].name if pre_passed else "",
                    status="unmatched",
                    reason="; ".join(eval_map[pre_passed[0].id].get("reasons", [])) if pre_passed else "规则未匹配",
                )
            return {
                "matched": False,
                "bypassed": False,
                "context": {
                    "url": ctx.url,
                    "platform": ctx.platform,
                    "mediaType": ctx.media_type,
                    "title": ctx.title,
                    "author": ctx.author,
                    "durationSec": ctx.duration_sec,
                    "isCollection": ctx.is_collection,
                },
                "reason": "媒体解析完成，但未完整匹配任何已启用规则",
                "evaluations": evaluations,
                "plannedTasks": [],
            }

        # === 第 4 & 5 步：下载计划生成 + 合集筛选 + 跨入口去重校验 ===
        plan_res = cls._build_download_plan(
            matched_rule,
            trigger=trigger,
            kind=kind,
            data=data,
            ctx=ctx,
            active_task_ids=active_task_ids,
            base_warnings=post_warnings,
            client_source=(fallback_options or {}).get("clientSource", ""),
        )
        plan_res["evaluations"] = evaluations
        plan_res["parsed"] = parsed
        plan_res["matchedRule"] = matched_rule.to_safe_export_dict()
        plan_res["ruleObj"] = matched_rule
        return plan_res

    @staticmethod
    def _build_context(
        trigger: RuleTrigger,
        original_url: str,
        kind: str,
        data: Any,
        *,
        fallback_platform: str = "",
    ) -> MediaMatchContext:
        if kind == "images" and isinstance(data, dict):
            resources = data.get("resources") or []
            return MediaMatchContext(
                trigger=trigger,
                original_url=original_url,
                url=data.get("url") or original_url,
                platform=data.get("platform") or fallback_platform or UrlSniffer.identify_site(original_url),
                media_type="images",
                title=data.get("title") or "",
                author=data.get("author") or "",
                duration_sec=0,
                is_collection=len(resources) > 1,
                media_id=str(data.get("id") or data.get("mediaId") or ""),
            )

        meta: VideoMetadata = data
        is_gif = bool(getattr(meta, "is_gif", False))
        media_type = "gif" if is_gif else "video"
        duration_sec = parse_duration_seconds(
            getattr(meta, "duration_text", ""),
            int(getattr(meta, "expected_duration_ms", 0) or 0),
        )
        multi = getattr(meta, "multi_media_list", None) or []
        return MediaMatchContext(
            trigger=trigger,
            original_url=original_url,
            url=getattr(meta, "url", "") or original_url,
            platform=getattr(meta, "site_name", "") or fallback_platform or UrlSniffer.identify_site(original_url),
            media_type=media_type,
            title=getattr(meta, "title", "") or "",
            author=getattr(meta, "author", "") or "",
            duration_sec=duration_sec,
            is_collection=len(multi) > 1,
            media_id=getattr(meta, "media_id", "") or "",
        )

    @classmethod
    def _build_download_plan(
        cls,
        rule: DownloadRule,
        *,
        trigger: RuleTrigger,
        kind: str,
        data: Any,
        ctx: MediaMatchContext,
        active_task_ids: Optional[Set[str]] = None,
        base_warnings: Optional[List[str]] = None,
        client_source: str = "",
    ) -> Dict[str, Any]:
        warnings = list(base_warnings or [])
        action = rule.execution.action

        if action == RuleExecutionAction.IGNORE:
            return {
                "matched": True,
                "action": "ignore",
                "status": "ignored",
                "reason": f"规则「{rule.name}」策略为忽略（不解析下载队列）",
                "warnings": warnings,
                "plannedTasks": [],
                "skippedItems": [],
                "dedupSkipped": [],
                "context": ctx.__dict__,
            }

        planned_tasks: List[Dict[str, Any]] = []
        skipped_items: List[Dict[str, Any]] = []
        dedup_skipped: List[Dict[str, Any]] = []
        quality_summary = ""

        if kind == "images" and isinstance(data, dict):
            resources = data.get("resources") or []
            ids = [str(r.get("id")) for r in resources if r.get("id") is not None]
            if not ids:
                return {
                    "matched": True,
                    "action": action.value,
                    "status": "failed",
                    "error": "未从目标图文页解析出可用图片资源",
                    "warnings": warnings,
                    "plannedTasks": [],
                    "skippedItems": [],
                    "dedupSkipped": [],
                    "context": ctx.__dict__,
                }

            dtype = DownloadType.IMAGE.value if (
                rule.download.mode == DownloadType.IMAGE.value
                or (rule.download.mode not in (DownloadType.IMAGE.value, DownloadType.IMAGE_ZIP.value) and rule.download.image_archive_mode == "original")
            ) else DownloadType.IMAGE_ZIP.value
            if rule.download.mode not in (DownloadType.IMAGE.value, DownloadType.IMAGE_ZIP.value):
                warnings.append(f"目标为图文作品，已自动采用规则图文模式 ({dtype})")

            groups = [[gid] for gid in ids] if dtype == DownloadType.IMAGE.value else [ids]
            max_items = max(1, min(int(rule.execution.max_tasks or 100), 100))
            if len(groups) > max_items:
                warnings.append(f"图文数量 ({len(groups)}) 超过规则单次上限 ({max_items})，已截断处理")
                groups = groups[:max_items]

            q_sig = quality_signature_for_rule(rule, dtype)
            ext = "zip" if dtype == DownloadType.IMAGE_ZIP.value else "jpg"
            col_title = data.get("title") or "" if len(groups) > 1 else ""
            media_id_base = str(data.get("id") or data.get("url") or ctx.url)

            for idx, group in enumerate(groups, start=1):
                ep_id = group[0] if dtype == DownloadType.IMAGE.value else "zip"
                ep_title = f"{data.get('title') or '图文'}_{idx}" if len(groups) > 1 else (data.get("title") or "图文")
                dkey = compute_dedup_key(
                    ctx.platform,
                    media_id_base,
                    ep_id,
                    dtype,
                    q_sig,
                    ext,
                    fallback_url=data.get("url") or ctx.url,
                )
                hit = rule_store.check_duplicate(dkey, active_task_ids=active_task_ids)
                if hit is not None:
                    dedup_skipped.append({"index": idx, "title": ep_title, "reason": "命中跨入口去重索引（本地已有或已在队列中）", "dedupKey": dkey})
                    continue

                out_dir, fname_base, preview_path = build_output_plan(
                    rule,
                    download_type=dtype,
                    platform=ctx.platform,
                    title=ep_title,
                    author=ctx.author,
                    media_id=media_id_base,
                    collection_title=col_title,
                    episode_index=idx if len(groups) > 1 else 0,
                    resolution_label="原图" if dtype == DownloadType.IMAGE.value else "ZIP",
                    ext=ext,
                )
                snap = build_rule_snapshot(
                    rule,
                    trigger=trigger.value,
                    quality_desc=dtype,
                    planned_path=preview_path,
                    dedup_key=dkey,
                    warnings=warnings,
                )
                planned_tasks.append({
                    "url": data.get("url") or ctx.url,
                    "title": ep_title,
                    "author": ctx.author,
                    "thumbnailUrl": resources[0].get("url", "") if resources else "",
                    "downloadType": dtype,
                    "imagePlatform": data.get("platform") or ctx.platform,
                    "selectedImageIds": group,
                    "mediaId": f"{media_id_base}:{ep_id}",
                    "collectionId": f"rule-{rule.id}-{media_id_base}" if len(groups) > 1 else None,
                    "collectionTitle": col_title or None,
                    "episodeIndex": idx,
                    "episodeTotal": len(groups),
                    "clientSource": client_source or f"rule:{trigger.value}",
                    "outputDirOverride": out_dir,
                    "filenameOverride": fname_base,
                    "duplicatePolicy": rule.files.duplicate_policy.value,
                    "ruleSnapshot": snap,
                    "dedupKey": dkey,
                    "plannedPath": preview_path,
                    "platform": ctx.platform,
                    "outputFormat": ext,
                    "qualitySignature": q_sig,
                })
            quality_summary = f"图文 ({dtype})"
        else:
            meta: VideoMetadata = data
            dtype = rule.download.mode
            if dtype in (DownloadType.IMAGE.value, DownloadType.IMAGE_ZIP.value):
                dtype = DownloadType.VIDEO_WITH_AUDIO.value
                warnings.append("目标为视频/动态媒体，规则原图模式已自动适配为视频下载")

            multi = list(getattr(meta, "multi_media_list", None) or [])
            is_multi = len(multi) > 1

            # 对于多集作品，在合集/批量/剪贴板/或规则声明合集模式时支持筛选多集；
            # 若仅单个视频或仅当前分集，按单个条目处理
            if is_multi:
                def _check_ep_downloaded(ep_idx: int, ep_item: VideoMetadata) -> Tuple[bool, str]:
                    ep_mid = ep_item.media_id or ep_item.url
                    q_s = quality_signature_for_rule(rule, dtype)
                    o_ext = rule.quality.audio_format if dtype == DownloadType.AUDIO_ONLY.value else (
                        "gif" if dtype == DownloadType.GIF.value else (
                            "jpg" if dtype == DownloadType.COVER.value else (rule.quality.container if rule.quality.container != "auto" else "mp4")
                        )
                    )
                    dk = compute_dedup_key(
                        ctx.platform,
                        meta.media_id or meta.url,
                        ep_mid,
                        dtype,
                        q_s,
                        o_ext,
                        fallback_url=ep_item.url,
                    )
                    hit_rec = rule_store.check_duplicate(dk, active_task_ids=active_task_ids)
                    return (hit_rec is not None, "分集已下载或已存在队列中" if hit_rec else "")

                selected_pairs, col_skipped = filter_collection_items(
                    multi,
                    rule,
                    is_item_downloaded=_check_ep_downloaded,
                )
                skipped_items.extend(col_skipped)
            else:
                selected_pairs = [(1, meta)]

            max_tasks = max(1, min(int(rule.execution.max_tasks or 100), 100))
            if len(selected_pairs) > max_tasks:
                for extra_idx, extra_ep in selected_pairs[max_tasks:]:
                    skipped_items.append({
                        "index": extra_idx,
                        "title": getattr(extra_ep, "title", ""),
                        "reason": f"超过规则最大任务限制 ({max_tasks})",
                    })
                warnings.append(f"筛选命中 {len(selected_pairs)} 项，已按规则上限限制为前 {max_tasks} 项")
                selected_pairs = selected_pairs[:max_tasks]

            # 解析可用格式列表做画质评估
            sample_formats: List[FormatOption] = []
            for _, ep_obj in selected_pairs:
                if getattr(ep_obj, "available_video_formats", None):
                    sample_formats = list(ep_obj.available_video_formats)
                    break
            if not sample_formats:
                sample_formats = list(getattr(meta, "available_video_formats", None) or [])

            try:
                quality_sel, picked_fmt, q_state, q_warns = resolve_quality_for_formats(rule, sample_formats)
                warnings.extend(q_warns)
            except ValueError as q_err:
                return {
                    "matched": True,
                    "action": action.value,
                    "status": "failed",
                    "error": str(q_err),
                    "warnings": warnings,
                    "plannedTasks": [],
                    "skippedItems": skipped_items,
                    "dedupSkipped": dedup_skipped,
                    "context": ctx.__dict__,
                }

            res_label = picked_fmt.resolution_label if picked_fmt else (
                f"{rule.quality.resolution}p" if rule.quality.resolution > 0 else "最佳可用"
            )
            quality_summary = (
                f"{rule.quality.audio_format.upper()}"
                if dtype == DownloadType.AUDIO_ONLY.value
                else (res_label if dtype in (DownloadType.VIDEO_WITH_AUDIO.value, DownloadType.VIDEO_ONLY.value) else dtype)
            )
            q_sig = quality_signature_for_rule(rule, dtype)
            out_ext = (
                rule.quality.audio_format
                if dtype == DownloadType.AUDIO_ONLY.value
                else ("gif" if dtype == DownloadType.GIF.value else ("jpg" if dtype == DownloadType.COVER.value else (picked_fmt.ext if picked_fmt and rule.quality.container == "auto" else (rule.quality.container if rule.quality.container != "auto" else "mp4"))))
            )

            col_id = f"rule-{rule.id}-{(meta.media_id or ctx.url)[-12:]}" if is_multi else None
            col_title = meta.title if is_multi else ""

            for ep_idx, ep_obj in selected_pairs:
                ep_formats = list(getattr(ep_obj, "available_video_formats", None) or sample_formats)
                ep_fmt_id = ""
                if dtype in (DownloadType.VIDEO_WITH_AUDIO.value, DownloadType.VIDEO_ONLY.value) and ep_formats:
                    try:
                        _, ep_picked, _, _ = resolve_quality_for_formats(rule, ep_formats)
                        ep_fmt_id = ep_picked.format_id if ep_picked else ""
                    except Exception:
                        ep_fmt_id = picked_fmt.format_id if picked_fmt else ""

                ep_url = getattr(ep_obj, "url", "") or meta.url
                ep_title = getattr(ep_obj, "title", "") or meta.title
                ep_author = getattr(ep_obj, "author", "") or meta.author
                ep_thumb = getattr(ep_obj, "thumbnail_url", "") or meta.thumbnail_url
                ep_mid = getattr(ep_obj, "media_id", "") or ep_url

                dkey = compute_dedup_key(
                    ctx.platform,
                    meta.media_id or meta.url,
                    ep_mid if is_multi else "",
                    dtype,
                    q_sig,
                    out_ext,
                    fallback_url=ep_url,
                )
                hit = rule_store.check_duplicate(dkey, active_task_ids=active_task_ids)
                if hit is not None:
                    dedup_skipped.append({
                        "index": ep_idx,
                        "title": ep_title,
                        "reason": "命中跨入口去重索引（已存在下载记录或队列任务）",
                        "dedupKey": dkey,
                    })
                    continue

                out_dir, fname_base, preview_path = build_output_plan(
                    rule,
                    download_type=dtype,
                    platform=ctx.platform,
                    title=ep_title,
                    author=ep_author,
                    media_id=ep_mid,
                    collection_title=col_title,
                    episode_index=ep_idx if is_multi else 0,
                    resolution_label=res_label,
                    ext=out_ext,
                )
                snap = build_rule_snapshot(
                    rule,
                    trigger=trigger.value,
                    quality_desc=quality_summary,
                    planned_path=preview_path,
                    dedup_key=dkey,
                    warnings=warnings,
                )

                planned_tasks.append({
                    "url": ep_url,
                    "title": f"{meta.title} - {ep_title}" if is_multi and meta.title and meta.title not in ep_title else ep_title,
                    "author": ep_author,
                    "thumbnailUrl": ep_thumb,
                    "downloadType": dtype,
                    "selectedResolution": ep_fmt_id or "best",
                    "selectedVideoFormatId": ep_fmt_id if not is_multi else "",
                    "qualitySelection": quality_sel.model_dump(by_alias=True),
                    "audioFormat": rule.quality.audio_format,
                    "mediaId": getattr(ep_obj, "media_id", "") or "",
                    "collectionId": col_id,
                    "collectionTitle": col_title or None,
                    "episodeIndex": ep_idx if is_multi else 0,
                    "episodeTotal": len(multi) if is_multi else 0,
                    "clientSource": client_source or f"rule:{trigger.value}",
                    "outputDirOverride": out_dir,
                    "filenameOverride": fname_base,
                    "duplicatePolicy": rule.files.duplicate_policy.value,
                    "ruleSnapshot": snap,
                    "dedupKey": dkey,
                    "plannedPath": preview_path,
                    "platform": ctx.platform,
                    "outputFormat": out_ext,
                    "qualitySignature": q_sig,
                })

            # 附带封面任务
            if rule.download.save_cover and dtype != DownloadType.COVER.value:
                cover_url = meta.thumbnail_url or ctx.url
                if cover_url:
                    cov_dkey = compute_dedup_key(
                        ctx.platform,
                        meta.media_id or meta.url,
                        "cover",
                        DownloadType.COVER.value,
                        "cover",
                        "jpg",
                        fallback_url=cover_url,
                    )
                    if rule_store.check_duplicate(cov_dkey, active_task_ids=active_task_ids) is None:
                        cov_dir, cov_name, cov_preview = build_output_plan(
                            rule,
                            download_type=DownloadType.COVER.value,
                            platform=ctx.platform,
                            title=meta.title,
                            author=meta.author,
                            media_id=meta.media_id or meta.url,
                            collection_title=col_title,
                            ext="jpg",
                        )
                        planned_tasks.append({
                            "url": cover_url,
                            "title": f"{meta.title} - 封面",
                            "author": meta.author,
                            "thumbnailUrl": cover_url,
                            "downloadType": DownloadType.COVER.value,
                            "mediaId": meta.media_id or "",
                            "clientSource": client_source or f"rule:{trigger.value}",
                            "outputDirOverride": cov_dir,
                            "filenameOverride": cov_name,
                            "duplicatePolicy": rule.files.duplicate_policy.value,
                            "ruleSnapshot": build_rule_snapshot(
                                rule,
                                trigger=trigger.value,
                                quality_desc="COVER",
                                planned_path=cov_preview,
                                dedup_key=cov_dkey,
                                warnings=warnings,
                            ),
                            "dedupKey": cov_dkey,
                            "plannedPath": cov_preview,
                            "platform": ctx.platform,
                            "outputFormat": "jpg",
                            "qualitySignature": "cover",
                        })

        # 去重/合集检查结论状态汇总
        if not planned_tasks and (dedup_skipped or skipped_items):
            status = "dedup_skipped" if dedup_skipped else "skipped"
        elif action == RuleExecutionAction.PARSE_ONLY:
            status = "parse_only"
        elif action == RuleExecutionAction.CONFIRM:
            status = "confirm_required"
        else:
            status = "ready"

        return {
            "matched": True,
            "action": action.value,
            "status": status,
            "qualitySummary": quality_summary,
            "warnings": list(dict.fromkeys(warnings)),
            "plannedTasks": planned_tasks,
            "skippedItems": skipped_items,
            "dedupSkipped": dedup_skipped,
            "context": {
                "url": ctx.url,
                "platform": ctx.platform,
                "mediaType": ctx.media_type,
                "title": ctx.title,
                "author": ctx.author,
                "durationSec": ctx.duration_sec,
                "isCollection": ctx.is_collection,
            },
        }


rule_engine = RuleEngine()
