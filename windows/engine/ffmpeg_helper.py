"""
OmniDownloader FFmpeg 音视频处理工具 (Windows 移植版)
提供音视频轨混流、纯画面提取、高保真音频转码抽取与 GIF 动图生成
"""

import os
import sys
import shutil
import subprocess
import asyncio
from pathlib import Path
from typing import Optional, List
from contextvars import ContextVar

cancel_context = ContextVar("omni_ffmpeg_cancel", default=None)


def get_ffmpeg_path() -> Optional[str]:
    """
    智能解析 FFmpeg 二进制路径：
    1. 优先检查打包运行时目录或应用根目录的 bin/ffmpeg.exe
    2. 检查 Python imageio_ffmpeg 自带的二进制
    3. 检查系统环境变量 PATH 中的 ffmpeg
    """
    # 兼容 PyInstaller 打包模式
    candidate_dirs = []
    if getattr(sys, 'frozen', False):
        if hasattr(sys, '_MEIPASS'):
            candidate_dirs.append(Path(sys._MEIPASS))
        candidate_dirs.append(Path(sys.executable).parent)
        candidate_dirs.append(Path(sys.executable).parent / "_internal")

    base_dir = Path(__file__).parent.parent
    candidate_dirs.append(base_dir)

    for c_dir in candidate_dirs:
        p = c_dir / "bin" / "ffmpeg.exe"
        if p.exists():
            return str(p.resolve())

    # 2. 检查 imageio_ffmpeg
    try:
        import imageio_ffmpeg
        exe = imageio_ffmpeg.get_ffmpeg_exe()
        if exe and os.path.exists(exe):
            return exe
    except Exception:
        pass

    # 3. 检查 PATH
    sys_ffmpeg = shutil.which("ffmpeg")
    if sys_ffmpeg:
        return sys_ffmpeg

    return None


