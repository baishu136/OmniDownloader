"""Omni Download Rules (v1) 跨平台统一规则模型、Schema 校验与安全清洗模块。"""

from __future__ import annotations

import json
import os
import re
import time
import uuid
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Any, Dict, List, Literal, Optional, Tuple
from pydantic import BaseModel, Field, field_validator, model_validator


RULE_SCHEMA_VERSION = 1
RULE_BUNDLE_FORMAT = "omni-download-rules"
MAX_IMPORT_BYTES = 1024 * 1024
MAX_RULES_COUNT = 100

WINDOWS_RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}

SENSITIVE_KEY_PATTERN = re.compile(
    r"(cookie|cookies|token|access_?token|refresh_?token|auth|authorization|secret|password|passwd|credential|sessdata|bili_jct|session_?id|api_?key)",
    re.IGNORECASE,
)
SENSITIVE_VALUE_PATTERN = re.compile(
    r"(SESSDATA=|bili_jct=|DedeUserID=|Bearer\s+[A-Za-z0-9._\-]{8,}|Cookie\s*:|Set-Cookie\s*:)",
    re.IGNORECASE,
)
UNSAFE_COMMAND_PATTERN = re.compile(
    r"(?:\$\([^)]*\)|`[^`]*`|\|\||&&|;\s*(?:rm|del|cmd|powershell|pwsh|bash|sh|python)\b)",
    re.IGNORECASE,
)


class RuleTrigger(str, Enum):
    """规则触发入口枚举"""
    MANUAL = "manual"
    COLLECTION = "collection"
    BATCH = "batch"
    CLIPBOARD = "clipboard"


class RuleExecutionAction(str, Enum):
    """命中规则后的执行动作"""
    AUTO_DOWNLOAD = "auto_download"
    PARSE_ONLY = "parse_only"
    CONFIRM = "confirm"
    IGNORE = "ignore"


class RuleFallbackStrategy(str, Enum):
    """画质/编码不支持时的回退策略"""
    BEST_AVAILABLE = "best_available"
    CLOSEST_LOWER = "closest_lower"
    EXACT_OR_FAIL = "exact_or_fail"


class RuleDuplicatePolicy(str, Enum):
    """本地重名文件处理策略"""
    RENAME = "rename"
    SKIP = "skip"
    OVERWRITE = "overwrite"


class RuleMatchConfig(BaseModel):
    """规则匹配条件（解析前可过滤域名/URL，解析后校验完整元数据）"""
    platforms: List[str] = Field(default_factory=list)
    domains: List[str] = Field(default_factory=list)
    url_patterns: List[str] = Field(default_factory=list, alias="urlPatterns")
    url_match_mode: Literal["contains", "regex", "wildcard"] = Field("contains", alias="urlMatchMode")
    media_types: List[Literal["video", "images", "audio", "gif"]] = Field(default_factory=list, alias="mediaTypes")
    title_keywords: List[str] = Field(default_factory=list, alias="titleKeywords")
    title_exclude_keywords: List[str] = Field(default_factory=list, alias="titleExcludeKeywords")
    title_regex: str = Field("", alias="titleRegex")
    author_keywords: List[str] = Field(default_factory=list, alias="authorKeywords")
    author_exclude_keywords: List[str] = Field(default_factory=list, alias="authorExcludeKeywords")
    min_duration_sec: int = Field(0, ge=0, le=864000, alias="minDurationSec")
    max_duration_sec: int = Field(0, ge=0, le=864000, alias="maxDurationSec")

    model_config = {"populate_by_name": True}

    @field_validator("url_patterns", "title_regex")
    @classmethod
    def _validate_regex_safety(cls, value: Any) -> Any:
        if isinstance(value, str):
            if len(value) > 512:
                raise ValueError("匹配表达式长度不能超过 512 字符")
            return value
        if isinstance(value, list):
            if len(value) > 50:
                raise ValueError("单条规则的 URL 模式数量不能超过 50")
            for item in value:
                if len(str(item)) > 512:
                    raise ValueError("URL 模式长度不能超过 512 字符")
        return value

    @model_validator(mode="after")
    def _check_patterns(self) -> "RuleMatchConfig":
        if self.url_match_mode == "regex":
            for pattern in self.url_patterns:
                if pattern:
                    try:
                        re.compile(pattern)
                    except re.error as exc:
                        raise ValueError(f"urlPatterns 正则语法错误: {exc}") from None
        if self.title_regex:
            try:
                re.compile(self.title_regex)
            except re.error as exc:
                raise ValueError(f"titleRegex 正则语法错误: {exc}") from None
        if self.max_duration_sec > 0 and self.min_duration_sec > self.max_duration_sec:
            raise ValueError("最小时长不能大于最大时长")
        return self


