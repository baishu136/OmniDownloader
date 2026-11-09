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
    COVER("保存封面", "下载并保存当前视频的高清原图封面至系统相册"),
    IMAGE("原始图片", "保留平台图片原始格式与动画"),
    IMAGE_ZIP("图文归档", "按作品顺序保存所选原图至 ZIP")
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
    val note: String = "",
    // 保留精确格式信息：同高度可能存在 AVC / HEVC / AV1 等多种编码
    val vcodec: String = "",
    val acodec: String = "",
    // 是否为 UI 兜底生成的占位格式（不能作为 yt-dlp 的 -f 参数）
    val isSynthetic: Boolean = false,
    val dynamicRange: String = "",
    val isUpscaled: Boolean = false,
    val sourceUrl: String = "",
    val sourceHeaders: Map<String, String> = emptyMap(),
    // Confirmed container duration of this exact playback variant, when known.
    val sourceDurationMs: Long = 0,
    // Platform bitrate ranks otherwise unknown renditions before sparse probing.
    val videoBitrateKbps: Double = 0.0,
    val dimensionsVerified: Boolean = false
)

/**
 * 质量意图。保存用户“想要什么”，而不是某一次解析的具体格式 ID。
 */
@Serializable
enum class QualityMode {
    BEST_AVAILABLE,
    MAX_HEIGHT,
    TARGET_TIER,
    EXACT_FORMAT
}

@Serializable
data class QualitySelection(
    val mode: QualityMode = QualityMode.BEST_AVAILABLE,
    val maxHeight: Int = 0,
    val targetFps: Int = 0,
    val codecPreference: String = "auto",
    val container: String = "mp4",
    val allowLowerQuality: Boolean = false,
    val dynamicRangePreference: String = "auto"
)

/** 从旧版 selectedResolution 文案推导质量意图。无法识别时退回“最高可用”。 */
fun qualitySelectionFromResolution(label: String): QualitySelection {
    val height = parseResolutionHeight(label)
    return if (height > 0) {
        QualitySelection(mode = QualityMode.MAX_HEIGHT, maxHeight = height)
    } else {
        QualitySelection(mode = QualityMode.BEST_AVAILABLE)
    }
}

fun parseResolutionHeight(label: String): Int {
    val lower = label.lowercase(java.util.Locale.ROOT)
    if (lower.contains("自适应") || lower.contains("最佳") || lower.contains("最高") ||
        lower.contains("auto") || lower.contains("best") || lower.contains("默认") ||
        lower.contains("原画")
    ) {
        return 0
    }
    if (lower.contains("8k") || lower.contains("4320")) return 4320
    if (lower.contains("6k") || lower.contains("3240")) return 3240
    if (lower.contains("5k") || lower.contains("2880")) return 2880
    if (lower.contains("4k") || lower.contains("2160")) return 2160
    if (lower.contains("2k") || lower.contains("1440")) return 1440
    return Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

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
    val multiMediaList: List<VideoMetadata> = emptyList(),
    val contentKind: String = "VIDEO",
    val mediaId: String = "",
    val expectedDurationMs: Long = 0,
    val accessState: String = "UNKNOWN",
    val accessEvidence: List<String> = emptyList(),
    val accountId: String = "",
    val accountName: String = "",
    val accountVerifiedAt: Long = 0,
    val exclusiveType: String = "UNKNOWN",
    val qualityWarning: String = "",
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0,
    val qualityEvidence: String = ""
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
    WAITING_FOR_AUTH("等待登录/验证"),
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
    // === 结构化选流（schemaVersion >= 2） ===
    val schemaVersion: Int = 0,
    val qualitySelection: QualitySelection = QualitySelection(),
    val selectedVideoFormatId: String = "",
    val selectedVideoIsMuxed: Boolean = false,
    val selectedAudioFormatId: String = "",
    val errorCode: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f, // 0.0 - 100.0
    val speedText: String = "",
    val etaText: String = "",
    val fileSizeText: String = "",
    val localFilePath: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // === 合集与多视频扩展字段 ===
    val collectionId: String? = null,
    val collectionTitle: String? = null,
    val episodeIndex: Int = 0,
    val episodeTotal: Int = 0,
    // 已解析的国内媒体源与请求头，独立于 UI 清晰度标签，并随任务持久化。
    val directMediaUrl: String = "",
    val directMediaHeaders: Map<String, String> = emptyMap(),
    val mediaId: String = "",
    val expectedDurationMs: Long = 0,
    val selectedWidth: Int = 0,
    val selectedHeight: Int = 0,
    val requireAudio: Boolean = false,
    val imagePlatform: String = "",
    val selectedImageIds: List<String> = emptyList()
)

@Serializable data class ImageResource(
    val id: String, val url: String, val width: Int = 0, val height: Int = 0,
    val original: Boolean = false, val watermark: String = "UNCONFIRMED"
)
@Serializable data class ImagePost(
    val platform: String, val url: String, val contentId: String, val title: String,
    val author: String = "", val resources: List<ImageResource> = emptyList(), val route: String = ""
)
