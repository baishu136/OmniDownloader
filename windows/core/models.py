"""
OmniDownloader 核心数据模型 (Windows 移植版)
定义与 Android 版对应的枚举、数据类及序列化模型
"""

from enum import Enum
from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field
import time


class DownloadType(str, Enum):
    """下载类型枚举"""
    VIDEO_WITH_AUDIO = "VIDEO_WITH_AUDIO"  # 音画合流
    VIDEO_ONLY = "VIDEO_ONLY"              # 仅纯画面
    AUDIO_ONLY = "AUDIO_ONLY"              # 直接提取音频
    GIF = "GIF"                            # 动图 (GIF)
    COVER = "COVER"                        # 保存高清封面


class AudioFormat(str, Enum):
    """支持导出的音频格式"""
    MP3 = "mp3"
    M4A = "m4a"
    FLAC = "flac"
    OPUS = "opus"


class FormatOption(BaseModel):
    """清晰度或格式选项详情"""
    format_id: str = Field(..., alias="formatId")
    resolution_label: str = Field(..., alias="resolutionLabel")  # 如 4K, 1080p, 720p
    width: int = 0
    height: int = 0
    fps: int = 0
    ext: str = "mp4"
    approximate_size: str = Field("", alias="approximateSize")
    is_video_only: bool = Field(False, alias="isVideoOnly")
    is_audio_only: bool = Field(False, alias="isAudioOnly")
    note: str = ""

    class Config:
        populate_by_name = True


class VideoMetadata(BaseModel):
    """视频解析出来的元数据"""
    url: str
    title: str
    author: str = ""
    duration_text: str = Field("", alias="durationText")
    thumbnail_url: str = Field("", alias="thumbnailUrl")
    site_name: str = Field("", alias="siteName")  # 哔哩哔哩, YouTube, 抖音, TikTok, X (Twitter) 等
    is_gif: bool = Field(False, alias="isGif")
    available_video_formats: List[FormatOption] = Field(default_factory=list, alias="availableVideoFormats")
    available_audio_formats: List[FormatOption] = Field(default_factory=list, alias="availableAudioFormats")
    multi_media_list: List["VideoMetadata"] = Field(default_factory=list, alias="multiMediaList")  # 合集或分P列表

    class Config:
        populate_by_name = True


class TaskStatus(str, Enum):
    """下载任务状态"""
    PENDING = "PENDING"          # 等待中
    DOWNLOADING = "DOWNLOADING"  # 正在下载
    PROCESSING = "PROCESSING"    # 正在合并封装 / 转码
    COMPLETED = "COMPLETED"      # 下载完成
    FAILED = "FAILED"            # 下载失败
    CANCELLED = "CANCELLED"      # 已取消


class DownloadTask(BaseModel):
    """单个下载任务实体"""
    id: str
    url: str
    title: str
    author: str = ""
    thumbnail_url: str = Field("", alias="thumbnailUrl")
    download_type: DownloadType = Field(..., alias="downloadType")
    selected_resolution: str = Field("", alias="selectedResolution")
    audio_format: AudioFormat = Field(AudioFormat.MP3, alias="audioFormat")
    status: TaskStatus = TaskStatus.PENDING
    progress: float = 0.0  # 0.0 ~ 100.0
    speed_text: str = Field("", alias="speedText")
    eta_text: str = Field("", alias="etaText")
    file_size_text: str = Field("", alias="fileSizeText")
    local_file_path: str = Field("", alias="localFilePath")
    error_message: str = Field("", alias="errorMessage")
    created_at: int = Field(default_factory=lambda: int(time.time() * 1000), alias="createdAt")
    
    # 合集与分P扩展属性
    collection_id: Optional[str] = Field(None, alias="collectionId")
    collection_title: Optional[str] = Field(None, alias="collectionTitle")
    episode_index: int = Field(0, alias="episodeIndex")
    episode_total: int = Field(0, alias="episodeTotal")

    class Config:
        populate_by_name = True