class RuleCollectionConfig(BaseModel):
    """合集/分P筛选配置"""
    mode: Literal["all", "episodes", "range", "keywords"] = "all"
    episodes: str = ""
    range_start: int = Field(0, ge=0, le=10000, alias="rangeStart")
    range_end: int = Field(0, ge=0, le=10000, alias="rangeEnd")
    include_keywords: List[str] = Field(default_factory=list, alias="includeKeywords")
    exclude_keywords: List[str] = Field(default_factory=list, alias="excludeKeywords")
    skip_downloaded: bool = Field(True, alias="skipDownloaded")

    model_config = {"populate_by_name": True}

    @model_validator(mode="after")
    def _validate_range(self) -> "RuleCollectionConfig":
        if self.range_start > 0 and self.range_end > 0 and self.range_start > self.range_end:
            raise ValueError("合集起始分集不能大于结束分集")
        return self


class RuleDownloadConfig(BaseModel):
    """下载产物与导出类型参数"""
    mode: Literal[
        "VIDEO_WITH_AUDIO",
        "VIDEO_ONLY",
        "AUDIO_ONLY",
        "GIF",
        "COVER",
        "IMAGE",
        "IMAGE_ZIP",
    ] = "VIDEO_WITH_AUDIO"
    save_cover: bool = Field(False, alias="saveCover")
    image_archive_mode: Literal["original", "zip"] = Field("zip", alias="imageArchiveMode")

    model_config = {"populate_by_name": True}


class RuleQualityConfig(BaseModel):
    """质量选择、编码/帧率/HDR 偏好与回退策略"""
    strategy: Literal["best", "target_resolution"] = "best"
    resolution: int = Field(0, ge=0, le=8640)
    codec: Literal["auto", "av01", "hev1", "avc1", "vp9", "h264", "hevc", "av1"] = "auto"
    min_fps: int = Field(0, ge=0, le=240, alias="minFps")
    dynamic_range: Literal["auto", "HDR", "SDR", "hdr", "sdr"] = Field("auto", alias="dynamicRange")
    container: Literal["mp4", "mkv", "webm", "auto"] = "mp4"
    audio_format: Literal["mp3", "m4a", "flac", "opus"] = Field("mp3", alias="audioFormat")
    audio_bitrate_kbps: int = Field(0, ge=0, le=3200, alias="audioBitrateKbps")
    fallback: RuleFallbackStrategy = RuleFallbackStrategy.BEST_AVAILABLE

    model_config = {"populate_by_name": True}

    @model_validator(mode="after")
    def _normalize_fields(self) -> "RuleQualityConfig":
        codec_map = {
            "h264": "avc1",
            "hevc": "hev1",
            "av1": "av01",
        }
        lower_codec = self.codec.lower()
        self.codec = codec_map.get(lower_codec, lower_codec)  # type: ignore[assignment]
        if self.dynamic_range.lower() == "hdr":
            self.dynamic_range = "HDR"
        elif self.dynamic_range.lower() == "sdr":
            self.dynamic_range = "SDR"
        else:
            self.dynamic_range = "auto"
        return self


