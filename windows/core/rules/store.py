"""规则集、跨入口去重记录索引与执行审计日志持久化仓储。"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import core.config as core_config
from core.rules.schema import (
    DownloadRule,
    RuleSettingsState,
    export_rules_bundle,
    parse_and_validate_rule_json,
)


MAX_AUDIT_LOGS = 300
MAX_DEDUP_RECORDS = 5000


def _state_dir() -> Path:
    """动态读取当前状态目录，确保 pytest 隔离与环境变量 OMNI_STATE_DIR 始终生效。"""
    return Path(core_config.CONFIG_FILE).parent


def compute_dedup_key(
    platform: str,
    media_id: str,
    episode_id: str,
    download_type: str,
    quality_strategy: str,
    output_format: str,
    fallback_url: str = "",
) -> str:
    """
    计算跨入口去重指纹：
    由 平台 + 媒体ID + 分集ID + 下载类型 + 质量策略 + 输出格式 共同决定。
    """
    norm_platform = (platform or "unknown").strip().lower()
    norm_media = (media_id or "").strip()
    if not norm_media:
        norm_media = "url:" + hashlib.sha256((fallback_url or "").strip().encode("utf-8")).hexdigest()[:24]
    norm_ep = (episode_id or "").strip()
    norm_dtype = (download_type or "VIDEO_WITH_AUDIO").strip().upper()
    norm_q = (quality_strategy or "best").strip().lower()
    norm_fmt = (output_format or "mp4").strip().lower()
    raw = f"{norm_platform}|{norm_media}|{norm_ep}|{norm_dtype}|{norm_q}|{norm_fmt}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class RuleStore:
    """统一管理规则配置、去重索引与规则执行日志（支持原子写入与安全缓存）。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._loaded_dir: Optional[Path] = None
        self._settings = RuleSettingsState()
        self._rules: List[DownloadRule] = []
        self._dedup_index: Dict[str, Dict[str, Any]] = {}
        self._audit_logs: List[Dict[str, Any]] = []
        self._pending_confirmations: Dict[str, Dict[str, Any]] = {}

    def _ensure_loaded(self) -> None:
        cur_dir = _state_dir()
        with self._lock:
            if self._loaded_dir == cur_dir:
                return
            self._loaded_dir = cur_dir
            self._settings = RuleSettingsState()
            self._rules = []
            self._dedup_index = {}
            self._audit_logs = []
            self._pending_confirmations = {}

            rules_path = cur_dir / "rules.json"
            if rules_path.is_file():
                try:
                    raw = json.loads(rules_path.read_text("utf-8"))
                    if isinstance(raw, dict):
                        if isinstance(raw.get("settings"), dict):
                            self._settings = RuleSettingsState.model_validate(raw["settings"])
                        if isinstance(raw.get("rules"), list):
                            loaded: List[DownloadRule] = []
                            for item in raw["rules"]:
                                try:
                                    loaded.append(DownloadRule.model_validate(item))
                                except Exception:
                                    continue
                            self._rules = self._sort_rules(loaded)
                except Exception as exc:
                    print(f"[RuleStore] 读取规则文件失败，使用默认空规则集: {exc}")

            dedup_path = cur_dir / "rule_dedup_index.json"
            if dedup_path.is_file():
                try:
                    raw_dedup = json.loads(dedup_path.read_text("utf-8"))
                    if isinstance(raw_dedup, dict):
                        self._dedup_index = raw_dedup
                except Exception as exc:
                    print(f"[RuleStore] 读取去重索引失败: {exc}")

            audit_path = cur_dir / "rule_audit_logs.json"
            if audit_path.is_file():
                try:
                    raw_audit = json.loads(audit_path.read_text("utf-8"))
                    if isinstance(raw_audit, list):
                        self._audit_logs = raw_audit[-MAX_AUDIT_LOGS:]
                except Exception as exc:
                    print(f"[RuleStore] 读取执行日志失败: {exc}")

    @staticmethod
    def _sort_rules(rules: List[DownloadRule]) -> List[DownloadRule]:
        return sorted(rules, key=lambda r: (int(r.priority), int(r.created_at), r.id))

    @staticmethod
    def _atomic_write_json(path: Path, payload: Any) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)

    def _save_rules_unlocked(self) -> None:
        cur_dir = _state_dir()
        payload = {
            "schemaVersion": 1,
            "settings": self._settings.model_dump(by_alias=True, mode="json"),
            "rules": [r.model_dump(by_alias=True, mode="json") for r in self._rules],
        }
        self._atomic_write_json(cur_dir / "rules.json", payload)

    def _save_dedup_unlocked(self) -> None:
        cur_dir = _state_dir()
        self._atomic_write_json(cur_dir / "rule_dedup_index.json", self._dedup_index)

    def _save_audit_unlocked(self) -> None:
        cur_dir = _state_dir()
        self._atomic_write_json(cur_dir / "rule_audit_logs.json", self._audit_logs[-MAX_AUDIT_LOGS:])

    # === 全局设置 ===

    def get_settings(self) -> RuleSettingsState:
        self._ensure_loaded()
        with self._lock:
            return self._settings.model_copy(deep=True)

    def update_settings(self, patch: Dict[str, Any]) -> RuleSettingsState:
        self._ensure_loaded()
        with self._lock:
            merged = self._settings.model_dump(by_alias=True)
            for k, v in patch.items():
                if v is not None:
                    merged[k] = v
            self._settings = RuleSettingsState.model_validate(merged)
            self._save_rules_unlocked()
            return self._settings.model_copy(deep=True)

    # === 规则 CRUD ===

    def list_rules(self) -> List[DownloadRule]:
        self._ensure_loaded()
        with self._lock:
            return [r.model_copy(deep=True) for r in self._rules]

    def get_rule(self, rule_id: str) -> Optional[DownloadRule]:
        self._ensure_loaded()
        with self._lock:
            for r in self._rules:
                if r.id == rule_id:
                    return r.model_copy(deep=True)
            return None

    def upsert_rule(self, rule: DownloadRule) -> DownloadRule:
        self._ensure_loaded()
        with self._lock:
            now = int(time.time() * 1000)
            updated = rule.model_copy(deep=True)
            updated.updated_at = now
            replaced = False
            for idx, existing in enumerate(self._rules):
                if existing.id == updated.id:
                    updated.created_at = existing.created_at
                    self._rules[idx] = updated
                    replaced = True
                    break
            if not replaced:
                updated.created_at = updated.created_at or now
                self._rules.append(updated)
            self._rules = self._sort_rules(self._rules)
            self._save_rules_unlocked()
            return updated.model_copy(deep=True)

    def duplicate_rule(self, rule_id: str, new_name: Optional[str] = None) -> Optional[DownloadRule]:
        self._ensure_loaded()
        with self._lock:
            source = self.get_rule(rule_id)
            if not source:
                return None
            now = int(time.time() * 1000)
            clone = source.model_copy(deep=True)
            clone.id = f"rule_{uuid.uuid4().hex[:10]}"
            clone.name = (new_name or f"{source.name} (副本)")[:100]
            clone.created_at = now
            clone.updated_at = now
            self._rules.append(clone)
            self._rules = self._sort_rules(self._rules)
            self._save_rules_unlocked()
            return clone.model_copy(deep=True)

    def delete_rule(self, rule_id: str) -> bool:
        self._ensure_loaded()
        with self._lock:
            before = len(self._rules)
            self._rules = [r for r in self._rules if r.id != rule_id]
            if len(self._rules) == before:
                return False
            self._save_rules_unlocked()
            return True

    def reorder_rules(self, ordered_ids: List[str]) -> List[DownloadRule]:
        """按前端拖拽或上移/下移提交的新顺序重排规则并自动步进赋优先级。"""
        self._ensure_loaded()
        with self._lock:
            id_to_rule = {r.id: r for r in self._rules}
            ordered: List[DownloadRule] = []
            seen = set()
            for rid in ordered_ids:
                if rid in id_to_rule and rid not in seen:
                    seen.add(rid)
                    ordered.append(id_to_rule[rid])
            for r in self._rules:
                if r.id not in seen:
                    ordered.append(r)
            now = int(time.time() * 1000)
            for idx, r in enumerate(ordered):
                r.priority = (idx + 1) * 10
                r.updated_at = now
            self._rules = ordered
            self._save_rules_unlocked()
            return [r.model_copy(deep=True) for r in self._rules]

    def export_bundle(self, rule_ids: Optional[List[str]] = None) -> Dict[str, Any]:
        self._ensure_loaded()
        with self._lock:
            if rule_ids:
                targets = [r for r in self._rules if r.id in set(rule_ids)]
            else:
                targets = list(self._rules)
            return export_rules_bundle(targets)

    # === 独立去重索引 ===

    def check_duplicate(
        self,
        dedup_key: str,
        active_task_ids: Optional[set[str]] = None,
    ) -> Optional[Dict[str, Any]]:
        """
        检查去重记录：
        - 若对应任务正在活跃队列中，返回命中记录；
        - 若对应记录已完成且本地文件路径存在（或未记录路径但属于刚记录条目），返回命中记录；
        - 若已完成任务的本地文件已被用户移动或删除，自动失效该旧索引以便重新下载。
        """
        self._ensure_loaded()
        with self._lock:
            rec = self._dedup_index.get(dedup_key)
            if not rec:
                return None
            status = str(rec.get("status") or "").upper()
            task_id = str(rec.get("taskId") or "")
            local_path = str(rec.get("localFilePath") or "")

            if status in ("PENDING", "DOWNLOADING", "PROCESSING", "QUEUED"):
                if active_task_ids is None or task_id in active_task_ids:
                    return dict(rec)
                # 队列中已不存在该旧任务且未完成，清理陈旧占位
                self._dedup_index.pop(dedup_key, None)
                self._save_dedup_unlocked()
                return None

            if status == "COMPLETED":
                if local_path:
                    if os.path.exists(local_path):
                        return dict(rec)
                    # 用户删除了文件则允许重下
                    self._dedup_index.pop(dedup_key, None)
                    self._save_dedup_unlocked()
                    return None
                return dict(rec)
            return None

    def record_dedup_entry(
        self,
        dedup_key: str,
        *,
        platform: str,
        media_id: str,
        episode_id: str,
        download_type: str,
        quality_strategy: str,
        output_format: str,
        title: str,
        url: str,
        task_id: str = "",
        local_file_path: str = "",
        status: str = "QUEUED",
    ) -> None:
        self._ensure_loaded()
        with self._lock:
            now = int(time.time() * 1000)
            existing = self._dedup_index.get(dedup_key)
            self._dedup_index[dedup_key] = {
                "key": dedup_key,
                "platform": platform,
                "mediaId": media_id,
                "episodeId": episode_id,
                "downloadType": download_type,
                "qualityStrategy": quality_strategy,
                "outputFormat": output_format,
                "title": title[:160],
                "url": url[:500],
                "taskId": task_id,
                "localFilePath": local_file_path,
                "status": status.upper(),
                "createdAt": existing.get("createdAt", now) if existing else now,
                "updatedAt": now,
            }
            if len(self._dedup_index) > MAX_DEDUP_RECORDS:
                oldest = sorted(
                    self._dedup_index.items(),
                    key=lambda kv: int(kv[1].get("updatedAt") or 0),
                )
                for k, _ in oldest[: len(self._dedup_index) - MAX_DEDUP_RECORDS]:
                    self._dedup_index.pop(k, None)
            self._save_dedup_unlocked()

    def mark_task_completed(self, task_id: str, local_file_path: str) -> None:
        """任务完成后同步更新去重索引的落盘路径与状态。"""
        if not task_id:
            return
        self._ensure_loaded()
        with self._lock:
            changed = False
            now = int(time.time() * 1000)
            for rec in self._dedup_index.values():
                if rec.get("taskId") == task_id:
                    rec["status"] = "COMPLETED"
                    rec["localFilePath"] = local_file_path
                    rec["updatedAt"] = now
                    changed = True
            if changed:
                self._save_dedup_unlocked()

    def mark_task_inactive(self, task_id: str) -> None:
        """任务取消/失败/删除且未完成时移除 QUEUED 去重占位，允许用户重试。"""
        if not task_id:
            return
        self._ensure_loaded()
        with self._lock:
            to_del = [
                k for k, rec in self._dedup_index.items()
                if rec.get("taskId") == task_id and str(rec.get("status") or "").upper() != "COMPLETED"
            ]
            for k in to_del:
                self._dedup_index.pop(k, None)
            if to_del:
                self._save_dedup_unlocked()

    def list_dedup_entries(self, limit: int = 100) -> Dict[str, Any]:
        self._ensure_loaded()
        with self._lock:
            items = sorted(
                self._dedup_index.values(),
                key=lambda x: int(x.get("updatedAt") or 0),
                reverse=True,
            )[:limit]
            return {"totalCount": len(self._dedup_index), "items": items}

    def clear_dedup_index(self) -> int:
        self._ensure_loaded()
        with self._lock:
            count = len(self._dedup_index)
            self._dedup_index.clear()
            self._save_dedup_unlocked()
            return count

    # === 规则执行审计记录 ===

    def append_audit(
        self,
        *,
        trigger: str,
        url: str,
        platform: str = "",
        title: str = "",
        rule_id: str = "",
        rule_name: str = "",
        status: str = "unmatched",
        reason: str = "",
        warnings: Optional[List[str]] = None,
        task_ids: Optional[List[str]] = None,
        planned_count: int = 0,
        skipped_count: int = 0,
    ) -> Dict[str, Any]:
        self._ensure_loaded()
        with self._lock:
            entry = {
                "id": uuid.uuid4().hex[:12],
                "timestamp": int(time.time() * 1000),
                "trigger": trigger,
                "url": (url or "")[:500],
                "platform": platform,
                "title": (title or "")[:160],
                "ruleId": rule_id,
                "ruleName": rule_name,
                "status": status,
                "reason": reason,
                "warnings": list(warnings or []),
                "taskIds": list(task_ids or []),
                "plannedCount": planned_count,
                "skippedCount": skipped_count,
            }
            self._audit_logs.append(entry)
            if len(self._audit_logs) > MAX_AUDIT_LOGS:
                self._audit_logs = self._audit_logs[-MAX_AUDIT_LOGS:]
            self._save_audit_unlocked()
            return entry

    def list_audit_logs(self, limit: int = 100) -> List[Dict[str, Any]]:
        self._ensure_loaded()
        with self._lock:
            return list(reversed(self._audit_logs[-limit:]))

    def clear_audit_logs(self) -> int:
        self._ensure_loaded()
        with self._lock:
            count = len(self._audit_logs)
            self._audit_logs.clear()
            self._save_audit_unlocked()
            return count

    # === 待用户确认队列（用于 confirm 执行策略）===

    def add_pending_confirmation(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self._ensure_loaded()
        with self._lock:
            cid = payload.get("confirmId") or uuid.uuid4().hex[:10]
            record = {**payload, "confirmId": cid, "createdAt": int(time.time() * 1000)}
            self._pending_confirmations[cid] = record
            if len(self._pending_confirmations) > 100:
                oldest_key = next(iter(self._pending_confirmations))
                self._pending_confirmations.pop(oldest_key, None)
            return dict(record)

    def pop_pending_confirmation(self, confirm_id: str) -> Optional[Dict[str, Any]]:
        self._ensure_loaded()
        with self._lock:
            item = self._pending_confirmations.pop(confirm_id, None)
            return dict(item) if item else None

    def list_pending_confirmations(self) -> List[Dict[str, Any]]:
        self._ensure_loaded()
        with self._lock:
            return list(reversed(list(self._pending_confirmations.values())))


rule_store = RuleStore()
