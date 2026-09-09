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
        stdout, stderr = await proc.communicate()
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
    async def convert_to_gif(cls, video_path: str, output_path: str, max_duration: int = 15) -> bool:
        """
        将视频转换为高画质 GIF 动图
        使用调色板分析 (palettegen / paletteuse) 消除色斑
        """
        palette_temp = output_path + ".palette.png"
        try:
            # 1. 生成全局调色板 (限制前 max_duration 秒，帧率 15fps，宽度不超过 480)
            vf_filter = "fps=15,scale=480:-1:flags=lanczos,palettegen=stats_mode=diff"
            gen_args = [
                "-y",
                "-t", str(max_duration),
                "-i", video_path,
                "-vf", vf_filter,
                palette_temp
            ]
            if not await cls._run_command(gen_args):
                # 调色板生成失败，尝试简易转换
                simple_args = [
                    "-y",
                    "-t", str(max_duration),
                    "-i", video_path,
                    "-vf", "fps=12,scale=360:-1:flags=lanczos",
                    output_path
                ]
                return await cls._run_command(simple_args)

            # 2. 应用调色板渲染高画质 GIF
            use_args = [
                "-y",
                "-t", str(max_duration),
                "-i", video_path,
                "-i", palette_temp,
                "-lavfi", "fps=15,scale=480:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                output_path
            ]
            return await cls._run_command(use_args)
        finally:
            if os.path.exists(palette_temp):
                try:
                    os.remove(palette_temp)
                except Exception:
                    pass