class RuleFileConfig(BaseModel):
    """文件命名模板、相对分类目录与重复文件处理（受严格目录沙盒限制）"""
    filename_template: str = Field("{title}", alias="filenameTemplate")
    category_dir: str = Field("", alias="categoryDir")
    collection_subfolder: bool = Field(True, alias="collectionSubfolder")
    collection_dir_template: str = Field("{collectionTitle}", alias="collectionDirTemplate")
    duplicate_policy: RuleDuplicatePolicy = Field(RuleDuplicatePolicy.RENAME, alias="duplicatePolicy")

    model_config = {"populate_by_name": True}

    @field_validator("filename_template", "category_dir", "collection_dir_template")
    @classmethod
    def _validate_path_safety(cls, value: str, info: Any) -> str:
        text = (value or "").strip()
        if len(text) > 160:
            raise ValueError("命名模板或目录名过长（最多 160 字符）")
        if not text:
            return "{title}" if info.field_name == "filename_template" else (
                "{collectionTitle}" if info.field_name == "collection_dir_template" else ""
            )
        if UNSAFE_COMMAND_PATTERN.search(text):
            raise ValueError("检测到不安全脚本或命令表达式，已被拒绝")
        # 严格禁止绝对路径、盘符、UNC 路径以及 .. 上级目录穿越
        if text.startswith(("/", "\\")) or ":" in text or text.startswith("~"):
            raise ValueError("规则目录或文件名模板禁止使用绝对路径、盘符或 UNC 路径")
        parts = re.split(r"[\\/]+", text)
        for seg in parts:
            cleaned_seg = seg.strip()
            if cleaned_seg in ("..", ".") and cleaned_seg != "":
                raise ValueError("规则路径禁止包含 '..' 或 '.' 越权跳转段")
            if info.field_name == "filename_template" and ("/" in text or "\\" in text):
                raise ValueError("filenameTemplate 仅允许生成文件名（分类子目录请配置到 categoryDir）")
        return text


class RuleExecutionConfig(BaseModel):
    """执行动作与保护上限"""
    action: RuleExecutionAction = RuleExecutionAction.CONFIRM
    max_tasks: int = Field(100, ge=1, le=200, alias="maxTasks")

    model_config = {"populate_by_name": True}


def sanitize_platform_overrides(raw: Any) -> Dict[str, Any]:
    """深度净化扩展字段，确保绝不夹带 Cookie、Token、脚本命令等敏感数据。"""
    if not isinstance(raw, dict):
        return {}
    cleaned: Dict[str, Any] = {}
    for k, v in raw.items():
        key_str = str(k)
        if SENSITIVE_KEY_PATTERN.search(key_str):
            continue
        if isinstance(v, dict):
            nested = sanitize_platform_overrides(v)
            if nested:
                cleaned[key_str] = nested
        elif isinstance(v, list):
            safe_list = []
            for item in v[:50]:
                if isinstance(item, (str, int, float, bool)):
                    if isinstance(item, str) and (SENSITIVE_VALUE_PATTERN.search(item) or UNSAFE_COMMAND_PATTERN.search(item)):
                        continue
                    safe_list.append(item)
            cleaned[key_str] = safe_list
        elif isinstance(v, (str, int, float, bool)):
            if isinstance(v, str) and (SENSITIVE_VALUE_PATTERN.search(v) or UNSAFE_COMMAND_PATTERN.search(v)):
                continue
            cleaned[key_str] = v
    return cleaned


def contains_sensitive_data(obj: Any) -> bool:
    """递归检测对象中是否包含疑似 Cookie/Token/授权头等敏感内容。"""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if SENSITIVE_KEY_PATTERN.search(str(k)):
                return True
            if contains_sensitive_data(v):
                return True
    elif isinstance(obj, list):
        for item in obj:
            if contains_sensitive_data(item):
                return True
    elif isinstance(obj, str):
        if SENSITIVE_VALUE_PATTERN.search(obj) or UNSAFE_COMMAND_PATTERN.search(obj):
            return True
    return False


