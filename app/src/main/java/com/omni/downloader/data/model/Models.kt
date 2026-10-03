package com.omni.downloader.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 下载类型枚举
 */
@Serializable
enum class DownloadType(val label: String, val description: String) {
    VIDEO_WITH_AUDIO("音画合流", "下载完整高清视频，自动合并最佳音轨"),
    VIDEO_ONLY("仅无音频画面", "仅下载纯画面视频（无声），适合剪辑或背景素材"),
    AUDIO_ONLY("直接提取音频", "抽取最高保真音轨并转码为音频文件"),
    GIF("动图 (GIF)", "智能识别或转换为高画质动图文件，免去播放器即开即看"),
    COVER("保存封面", "下载并保存当前视频的高清原图封面至系统相册")
}

/**
 * 支持导出的音频格式
 */
@Serializable
enum class AudioFormat(val ext: String, val label: String) {
    MP3("mp3", "MP3 格式 (高兼容性)"),
    M4A("m4a", "M4A 格式 (高音质 AAC)"),
    FLAC("flac", "FLAC 格式 (无损音频)"),
    OPUS("opus", "OPUS 格式 (超高压缩)")
}

/**
 * 格式项详情（用于用户在弹窗中点选不同清晰度或格式）
 */
@Serializable
data class FormatOption(
    val formatId: String,
    val resolutionLabel: String, // 如 1080p, 720p, 480p, 360p, 4K, GIF
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val ext: String = "mp4",
    val approximateSize: String = "",
    val isVideoOnly: Boolean = false,
    val isAudioOnly: Boolean = false,
    val note: String = ""
)

/**
 * 视频解析出来的元数据
 */
@Serializable
data class VideoMetadata(
    val url: String,
    val title: String,
    val author: String = "",
    val durationText: String = "",
    val thumbnailUrl: String = "",
    val siteName: String = "", // YouTube, Bilibili, X (Twitter), Other
    val isGif: Boolean = false,
    val availableVideoFormats: List<FormatOption> = emptyList(),
    val availableAudioFormats: List<FormatOption> = emptyList(),
    val multiMediaList: List<VideoMetadata> = emptyList() // 包含推文中的全部独立视频
)

/**
 * 下载任务状态
 */
@Serializable
enum class TaskStatus(val label: String) {
    PENDING("等待中"),
    DOWNLOADING("正在下载"),
    PROCESSING("正在合并封装"),
    COMPLETED("下载完成"),
    FAILED("下载失败"),
    CANCELLED("已取消")
}

/**
 * 单个下载任务实体（支持序列化持久化）
 */
@Immutable
@Serializable
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val downloadType: DownloadType,
    val selectedResolution: String = "", // 如 "1080p"
    val audioFormat: AudioFormat = AudioFormat.MP3,
    var status: TaskStatus = TaskStatus.PENDING,
    var progress: Float = 0f, // 0.0 - 100.0
    var speedText: String = "",
    var etaText: String = "",
    var fileSizeText: String = "",
    var localFilePath: String = "",
    var errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // === 合集与多视频扩展字段 ===
    val collectionId: String? = null,
    val collectionTitle: String? = null,
    val episodeIndex: Int = 0,
    val episodeTotal: Int = 0
)
