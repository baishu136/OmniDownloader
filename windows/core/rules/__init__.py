"""Omni Download Rules 核心包：规则校验、匹配、合集筛选、安全输出规划与跨入口去重索引。"""

from core.rules.engine import RuleEngine, rule_engine
from core.rules.output_helper import resolve_task_export_target
from core.rules.schema import (
    DownloadRule,
    RuleBundle,
    RuleDuplicatePolicy,
    RuleExecutionAction,
    RuleFallbackStrategy,
    RuleSettingsState,
    RuleTrigger,
    RULE_SCHEMA_DOCS,
    apply_external_import_safety_guard,
    export_rules_bundle,
    parse_and_validate_rule_json,
    resolve_safe_subpath,
    sanitize_filename_segment,
)
from core.rules.store import RuleStore, compute_dedup_key, rule_store

__all__ = [
    "DownloadRule",
    "RuleBundle",
    "RuleDuplicatePolicy",
    "RuleEngine",
    "RuleExecutionAction",
    "RuleFallbackStrategy",
    "RuleSettingsState",
    "RuleStore",
    "RuleTrigger",
    "RULE_SCHEMA_DOCS",
    "apply_external_import_safety_guard",
    "compute_dedup_key",
    "export_rules_bundle",
    "parse_and_validate_rule_json",
    "resolve_safe_subpath",
    "resolve_task_export_target",
    "rule_engine",
    "rule_store",
    "sanitize_filename_segment",
]
