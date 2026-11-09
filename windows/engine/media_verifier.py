import json
from pathlib import Path
import re
import shutil
import subprocess
from engine.ffmpeg_helper import get_ffmpeg_path


def probe(path):
    ffmpeg = get_ffmpeg_path()
    candidate = Path(ffmpeg).with_name('ffprobe.exe') if ffmpeg else Path('ffprobe.exe')
    ffprobe = str(candidate) if candidate.is_file() else shutil.which('ffprobe')
    flags = 0x08000000 if __import__('os').name == 'nt' else 0
    if ffprobe:
        result = subprocess.run([ffprobe, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)], capture_output=True, timeout=30, creationflags=flags)
        data = json.loads(result.stdout)
        video = next((s for s in data.get('streams', []) if s.get('codec_type') == 'video'), {})
        return {'width': video.get('width', 0), 'height': video.get('height', 0),
                'audio': any(s.get('codec_type') == 'audio' for s in data.get('streams', [])),
                'duration': float(data.get('format', {}).get('duration') or 0)}
    if not ffmpeg: raise RuntimeError('[ENGINE_UNAVAILABLE] 缺少媒体验证组件')
    result = subprocess.run([ffmpeg, '-hide_banner', '-i', str(path)], capture_output=True, timeout=30, creationflags=flags)
    text = result.stderr.decode('utf-8', 'replace')
    video = next((line for line in text.splitlines() if 'Video:' in line), '')
    size = re.search(r'\b(\d{2,5})x(\d{2,5})\b', video)
    duration = re.search(r'Duration: (\d+):(\d+):([\d.]+)', text)
    return {'width': int(size[1]) if size else 0, 'height': int(size[2]) if size else 0,
            'audio': 'Audio:' in text, 'duration': int(duration[1]) * 3600 + int(duration[2]) * 60 + float(duration[3]) if duration else 0}


def verify(path, selected=None, duration=0, require_audio=False):
    path = Path(path)
    if not path.is_file() or path.stat().st_size == 0: raise RuntimeError('[POSTPROCESS_FAILED] 成品为空')
    info = probe(path)
    if selected and info['width'] * info['height'] < (selected.get('width') or 0) * (selected.get('height') or 0) * .98:
        raise RuntimeError('[QUALITY_UNAVAILABLE] 成品尺寸低于所选源')
    if selected and not info['width']: raise RuntimeError('[POSTPROCESS_FAILED] 无法读取视频尺寸')
    if require_audio and not info['audio']: raise RuntimeError('[POSTPROCESS_FAILED] 缺少预期音轨')
    if duration and abs(info['duration'] - duration) > max(2, duration * .01): raise RuntimeError('[POSTPROCESS_FAILED] 成品时长与目标不符')
    return info