class DownloadRule(BaseModel):
    """完整单条 Omni Download Rule 数据契约"""
    id: str = Field(default_factory=lambda: f"rule_{uuid.uuid4().hex[:10]}")
    name: str = Field(..., min_length=1, max_length=100)
    description: str = Field("", max_length=400)
    enabled: bool = True
    priority: int = Field(100, ge=0, le=99999)
    imported_untrusted: bool = Field(False, alias="importedUntrusted")
    triggers: List[RuleTrigger] = Field(
        default_factory=lambda: [
            RuleTrigger.MANUAL,
            RuleTrigger.COLLECTION,
            RuleTrigger.BATCH,
            RuleTrigger.CLIPBOARD,
        ]
    )
    match: RuleMatchConfig = Field(default_factory=RuleMatchConfig)
    collection: RuleCollectionConfig = Field(default_factory=RuleCollectionConfig)
    download: RuleDownloadConfig = Field(default_factory=RuleDownloadConfig)
    quality: RuleQualityConfig = Field(default_factory=RuleQualityConfig)
    files: RuleFileConfig = Field(default_factory=RuleFileConfig)
    execution: RuleExecutionConfig = Field(default_factory=RuleExecutionConfig)
    platform_overrides: Dict[str, Any] = Field(default_factory=dict, alias="platformOverrides")
    created_at: int = Field(default_factory=lambda: int(time.time() * 1000), alias="createdAt")
    updated_at: int = Field(default_factory=lambda: int(time.time() * 1000), alias="updatedAt")

    model_config = {"populate_by_name": True}

    @field_validator("id")
    @classmethod
    def _clean_id(cls, value: str) -> str:
        clean = re.sub(r"[^a-zA-Z0-9_\-]", "", (value or "").strip())
        return clean[:64] or f"rule_{uuid.uuid4().hex[:10]}"

    @field_validator("name")
    @classmethod
    def _clean_name(cls, value: str) -> str:
        text = (value or "").strip()
        if not text:
            raise ValueError("规则名称不能为空")
        if SENSITIVE_VALUE_PATTERN.search(text) or UNSAFE_COMMAND_PATTERN.search(text):
            raise ValueError("规则名称包含不允许的内容")
        return text

    @field_validator("triggers")
    @classmethod
    def _ensure_triggers(cls, value: List[RuleTrigger]) -> List[RuleTrigger]:
        if not value:
            return [RuleTrigger.MANUAL]
        seen: List[RuleTrigger] = []
        for item in value:
            if item not in seen:
                seen.append(item)
        return seen

    @field_validator("platform_overrides")
    @classmethod
    def _clean_overrides(cls, value: Dict[str, Any]) -> Dict[str, Any]:
        return sanitize_platform_overrides(value)

    def to_safe_export_dict(self) -> Dict[str, Any]:
        """生成绝对脱敏的可分享跨平台规则 JSON（剔除本地时间戳差异并剥离所有潜在敏感字段）。"""
        dumped = self.model_dump(by_alias=True, mode="json")
        dumped.pop("importedUntrusted", None)
        dumped["platformOverrides"] = sanitize_platform_overrides(dumped.get("platformOverrides") or {})
        return dumped


class RuleSettingsState(BaseModel):
    """规则系统与剪贴板监听全局配置"""
    rules_system_enabled: bool = Field(True, alias="rulesSystemEnabled")
    clipboard_watch_enabled: bool = Field(False, alias="clipboardWatchEnabled")
    clipboard_paused: bool = Field(False, alias="clipboardPaused")
    clipboard_debounce_ms: int = Field(550, ge=200, le=5000, alias="clipboardDebounceMs")
    clipboard_dedup_window_sec: int = Field(20, ge=3, le=3600, alias="clipboardDedupWindowSec")
    clipboard_show_tray_notice: bool = Field(True, alias="clipboardShowTrayNotice")

    model_config = {"populate_by_name": True}


class RuleBundle(BaseModel):
    """标准 .omnirule.json 导入导出协议包装"""
    schema_version: int = Field(RULE_SCHEMA_VERSION, alias="schemaVersion")
    format: str = RULE_BUNDLE_FORMAT
    exported_at: str = Field("", alias="exportedAt")
    rules: List[DownloadRule] = Field(default_factory=list)

    model_config = {"populate_by_name": True}


