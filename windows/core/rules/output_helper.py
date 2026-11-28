"""共享安全输出路径助手：供所有现有下载引擎出口统一应用规则定制目录、命名与去重/重名覆盖策略。"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Tuple

from core.config import config
from core.rules.schema import resolve_safe_subpath, sanitize_filename_segment


def resolve_task_export_target(
    task: Any,
    default_base_name: str,
    ext: str,
) -> Tuple[Path, str]:
    """
    解析任务的最终导出文件路径与生效的重复文件策略（rename | skip | overwrite）。
    当任务未使用规则目录/文件覆盖时，行为 100% 等同原系统（resolve_download_dir + 自动 _1 序号递增）。
    """
    clean_ext = ext if str(ext).startswith(".") else f".{ext}"
    root_dir = Path(config.resolve_download_dir(task.download_type)).resolve()
    override_dir = (getattr(task, "output_dir_override", "") or "").strip()

    export_dir = root_dir
    if override_dir:
        cand_path = Path(override_dir)
        if cand_path.is_absolute():
            # 即便历史快照传了绝对路径，也强制核验它位于允许下载根目录内，拒绝任意目录写入
            resolved = cand_path.resolve()
            try:
                resolved.relative_to(root_dir)
                export_dir = resolved
            except ValueError:
                base_global = Path(config.download_dir).resolve()
                try:
                    resolved.relative_to(base_global)
                    export_dir = resolved
                except ValueError as exc:
                    raise ValueError(f"[SECURITY_VIOLATION] 拦截越权输出目录: {override_dir}") from exc
        else:
            export_dir = resolve_safe_subpath(root_dir, override_dir)

    export_dir.mkdir(parents=True, exist_ok=True)

    override_name = (getattr(task, "filename_override", "") or "").strip()
    base_name = sanitize_filename_segment(override_name, default=default_base_name, max_len=120) if override_name else default_base_name
    dup_policy = (getattr(task, "duplicate_policy", "") or "rename").strip().lower()
    if dup_policy not in ("rename", "skip", "overwrite"):
        dup_policy = "rename"

    destination = export_dir / f"{base_name}{clean_ext}"
    if dup_policy == "skip" and destination.exists():
        return destination, "skip"
    if dup_policy == "overwrite":
        return destination, "overwrite"

    index = 1
    while destination.exists():
        destination = export_dir / f"{base_name}_{index}{clean_ext}"
        index += 1
    return destination, "rename"
