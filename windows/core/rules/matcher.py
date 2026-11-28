"""规则匹配与合集分集筛选引擎（不绑定硬编码平台白名单）。"""

from __future__ import annotations

import fnmatch
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set, Tuple
from urllib.parse import urlsplit

from core.rules.schema import DownloadRule, RuleTrigger


@dataclass
class MediaMatchContext:
    """统一封装解析前后的媒体匹配上下文（视频、音频、动图、图文统一适用）"""
    trigger: RuleTrigger
    original_url: str
    url: str
    platform: str
    media_type: str  # video | images | audio | gif
    title: str = ""
    author: str = ""
    duration_sec: int = 0
    is_collection: bool = False
    media_id: str = ""
    extra_urls: List[str] = field(default_factory=list)


def extract_domain(url: str) -> str:
    try:
        host = (urlsplit(url or "").hostname or "").strip().lower()
        return host
    except Exception:
        return ""


def parse_duration_seconds(duration_text: str = "", expected_duration_ms: int = 0) -> int:
    """统一将毫秒或 HH:MM:SS / MM:SS 文本解析为秒数；缺失则返回 0。"""
    if expected_duration_ms and expected_duration_ms > 0:
        return int(expected_duration_ms // 1000)
    text = (duration_text or "").strip()
    if not text:
        return 0
    parts = [p for p in text.split(":") if p.isdigit()]
    if len(parts) == 2:
        return int(parts[0]) * 60 + int(parts[1])
    if len(parts) == 3:
        return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
    m = re.search(r"(\d+)\s*(?:s|秒)", text, re.IGNORECASE)
    return int(m.group(1)) if m else 0


def parse_episode_tokens(spec: str) -> Set[int]:
    """解析分集表达式（如 '1,3,5-8,P10' -> {1, 3, 5, 6, 7, 8, 10}）。"""
    result: Set[int] = set()
    if not spec or not spec.strip():
        return result
    tokens = re.split(r"[,，;\s]+", spec.strip())
    for tok in tokens:
        tok = re.sub(r"^[pPeE]+", "", tok.strip())
        if not tok:
            continue
        if "-" in tok:
            parts = tok.split("-", 1)
            if parts[0].isdigit() and parts[1].isdigit():
                a, b = int(parts[0]), int(parts[1])
                lo, hi = min(a, b), max(a, b)
                for n in range(max(1, lo), min(hi, 10000) + 1):
                    result.add(n)
        elif tok.isdigit():
            n = int(tok)
            if n >= 1:
                result.add(n)
    return result


def _match_platform_token(expected: str, actual: str) -> bool:
    e = re.sub(r"\s+", "", (expected or "")).lower()
    a = re.sub(r"\s+", "", (actual or "")).lower()
    if not e or not a:
        return False
    if e == a:
        return True
    if len(e) >= 2 and (e in a or a in e):
        return True
    return False


def _match_domain(rule_domains: List[str], urls: List[str]) -> bool:
    if not rule_domains:
        return True
    hosts = [extract_domain(u) for u in urls if u]
    hosts = [h for h in hosts if h]
    if not hosts:
        return False
    for raw_dom in rule_domains:
        dom = (raw_dom or "").strip().lower().lstrip(".")
        if not dom:
            continue
        for h in hosts:
            if h == dom or h.endswith("." + dom):
                return True
    return False


def _match_url_patterns(patterns: List[str], mode: str, urls: List[str]) -> bool:
    clean_patterns = [p.strip() for p in patterns if (p or "").strip()]
    if not clean_patterns:
        return True
    for pat in clean_patterns:
        for u in urls:
            if not u:
                continue
            if mode == "regex":
                try:
                    if re.search(pat, u, re.IGNORECASE):
                        return True
                except re.error:
                    continue
            elif mode == "wildcard":
                if fnmatch.fnmatch(u.lower(), pat.lower()):
                    return True
            else:
                if pat.lower() in u.lower():
                    return True
    return False


def match_rule_pre_parse(
    rule: DownloadRule,
    trigger: RuleTrigger,
    url: str,
    platform_hint: str = "",
) -> Tuple[bool, str]:
    """
    阶段一：解析前预匹配。
    可提前跳过触发器不符、已禁用或明显不匹配域名/URL/已知站点的高成本解析操作。
    """
    if not rule.enabled:
        return False, "规则已禁用"
    if trigger not in rule.triggers:
        return False, f"规则未包含触发入口 {trigger.value}"

    urls = [url]
    if rule.match.domains and not _match_domain(rule.match.domains, urls):
        # 对常见短链域名（如 b23.tv / t.co / xhslink.com），域名提示若已识别出同一平台也给予候选放行等待重定向解析
        hint_match = (
            platform_hint
            and platform_hint != "网络视频"
            and rule.match.platforms
            and any(_match_platform_token(p, platform_hint) for p in rule.match.platforms)
        )
        if not hint_match:
            return False, "解析前域名不匹配"

    if rule.match.url_patterns and not _match_url_patterns(rule.match.url_patterns, rule.match.url_match_mode, urls):
        # 若只是域名/短链重定向前不一致且可能还原，按严格模式还是只针对有正则/关键词 URL 判定：
        # 如果没有任何未知短链，直接前置排除；否则留待解析后 canonical URL 再核一次
        host = extract_domain(url)
        short_hosts = {"b23.tv", "v.douyin.com", "v.kuaishou.com", "xhslink.com", "xhslink.cn", "t.co", "vm.tiktok.com", "vt.tiktok.com", "pin.it", "fb.watch", "fb.me", "t.cn"}
        if host not in short_hosts:
            return False, "解析前 URL 模式不匹配"

    if rule.match.platforms and platform_hint and platform_hint != "网络视频":
        if not any(_match_platform_token(p, platform_hint) for p in rule.match.platforms):
            return False, f"解析前平台不匹配（当前：{platform_hint}）"

    return True, "通过解析前候选筛选"


def match_rule_post_parse(
    rule: DownloadRule,
    ctx: MediaMatchContext,
) -> Tuple[bool, List[str], List[str]]:
    """
    阶段三：解析后完整元数据匹配。
    返回 (是否完整匹配, 不匹配原因列表, 缺失元数据提示/警告列表)。
    """
    failures: List[str] = []
    warnings: List[str] = []

    if not rule.enabled:
        failures.append("规则已禁用")
        return False, failures, warnings

    if ctx.trigger not in rule.triggers:
        failures.append(f"未配置触发入口 {ctx.trigger.value}")

    m = rule.match
    all_urls = [u for u in [ctx.url, ctx.original_url, *ctx.extra_urls] if u]

    if m.platforms:
        if not ctx.platform:
            failures.append("解析结果未提供平台名称，无法满足平台筛选条件")
        elif not any(_match_platform_token(p, ctx.platform) for p in m.platforms):
            failures.append(f"平台不匹配（期望：{', '.join(m.platforms)}，实际：{ctx.platform}）")

    if m.domains and not _match_domain(m.domains, all_urls):
        failures.append(f"域名不匹配（实际：{extract_domain(ctx.url) or '未知'}）")

    if m.url_patterns and not _match_url_patterns(m.url_patterns, m.url_match_mode, all_urls):
        failures.append("URL 不满足规则匹配表达式")

    if m.media_types:
        if ctx.media_type not in m.media_types:
            failures.append(f"内容类型不匹配（期望：{', '.join(m.media_types)}，实际：{ctx.media_type}）")

    title_lower = (ctx.title or "").lower()
    if m.title_keywords:
        clean_kw = [k.strip() for k in m.title_keywords if (k or "").strip()]
        if clean_kw and not any(k.lower() in title_lower for k in clean_kw):
            failures.append("标题未命中任何必需关键词")

    if m.title_exclude_keywords:
        hit_ex = [k.strip() for k in m.title_exclude_keywords if (k or "").strip() and k.strip().lower() in title_lower]
        if hit_ex:
            failures.append(f"标题命中排除关键词：{', '.join(hit_ex)}")

    if m.title_regex:
        try:
            if not re.search(m.title_regex, ctx.title or "", re.IGNORECASE):
                failures.append("标题未匹配正则表达式")
        except re.error:
            failures.append("标题正则表达式无效")

    author_lower = (ctx.author or "").lower()
    if m.author_keywords:
        clean_ak = [k.strip() for k in m.author_keywords if (k or "").strip()]
        if clean_ak:
            if not ctx.author:
                failures.append("缺失作者元数据，无法满足作者筛选条件")
            elif not any(k.lower() in author_lower for k in clean_ak):
                failures.append(f"作者不匹配（实际：{ctx.author}）")

    if m.author_exclude_keywords:
        clean_aex = [k.strip() for k in m.author_exclude_keywords if (k or "").strip()]
        if clean_aex:
            if not ctx.author:
                warnings.append("平台未返回作者信息，已跳过作者排除检查")
            else:
                hit_aex = [k for k in clean_aex if k.lower() in author_lower]
                if hit_aex:
                    failures.append(f"作者命中排除词：{', '.join(hit_aex)}")

    if m.min_duration_sec > 0 or m.max_duration_sec > 0:
        if ctx.media_type == "images":
            warnings.append("图文内容无视频时长属性，已跳过时长范围检查")
        elif ctx.duration_sec <= 0:
            if m.min_duration_sec > 0:
                failures.append("缺失时长元数据，未满足最小时长限制")
            else:
                warnings.append("平台未提供视频时长，已跳过最大时长限制")
        else:
            if m.min_duration_sec > 0 and ctx.duration_sec < m.min_duration_sec:
                failures.append(f"视频时长过短（{ctx.duration_sec}s < {m.min_duration_sec}s）")
            if m.max_duration_sec > 0 and ctx.duration_sec > m.max_duration_sec:
                failures.append(f"视频时长过长（{ctx.duration_sec}s > {m.max_duration_sec}s）")

    return len(failures) == 0, failures, warnings


def filter_collection_items(
    items: List[Any],
    rule: DownloadRule,
    *,
    is_item_downloaded: Optional[Callable[[int, Any], Tuple[bool, str]]] = None,
) -> Tuple[List[Tuple[int, Any]], List[Dict[str, Any]]]:
    """
    合集/分集筛选：返回 ([(1-based序号, item), ...], skipped_details)。
    支持：all / episodes / range / keywords 以及 skipDownloaded。
    """
    col = rule.collection
    selected: List[Tuple[int, Any]] = []
    skipped: List[Dict[str, Any]] = []

    target_indices = parse_episode_tokens(col.episodes) if col.mode == "episodes" else set()
    inc_kws = [k.strip().lower() for k in col.include_keywords if (k or "").strip()]
    exc_kws = [k.strip().lower() for k in col.exclude_keywords if (k or "").strip()]

    for idx, ep in enumerate(items, start=1):
        ep_title = str(getattr(ep, "title", "") or (ep.get("title", "") if isinstance(ep, dict) else ""))
        ep_title_l = ep_title.lower()

        if col.mode == "episodes" and target_indices and idx not in target_indices:
            skipped.append({"index": idx, "title": ep_title, "reason": "未在指定分集列表内"})
            continue

        if col.mode == "range":
            if col.range_start > 0 and idx < col.range_start:
                skipped.append({"index": idx, "title": ep_title, "reason": f"分集序号小于起始范围 ({col.range_start})"})
                continue
            if col.range_end > 0 and idx > col.range_end:
                skipped.append({"index": idx, "title": ep_title, "reason": f"分集序号超过结束范围 ({col.range_end})"})
                continue

        if col.mode == "keywords" or inc_kws or exc_kws:
            if inc_kws and not any(k in ep_title_l for k in inc_kws):
                skipped.append({"index": idx, "title": ep_title, "reason": "分集标题未命中包含关键词"})
                continue
            hit_exc = [k for k in exc_kws if k in ep_title_l]
            if hit_exc:
                skipped.append({"index": idx, "title": ep_title, "reason": f"分集标题命中排除词 ({', '.join(hit_exc)})"})
                continue

        if col.skip_downloaded and is_item_downloaded is not None:
            done, dup_reason = is_item_downloaded(idx, ep)
            if done:
                skipped.append({"index": idx, "title": ep_title, "reason": dup_reason or "跳过已下载分集"})
                continue

        selected.append((idx, ep))

    return selected, skipped