def parse_and_validate_rule_json(raw_text: str) -> Tuple[List[DownloadRule], List[str]]:
    """
    校验并解析外部/编辑器传入的规则 JSON（兼容单条规则 JSON 与 RuleBundle 包）。
    返回 (validated_rules, warnings)。
    """
    if raw_text is None or not str(raw_text).strip():
        raise ValueError("JSON 内容不能为空")
    raw_bytes = raw_text.encode("utf-8")
    if len(raw_bytes) > MAX_IMPORT_BYTES:
        raise ValueError(f"规则文件体积超过上限（最大 {MAX_IMPORT_BYTES // 1024} KB）")
    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"JSON 语法错误（第 {exc.lineno} 行第 {exc.colno} 列）：{exc.msg}") from None

    if contains_sensitive_data(parsed):
        raise ValueError("规则 JSON 中禁止包含 Cookie、Token、鉴权头或命令注入片段")

    warnings: List[str] = []
    if isinstance(parsed, dict) and "rules" in parsed:
        version = int(parsed.get("schemaVersion") or 1)
        if version > RULE_SCHEMA_VERSION:
            warnings.append(f"规则文件版本 v{version} 高于当前客户端支持版本 v{RULE_SCHEMA_VERSION}，已尝试兼容解析")
        bundle = RuleBundle.model_validate(parsed)
        rules = bundle.rules
    elif isinstance(parsed, list):
        rules = [DownloadRule.model_validate(item) for item in parsed]
    elif isinstance(parsed, dict):
        rules = [DownloadRule.model_validate(parsed)]
    else:
        raise ValueError("规则 JSON 根节点必须为对象或规则数组")

    if not rules:
        raise ValueError("未在 JSON 中检测到任何有效规则")
    if len(rules) > MAX_RULES_COUNT:
        raise ValueError(f"单次最多导入 {MAX_RULES_COUNT} 条规则")
    return rules, warnings


def apply_external_import_safety_guard(rules: List[DownloadRule]) -> Tuple[List[DownloadRule], List[str]]:
    """
    安全规范落地：外部导入的规则默认不得启用自动下载（auto_download -> confirm），
    防止恶意共享规则一导入就借助剪贴板或批量任务静默大量下载文件。
    """
    notes: List[str] = []
    safe_rules: List[DownloadRule] = []
    for rule in rules:
        copied = rule.model_copy(deep=True)
        copied.id = f"rule_{uuid.uuid4().hex[:10]}"
        if copied.execution.action == RuleExecutionAction.AUTO_DOWNLOAD:
            copied.execution.action = RuleExecutionAction.CONFIRM
            copied.imported_untrusted = True
            notes.append(f"规则「{copied.name}」为外部导入，已按安全策略默认将执行动作降级为「确认后下载」")
        safe_rules.append(copied)
    return safe_rules, notes


def export_rules_bundle(rules: List[DownloadRule]) -> Dict[str, Any]:
    """构造严格脱敏的 .omnirule.json 导出字典。"""
    import datetime
    return {
        "schemaVersion": RULE_SCHEMA_VERSION,
        "format": RULE_BUNDLE_FORMAT,
        "exportedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
        "rules": [rule.to_safe_export_dict() for rule in rules],
    }


def sanitize_filename_segment(value: str, default: str = "item", max_len: int = 80) -> str:
    """清洗来自外部元数据（标题、作者、合集名等）的单段文件或子目录名，阻断路径穿越与非法字符。"""
    text = re.sub(r"https?://\S+", "", value or "")
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", text).strip(". ")
    cleaned = re.sub(r"\s+", " ", cleaned)[:max_len].strip(". ")
    if not cleaned or cleaned in (".", ".."):
        return default
    if cleaned.upper() in WINDOWS_RESERVED_NAMES:
        return f"_{cleaned}"
    return cleaned


