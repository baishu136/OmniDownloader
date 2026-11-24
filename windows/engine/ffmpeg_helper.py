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
            while True:
                cancelled = cancel_context.get()
                if cancelled is not None and cancelled.is_set(): raise asyncio.CancelledError()
                if asyncio.get_running_loop().time() >= deadline: raise asyncio.TimeoutError()
                try:
                    stdout, stderr = await asyncio.wait_for(asyncio.shield(pending), timeout=.25)
                    break
                except asyncio.TimeoutError: continue
        except (asyncio.CancelledError, asyncio.TimeoutError):
            if proc.returncode is None: proc.kill()
            await pending
            raise
        if proc.returncode != 0:
            err_msg = stderr.decode("utf-8", errors="ignore")
            print(f"[FFmpeg Error] 命令执行失败: {err_msg[:300]}")
            return False
        return True

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
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        fmt = audio_format.lower()
        if fmt in ["m4a", "aac"]:
            args = ["-y", "-i", input_path, "-vn", "-c:a", "aac", "-b:a", "320k", output_path]
        elif fmt == "flac":
            args = ["-y", "-i", input_path, "-vn", "-c:a", "flac", output_path]
        elif fmt == "opus":
            args = ["-y", "-i", input_path, "-vn", "-c:a", "libopus", "-b:a", "192k", output_path]
        else:  # mp3
            args = ["-y", "-i", input_path, "-vn", "-c:a", "libmp3lame", "-q:a", "0", output_path]
        return await cls._run_command(args)

    @classmethod
    async def gif_to_video(cls, source: str, output: str) -> bool:
        from engine.gif_media import verify_gif
        from engine.media_verifier import probe
        verify_gif(source)
        if Path(source).resolve() == Path(output).resolve(): return False
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        success = await cls._run_command(['-y', '-ignore_loop', '1', '-i', source,
            '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-vsync', 'vfr', '-an',
            '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-f', 'mp4', output])
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
