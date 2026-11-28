"""Windows GPU 硬件能力自动探测、FFmpeg 编解码加速配置及运行时软件回退管理。"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import json
import logging
import os
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from core.config import CONFIG_FILE, config

logger = logging.getLogger("omni.gpu")

_PROBE_LOCK = threading.RLock()
_MEM_CACHE: Dict[str, dict] = {}
_RUNTIME_FAILED_ENCODERS: set[str] = set()
_RUNTIME_FAILED_HWACCELS: set[str] = set()
_BG_WARMUP_STARTED = False

_CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


@dataclass(frozen=True)
class EncodeProfile:
    """FFmpeg 视频编码方案描述符（支持硬件编码与软件回退）。"""

    vendor: str         # nvidia | intel | amd | software
    backend: str        # nvenc | qsv | amf | software
    encoder: str        # h264_nvenc | h264_qsv | h264_amf | libx264
    pix_fmt: str        # yuv420p | nv12
    is_hardware: bool

    def build_encode_args(self, *, vfr_no_bframes: bool = False, threads: Optional[int] = None) -> List[str]:
        t = str(threads or optimal_ffmpeg_threads())
        if self.encoder == "h264_nvenc":
            args = ["-c:v", "h264_nvenc", "-preset", "p4", "-cq", "22"]
            if vfr_no_bframes:
                args.extend(["-bf", "0"])
            return args
        if self.encoder == "h264_qsv":
            args = ["-c:v", "h264_qsv", "-preset", "medium", "-global_quality", "22"]
            if vfr_no_bframes:
                args.extend(["-bf", "0"])
            return args
        if self.encoder == "h264_amf":
            args = ["-c:v", "h264_amf", "-quality", "balanced", "-qp_i", "20", "-qp_p", "22"]
            if vfr_no_bframes:
                args.extend(["-bf", "0"])
            return args
        # 默认 CPU 软件编码回退
        args = ["-c:v", "libx264", "-preset", "faster", "-crf", "18"]
        if vfr_no_bframes:
            args.extend(["-bf", "0"])
        args.extend(["-threads", t])
        return args


_VENDOR_PROFILES: Dict[str, EncodeProfile] = {
    "nvidia": EncodeProfile("nvidia", "nvenc", "h264_nvenc", "yuv420p", True),
    "intel": EncodeProfile("intel", "qsv", "h264_qsv", "nv12", True),
    "amd": EncodeProfile("amd", "amf", "h264_amf", "yuv420p", True),
}
_SOFTWARE_PROFILE = EncodeProfile("software", "software", "libx264", "yuv420p", False)


def optimal_ffmpeg_threads() -> int:
    """计算受控的 FFmpeg 线程上限，避免音视频处理与 UI/下载并发抢占全部核心。"""
    cores = os.cpu_count() or 4
    return max(2, min(cores // 2, 6))


def _cache_file_path() -> Path:
    return CONFIG_FILE.parent / "gpu_probe_cache.json"


def _resolve_ffmpeg_exe(explicit: Optional[str] = None) -> Optional[str]:
    if explicit:
        return explicit
    from engine.ffmpeg_helper import get_ffmpeg_path
    return get_ffmpeg_path()


def _detect_vendor_from_adapter(name: str, device_id: str) -> str:
    upper_id = (device_id or "").upper()
    lower_name = (name or "").lower()
    if "VEN_10DE" in upper_id or any(k in lower_name for k in ("nvidia", "geforce", "rtx", "gtx", "quadro", "tesla")):
        return "nvidia"
    if "VEN_8086" in upper_id or any(k in lower_name for k in ("intel", "uhd graphics", "iris", "arc(tm)", "arc graphics")):
        return "intel"
    if "VEN_1002" in upper_id or any(k in lower_name for k in ("amd", "radeon", "firepro")):
        return "amd"
    return "other"


def detect_display_adapters() -> List[dict]:
    """通过 Windows 原生 EnumDisplayDevicesW 毫秒级枚举显卡硬件，零 WMI 子进程开销。"""
    if sys.platform != "win32":
        return []

    class _DISPLAY_DEVICEW(ctypes.Structure):
        _fields_ = [
            ("cb", wt.DWORD),
            ("DeviceName", wt.WCHAR * 32),
            ("DeviceString", wt.WCHAR * 128),
            ("StateFlags", wt.DWORD),
            ("DeviceID", wt.WCHAR * 128),
            ("DeviceKey", wt.WCHAR * 128),
        ]

    adapters: List[dict] = []
    seen: set[tuple[str, str]] = set()
    dd = _DISPLAY_DEVICEW()
    dd.cb = ctypes.sizeof(dd)
    idx = 0
    try:
        enum_fn = ctypes.windll.user32.EnumDisplayDevicesW
        while enum_fn(None, idx, ctypes.byref(dd), 0):
            idx += 1
            name = (dd.DeviceString or "").strip()
            dev_id = (dd.DeviceID or "").strip()
            if not name:
                continue
            key = (name, dev_id)
            if key in seen:
                continue
            seen.add(key)
            vendor = _detect_vendor_from_adapter(name, dev_id)
            if vendor == "other":
                # 忽略纯虚拟远程桌面显示驱动等非实体显卡
                continue
            flags = int(dd.StateFlags)
            adapters.append({
                "name": name,
                "vendor": vendor,
                "deviceId": dev_id,
                "active": bool(flags & 0x1),
                "primary": bool(flags & 0x4),
            })
    except Exception as exc:
        logger.debug("EnumDisplayDevicesW 枚举异常: %s", exc)
    return adapters


def _query_ffmpeg_caps(ffmpeg_exe: str) -> Tuple[set[str], set[str]]:
    hwaccels: set[str] = set()
    encoders: set[str] = set()
    try:
        r_hw = subprocess.run(
            [ffmpeg_exe, "-hide_banner", "-hwaccels"],
            capture_output=True, text=True, timeout=4, creationflags=_CREATE_NO_WINDOW
        )
        if r_hw.returncode == 0:
            for line in (r_hw.stdout or "").splitlines():
                val = line.strip().lower()
                if val and val != "hardware acceleration methods:" and " " not in val:
                    hwaccels.add(val)
    except Exception:
        pass

    try:
        r_enc = subprocess.run(
            [ffmpeg_exe, "-hide_banner", "-encoders"],
            capture_output=True, text=True, timeout=5, creationflags=_CREATE_NO_WINDOW
        )
        if r_enc.returncode == 0:
            for enc in ("h264_nvenc", "h264_qsv", "h264_amf", "libx264"):
                if enc in (r_enc.stdout or ""):
                    encoders.add(enc)
    except Exception:
        pass
    return hwaccels, encoders


def _probe_encoder_usable(ffmpeg_exe: str, profile: EncodeProfile) -> bool:
    extra = ["-pix_fmt", profile.pix_fmt]
    cmd = [
        ffmpeg_exe, "-hide_banner", "-v", "error",
        "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.04",
        "-frames:v", "1", "-c:v", profile.encoder, *extra, "-f", "null", "-",
    ]
    try:
        res = subprocess.run(
            cmd, capture_output=True, text=True, timeout=5, creationflags=_CREATE_NO_WINDOW
        )
        return res.returncode == 0
    except Exception:
        return False


def _probe_hwaccel_usable(ffmpeg_exe: str, hwaccel: str) -> bool:
    cmd = [
        ffmpeg_exe, "-hide_banner", "-v", "error",
        "-hwaccel", hwaccel,
        "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.04",
        "-frames:v", "1", "-f", "null", "-",
    ]
    try:
        res = subprocess.run(
            cmd, capture_output=True, text=True, timeout=4, creationflags=_CREATE_NO_WINDOW
        )
        return res.returncode == 0
    except Exception:
        return False


def _ffmpeg_signature(ffmpeg_exe: Optional[str]) -> str:
    if not ffmpeg_exe:
        return "none"
    try:
        st = Path(ffmpeg_exe).stat()
        return f"{ffmpeg_exe}|{st.st_mtime_ns}|{st.st_size}"
    except OSError:
        return ffmpeg_exe


def _load_disk_probe_cache(sig: str, adapter_sig: str) -> Optional[dict]:
    path = _cache_file_path()
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("ffmpegSig") == sig and data.get("adapterSig") == adapter_sig:
            return data
    except Exception:
        pass
    return None


def _save_disk_probe_cache(payload: dict) -> None:
    path = _cache_file_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)
    except Exception:
        pass


def probe_hardware_capabilities(
    ffmpeg_path: Optional[str] = None,
    force_refresh: bool = False,
    *,
    probe_all: bool = False,
) -> dict:
    """探测并缓存本机可用硬件编码器（NVENC/QSV/AMF）与硬件解码方法；默认命中首选硬件即停以节省耗时与显存。"""
    ffmpeg_exe = _resolve_ffmpeg_exe(ffmpeg_path)
    adapters = detect_display_adapters()
    ff_sig = _ffmpeg_signature(ffmpeg_exe)
    adapter_sig = "|".join(f"{a['vendor']}:{a['name']}" for a in adapters) or "none"

    with _PROBE_LOCK:
        if not force_refresh and ff_sig in _MEM_CACHE and _MEM_CACHE[ff_sig].get("adapterSig") == adapter_sig:
            cached = _MEM_CACHE[ff_sig]
            if not probe_all or cached.get("probedAll"):
                return cached

        if not force_refresh:
            cached = _load_disk_probe_cache(ff_sig, adapter_sig)
            if cached is not None and (not probe_all or cached.get("probedAll")):
                _MEM_CACHE[ff_sig] = cached
                return cached

        usable_vendors: List[str] = []
        usable_hwaccels: List[str] = []

        if ffmpeg_exe:
            listed_hw, listed_enc = _query_ffmpeg_caps(ffmpeg_exe)
            present_vendors = [a["vendor"] for a in adapters]
            candidate_order: List[str] = []
            for v in ("nvidia", "intel", "amd"):
                if v in present_vendors and v not in candidate_order:
                    candidate_order.append(v)
            if not candidate_order:
                candidate_order = ["nvidia", "intel", "amd"]

            for vendor in candidate_order:
                prof = _VENDOR_PROFILES[vendor]
                if prof.encoder in listed_enc and _probe_encoder_usable(ffmpeg_exe, prof):
                    usable_vendors.append(vendor)
                    if not probe_all:
                        break

            for hw in ("d3d11va", "cuda", "qsv"):
                if hw in listed_hw:
                    if probe_all:
                        if _probe_hwaccel_usable(ffmpeg_exe, hw):
                            usable_hwaccels.append(hw)
                    else:
                        usable_hwaccels.append(hw)

        result = {
            "ffmpegSig": ff_sig,
            "adapterSig": adapter_sig,
            "adapters": adapters,
            "usableVendors": usable_vendors,
            "usableHwaccels": usable_hwaccels,
            "probedAll": probe_all,
            "probedAt": int(time.time()),
        }
        _MEM_CACHE[ff_sig] = result
        _save_disk_probe_cache(result)
        return result


def get_cached_gpu_status_fast() -> dict:
    """零子进程开销读取当前 GPU 状态快照（仅读内存/磁盘缓存与毫秒级显卡枚举），供 /api/settings 秒级响应。"""
    enabled = bool(getattr(config, "gpu_acceleration_enabled", True))
    pref = str(getattr(config, "gpu_preferred_vendor", "auto") or "auto").strip().lower()
    adapters = detect_display_adapters()
    ffmpeg_exe = _resolve_ffmpeg_exe(None)
    ff_sig = _ffmpeg_signature(ffmpeg_exe)
    adapter_sig = "|".join(f"{a['vendor']}:{a['name']}" for a in adapters) or "none"

    caps = None
    with _PROBE_LOCK:
        c = _MEM_CACHE.get(ff_sig)
        if c and c.get("adapterSig") == adapter_sig:
            caps = c
    if caps is None:
        caps = _load_disk_probe_cache(ff_sig, adapter_sig)
        if caps is not None:
            with _PROBE_LOCK:
                _MEM_CACHE[ff_sig] = caps

    if caps is None:
        # 尚未生成探测缓存时依据适配器硬件毫秒级推断候选状态，避免阻塞冷启动
        usable = [a["vendor"] for a in adapters if a["vendor"] in _VENDOR_PROFILES]
        caps = {
            "adapters": adapters,
            "usableVendors": usable,
            "usableHwaccels": ["d3d11va"] if usable else [],
        }
    return _format_gpu_status(enabled, pref, caps)


def _format_gpu_status(enabled: bool, pref: str, caps: dict) -> dict:
    adapters = list(caps.get("adapters") or [])
    with _PROBE_LOCK:
        failed = set(_RUNTIME_FAILED_ENCODERS)
        failed_hw = set(_RUNTIME_FAILED_HWACCELS)
    usable_vendors = [
        v for v in (caps.get("usableVendors") or [])
        if v in _VENDOR_PROFILES and _VENDOR_PROFILES[v].encoder not in failed
    ]

    if not enabled or pref == "software":
        active = _SOFTWARE_PROFILE
    else:
        ordered = ([pref] + [v for v in usable_vendors if v != pref]) if pref in usable_vendors else usable_vendors
        active = next((_VENDOR_PROFILES[v] for v in ordered), _SOFTWARE_PROFILE)

    usable_hw = [h for h in (caps.get("usableHwaccels") or []) if h not in failed_hw]
    decode_hw = ""
    if active.is_hardware and usable_hw:
        if active.vendor == "nvidia" and "cuda" in usable_hw:
            decode_hw = "cuda"
        elif active.vendor == "intel" and "qsv" in usable_hw:
            decode_hw = "qsv"
        elif "d3d11va" in usable_hw:
            decode_hw = "d3d11va"
        else:
            decode_hw = usable_hw[0]

    active_adapter = ""
    if active.is_hardware:
        matched = next((a["name"] for a in adapters if a.get("vendor") == active.vendor), "")
        active_adapter = matched or active.vendor.upper()
    elif adapters:
        active_adapter = next((a["name"] for a in adapters if a.get("primary")), adapters[0]["name"])

    if not enabled or pref == "software":
        status_code = "DISABLED"
    elif active.is_hardware:
        status_code = f"ACTIVE_{active.backend.upper()}"
    else:
        status_code = "FALLBACK_SOFTWARE"

    return {
        "enabled": enabled,
        "preferredVendor": pref,
        "activeBackend": active.backend,
        "activeVendor": active.vendor,
        "activeEncoder": active.encoder,
        "activeDecodeHwaccel": decode_hw,
        "hardwareAvailable": bool(usable_vendors),
        "fallbackActive": bool(enabled and not active.is_hardware),
        "statusCode": status_code,
        "activeAdapterName": active_adapter,
        "adapters": adapters,
        "supportedVendors": usable_vendors,
        "ffmpegThreads": optimal_ffmpeg_threads(),
    }


def mark_encoder_failed(encoder_name: str, reason: str = "") -> None:
    """运行期某硬件编码器异常时立即标记降级，后续任务自动跳过并回退软件或次选方案。"""
    if not encoder_name or encoder_name == "libx264":
        return
    with _PROBE_LOCK:
        _RUNTIME_FAILED_ENCODERS.add(encoder_name)
    logger.warning("硬件编码器 %s 运行异常，已切换回退路径: %s", encoder_name, reason[:160])


def mark_hwaccel_failed(hwaccel_name: str) -> None:
    if hwaccel_name:
        with _PROBE_LOCK:
            _RUNTIME_FAILED_HWACCELS.add(hwaccel_name)


def clear_probe_cache() -> None:
    with _PROBE_LOCK:
        _MEM_CACHE.clear()
        _RUNTIME_FAILED_ENCODERS.clear()
        _RUNTIME_FAILED_HWACCELS.clear()


def resolve_h264_encode_attempts(ffmpeg_path: Optional[str] = None) -> List[EncodeProfile]:
    """按配置偏好与探测结果返回本次视频编码尝试顺序（硬件方案在前，始终以 libx264 兜底）。"""
    enabled = bool(getattr(config, "gpu_acceleration_enabled", True))
    pref = str(getattr(config, "gpu_preferred_vendor", "auto") or "auto").strip().lower()
    if not enabled or pref == "software":
        return [_SOFTWARE_PROFILE]

    probe_all = pref in ("intel", "amd")
    caps = probe_hardware_capabilities(ffmpeg_path, probe_all=probe_all)
    usable = list(caps.get("usableVendors") or [])
    if pref in _VENDOR_PROFILES and pref in usable:
        ordered = [pref] + [v for v in usable if v != pref]
    else:
        ordered = usable

    attempts: List[EncodeProfile] = []
    with _PROBE_LOCK:
        failed = set(_RUNTIME_FAILED_ENCODERS)
    for vendor in ordered:
        prof = _VENDOR_PROFILES.get(vendor)
        if prof and prof.encoder not in failed:
            attempts.append(prof)
    attempts.append(_SOFTWARE_PROFILE)
    return attempts


def resolve_hwaccel_decode_args(ffmpeg_path: Optional[str] = None) -> List[str]:
    """当 GPU 加速开启且本机解码后端可用时返回 FFmpeg 硬件解码参数。"""
    enabled = bool(getattr(config, "gpu_acceleration_enabled", True))
    pref = str(getattr(config, "gpu_preferred_vendor", "auto") or "auto").strip().lower()
    if not enabled or pref == "software":
        return []
    caps = probe_hardware_capabilities(ffmpeg_path, probe_all=False)
    usable_hw = list(caps.get("usableHwaccels") or [])
    with _PROBE_LOCK:
        usable_hw = [h for h in usable_hw if h not in _RUNTIME_FAILED_HWACCELS]
    if not usable_hw:
        return []
    attempts = resolve_h264_encode_attempts(ffmpeg_path)
    primary = attempts[0] if attempts else _SOFTWARE_PROFILE
    if primary.vendor == "nvidia" and "cuda" in usable_hw:
        preferred_hw = "cuda"
    elif primary.vendor == "intel" and "qsv" in usable_hw:
        preferred_hw = "qsv"
    elif "d3d11va" in usable_hw:
        preferred_hw = "d3d11va"
    else:
        preferred_hw = usable_hw[0]
    return ["-hwaccel", preferred_hw]


def get_gpu_status(ffmpeg_path: Optional[str] = None, force_refresh: bool = False) -> dict:
    """获取用于设置页展示与诊断接口的完整 GPU 硬件加速状态快照。"""
    enabled = bool(getattr(config, "gpu_acceleration_enabled", True))
    pref = str(getattr(config, "gpu_preferred_vendor", "auto") or "auto").strip().lower()
    probe_all = force_refresh or pref in ("intel", "amd")
    caps = probe_hardware_capabilities(ffmpeg_path, force_refresh=force_refresh, probe_all=probe_all)
    return _format_gpu_status(enabled, pref, caps)


def start_background_gpu_warmup() -> None:
    """保留接口兼容性；延迟按需探测以确保服务冷启动零阻塞与零额外显存预占。"""
    return None