def resolve_safe_subpath(root_dir: str | Path, relative_subpath: str) -> Path:
    """将用户规则定义的相对目录解析到合法下载根目录下，并在规范化后校验沙盒边界。"""
    root = Path(root_dir).resolve()
    raw = (relative_subpath or "").strip()
    if not raw:
        return root
    if raw.startswith(("/", "\\")) or ":" in raw or raw.startswith("~") or UNSAFE_COMMAND_PATTERN.search(raw):
        raise ValueError(f"越权或非法的目标目录配置: {raw}")
    posix_parts = [p for p in PurePosixPath(raw.replace("\\", "/")).parts if p not in ("", "/")]
    safe_segments: List[str] = []
    for seg in posix_parts:
        if seg in (".", ".."):
            raise ValueError(f"目标目录禁止上级目录穿越: {raw}")
        safe_segments.append(sanitize_filename_segment(seg, default="folder", max_len=64))
    target = root.joinpath(*safe_segments).resolve()
    try:
        target.relative_to(root)
    except ValueError:
        raise ValueError(f"目标路径超出下载根目录沙盒限制: {target}") from None
    return target


RULE_SCHEMA_DOCS: List[Dict[str, str]] = [
    {"field": "schemaVersion", "type": "integer", "desc": "协议版本，当前固定为 1，兼容未来移动端与 Agent Edition"},
    {"field": "name / description", "type": "string", "desc": "规则名称（必填）与说明"},
    {"field": "enabled / priority", "type": "boolean / integer", "desc": "是否启用及优先级数字（数值越小优先级越高，默认 100）"},
    {"field": "triggers", "type": "string[]", "desc": "触发入口：manual（手动）、collection（合集）、batch（批量）、clipboard（剪贴板）"},
    {"field": "match.platforms", "type": "string[]", "desc": "平台名称筛选（无需枚举白名单，自动对接所有解析器提供的站点名）"},
    {"field": "match.domains / urlPatterns", "type": "string[]", "desc": "域名列表（如 bilibili.com）及 URL 匹配列表"},
    {"field": "match.urlMatchMode", "type": "string", "desc": "URL 匹配模式：contains | regex | wildcard"},
    {"field": "match.mediaTypes", "type": "string[]", "desc": "内容类型筛选：video | images | audio | gif"},
    {"field": "match.titleKeywords / titleExcludeKeywords / titleRegex", "type": "string[] / string", "desc": "标题包含词、排除词与正则"},
    {"field": "match.authorKeywords / authorExcludeKeywords", "type": "string[]", "desc": "作者或 UP 主包含/排除关键词"},
    {"field": "match.minDurationSec / maxDurationSec", "type": "integer", "desc": "视频时长筛选范围（单位：秒，0 表示不限制）"},
    {"field": "collection.mode", "type": "string", "desc": "合集筛选：all | episodes | range | keywords"},
    {"field": "collection.episodes / rangeStart / rangeEnd", "type": "string / int", "desc": "指定分集（如 '1,3,5-8'）或起止分集号"},
    {"field": "collection.includeKeywords / excludeKeywords / skipDownloaded", "type": "string[] / bool", "desc": "分集标题关键词筛选与是否跳过已下载分集"},
    {"field": "download.mode", "type": "string", "desc": "导出模式：VIDEO_WITH_AUDIO | VIDEO_ONLY | AUDIO_ONLY | GIF | COVER | IMAGE | IMAGE_ZIP"},
    {"field": "download.saveCover / imageArchiveMode", "type": "bool / string", "desc": "是否同时保存封面；图文规则默认 original（逐张）或 zip"},
    {"field": "quality.strategy / resolution / codec / minFps / dynamicRange", "type": "mixed", "desc": "质量策略（best / target_resolution）、目标高度（如 2160）、编码（auto/av01/hev1/avc1/vp9）、最小帧率、HDR/SDR"},
    {"field": "quality.container / audioFormat / fallback", "type": "string", "desc": "封装容器、音频格式（mp3/m4a/flac/opus）及回退策略（best_available / closest_lower / exact_or_fail）"},
    {"field": "files.filenameTemplate / categoryDir / collectionSubfolder / duplicatePolicy", "type": "mixed", "desc": "命名模板（如 '{author}-{title}'）、相对分类目录、合集独立子目录与重名处理（rename / skip / overwrite）"},
    {"field": "execution.action / maxTasks", "type": "string / int", "desc": "执行动作：auto_download | parse_only | confirm | ignore；单次最大任务数限制"},
]