class FFmpegHelper:
    """FFmpeg 异步操作封装"""

    @classmethod
    def is_available(cls) -> bool:
        return get_ffmpeg_path() is not None

    @classmethod
    async def _run_command(cls, args: List[str]) -> bool:
        ffmpeg_exe = get_ffmpeg_path()
        if not ffmpeg_exe:
            raise RuntimeError("未检测到可用的 FFmpeg 组件，请安装或检查配置")

        full_cmd = [ffmpeg_exe] + args
        # Windows 下隐藏控制台黑框 (CREATE_NO_WINDOW)
        creation_flags = 0x08000000 if sys.platform == "win32" else 0

        proc = await asyncio.create_subprocess_exec(
            *full_cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            creationflags=creation_flags
        )
        pending = asyncio.create_task(proc.communicate())
        deadline = asyncio.get_running_loop().time() + 1800
        try:
            cancelled = cancel_context.get()
            if cancelled is None:
                stdout, stderr = await asyncio.wait_for(pending, timeout=1800)
            else:
                while True:
                    if cancelled.is_set():
                        raise asyncio.CancelledError()
                    remaining = deadline - asyncio.get_running_loop().time()
                    if remaining <= 0:
                        raise asyncio.TimeoutError()
                    try:
                        stdout, stderr = await asyncio.wait_for(asyncio.shield(pending), timeout=min(0.4, remaining))
                        break
                    except asyncio.TimeoutError:
                        if asyncio.get_running_loop().time() >= deadline:
                            raise
                        continue
        except (asyncio.CancelledError, asyncio.TimeoutError):
            await cls._terminate_process_tree(proc)
            try:
                await pending
            except Exception:
                pass
            raise
        if proc.returncode != 0:
            err_msg = stderr.decode("utf-8", errors="ignore")
            print(f"[FFmpeg Error] 命令执行失败: {err_msg[:300]}")
            return False
        return True

    @classmethod
    async def _terminate_process_tree(cls, proc) -> None:
        """终止 FFmpeg 子进程及其可能派生的进程树，确保取消后资源与文件句柄立即释放。"""
        try:
            if proc.returncode is not None:
                return
            if sys.platform == "win32" and getattr(proc, "pid", None):
                try:
                    await asyncio.to_thread(
                        subprocess.run,
                        ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                        capture_output=True,
                        timeout=3,
                        creationflags=0x08000000,
                    )
                except Exception:
                    pass
            if proc.returncode is None:
                proc.kill()
        except Exception:
            pass

    @classmethod
    async def _encode_h264_with_fallback(
        cls,
        input_args: List[str],
        output_path: str,
        *,
        vf_prefix: str = "pad=ceil(iw/2)*2:ceil(ih/2)*2",
        extra_output_args: Optional[List[str]] = None,
        vfr_no_bframes: bool = False,
        allow_hardware: bool = True,
        use_hwaccel_decode: bool = False,
    ) -> bool:
        """优先尝试 GPU 硬件 H.264 编码，任一步异常自动回退软件 libx264。"""
        from engine.gpu_accel import (
            mark_encoder_failed,
            mark_hwaccel_failed,
            resolve_h264_encode_attempts,
            resolve_hwaccel_decode_args,
        )

        ffmpeg_exe = get_ffmpeg_path()
        attempts = resolve_h264_encode_attempts(ffmpeg_exe)
        if not allow_hardware:
            attempts = [a for a in attempts if not a.is_hardware] or attempts[-1:]

        hw_decode = resolve_hwaccel_decode_args(ffmpeg_exe) if (allow_hardware and use_hwaccel_decode) else []
        for idx, profile in enumerate(attempts):
            vf_filter = f"{vf_prefix},format={profile.pix_fmt}" if vf_prefix else f"format={profile.pix_fmt}"
            decode_prefix = hw_decode if profile.is_hardware else []
            args = [
                "-y",
                *decode_prefix,
                *input_args,
                "-vf", vf_filter,
                *profile.build_encode_args(vfr_no_bframes=vfr_no_bframes),
                *(extra_output_args or []),
                output_path,
            ]
            if await cls._run_command(args):
                return True
            if profile.is_hardware:
                if decode_prefix and len(decode_prefix) == 2:
                    mark_hwaccel_failed(decode_prefix[1])
                if idx + 1 < len(attempts):
                    mark_encoder_failed(profile.encoder, "runtime encode error")
        return False

    @classmethod
    async def encode_ugoira_video(cls, concat_path: Path | str, duration: float, output_path: Path | str) -> bool:
        """编码 Pixiv Ugoira 动图序列（支持 NVENC/QSV/AMF 硬件编码并自动回退软件）。"""
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        return await cls._encode_h264_with_fallback(
            ["-safe", "0", "-f", "concat", "-i", str(concat_path), "-t", f"{duration + .001:.3f}", "-fps_mode", "vfr"],
            str(output_path),
            vf_prefix="pad=ceil(iw/2)*2:ceil(ih/2)*2",
            extra_output_args=["-movflags", "+faststart"],
            vfr_no_bframes=True,
            allow_hardware=True,
            use_hwaccel_decode=False,
        )

    @classmethod
    async def merge_video_and_audio(cls, video_path: str, audio_path: str, output_path: str) -> bool:
        """
        音视频轨合并 (Dash 混流)
        使用 -c copy 毫秒级无损合并并添加 faststart 标记
        """
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        args = [
            "-y",
            "-i", video_path,
            "-i", audio_path,
            "-c", "copy",
            "-movflags", "+faststart",
            output_path
        ]
        return await cls._run_command(args)

    @classmethod
    async def strip_audio(cls, video_path: str, output_path: str) -> bool:
        """纯视频画面提取（去声轨）"""
        from engine.gif_media import inspect_gif
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        if inspect_gif(video_path): return await cls.gif_to_video(video_path, output_path)
        args = [
            "-y",
            "-i", video_path,
            "-an",
            "-c:v", "copy",
            "-movflags", "+faststart",
            output_path
        ]
        return await cls._run_command(args)

    @classmethod
    async def extract_audio(cls, input_path: str, output_path: str, audio_format: str = "mp3") -> bool:
        """提取并转码为独立高音质音频文件"""
        from engine.gpu_accel import optimal_ffmpeg_threads
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        t = str(optimal_ffmpeg_threads())
        fmt = audio_format.lower()
        if fmt in ["m4a", "aac"]:
            args = ["-y", "-threads", t, "-i", input_path, "-vn", "-c:a", "aac", "-b:a", "320k", output_path]
        elif fmt == "flac":
            args = ["-y", "-threads", t, "-i", input_path, "-vn", "-c:a", "flac", output_path]
        elif fmt == "opus":
            args = ["-y", "-threads", t, "-i", input_path, "-vn", "-c:a", "libopus", "-b:a", "192k", output_path]
        else:  # mp3
            args = ["-y", "-threads", t, "-i", input_path, "-vn", "-c:a", "libmp3lame", "-q:a", "0", output_path]
        return await cls._run_command(args)

    @classmethod
    async def gif_to_video(cls, source: str, output: str) -> bool:
        from engine.gif_media import inspect_gif, verify_gif
        from engine.media_verifier import probe
        verify_gif(source)
        if Path(source).resolve() == Path(output).resolve(): return False
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        meta = inspect_gif(source)
        # NVENC/QSV 硬件编码存在最小分辨率门限；极小尺寸直接走软件路径以免触发无意义的硬件重试
        allow_hw = bool(meta and meta.get("width", 0) >= 160 and meta.get("height", 0) >= 120)
        success = await cls._encode_h264_with_fallback(
            ['-ignore_loop', '1', '-i', source],
            output,
            vf_prefix='pad=ceil(iw/2)*2:ceil(ih/2)*2',
            extra_output_args=['-vsync', 'vfr', '-an', '-movflags', '+faststart', '-f', 'mp4'],
            vfr_no_bframes=False,
            allow_hardware=allow_hw,
            use_hwaccel_decode=False,
        )
        if success:
            info = probe(output)
            if info['width'] > 0 and info['height'] > 0 and info['duration'] > 0:
                with open(output, 'rb') as file:
                    if file.read(12)[4:8] == b'ftyp': return True
        Path(output).unlink(missing_ok=True)
        return False

    @classmethod
    async def convert_to_gif(cls, video_path: str, output_path: str, max_duration: int = 15) -> bool:
        """
        将视频转换为高画质 GIF 动图
        使用调色板分析 (palettegen / paletteuse) 消除色斑
        """
        from engine.gif_media import inspect_gif, verify_gif
        import tempfile
        source, output = Path(video_path), Path(output_path)
        if source.resolve() == output.resolve():
            try: verify_gif(source); return True
            except RuntimeError: return False
        output.parent.mkdir(parents=True, exist_ok=True)
        if inspect_gif(source):
            try:
                verify_gif(source)
                shutil.copyfile(source, output)
                verify_gif(output)
                return True
            except (RuntimeError, OSError): return False
        handle, palette_temp = tempfile.mkstemp(prefix='gif-palette-', suffix='.png', dir=output.parent)
        os.close(handle)
        scale = "fps=15,scale='min(480,iw)':-1:flags=lanczos"
        try:
            output.unlink(missing_ok=True)
            common = ['-y', '-t', str(max_duration), '-i', str(source)]
            palette = await cls._run_command(common + ['-vf', scale + ',palettegen=stats_mode=diff',
                '-frames:v', '1', '-update', '1', palette_temp])
            success = False
            if palette:
                success = await cls._run_command(common + ['-i', palette_temp, '-filter_complex',
                    '[0:v]' + scale + '[v];[v][1:v]paletteuse=dither=bayer',
                    '-an', '-c:v', 'gif', '-loop', '0', '-f', 'gif', str(output)])
            if success:
                try: verify_gif(output); return True
                except RuntimeError: pass
            output.unlink(missing_ok=True)
            success = await cls._run_command(common + ['-vf', scale, '-an', '-pix_fmt', 'rgb8',
                '-c:v', 'gif', '-loop', '0', '-f', 'gif', str(output)])
            if success:
                try: verify_gif(output); return True
                except RuntimeError: pass
            output.unlink(missing_ok=True)
            return False
        finally:
            Path(palette_temp).unlink(missing_ok=True)
