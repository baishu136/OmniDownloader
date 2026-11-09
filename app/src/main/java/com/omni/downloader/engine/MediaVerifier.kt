package com.omni.downloader.engine

import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.Context
import java.io.File

/**
 * 成品校验：用系统媒体元数据读取真实宽高与时长。
 *
 * 目的不是“文件存在即成功”，而是发现“用户选择 1080P，实际保存 360P”这类
 * 静默降级。Android 包内没有 ffprobe 时，MediaMetadataRetriever 能覆盖
 * MP4/MPEG-TS 等常见容器；无法探测时不误报失败。
 */
object MediaVerifier {

    data class Probe(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val hasAudio: Boolean
    )

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "flv", "ts", "m4v")

    fun isVideoFile(file: File): Boolean =
        file.extension.lowercase() in VIDEO_EXTENSIONS

    fun probe(file: File): Probe? {
        if (!file.exists() || file.length() <= 0) return null
        val retriever = MediaMetadataRetriever()
        val retrieved = try {
            retriever.setDataSource(file.absolutePath)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val swapped = width
                width = height
                height = swapped
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true) ?: false
            if (width <= 0 || height <= 0 || duration <= 0) null
            else Probe(width, height, duration, hasAudio)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        return retrieved ?: probeTracks(file)
    }

    private fun probeTracks(file: File): Probe? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var width = 0; var height = 0; var durationUs = 0L; var audio = false
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audio = true
                if (mime.startsWith("video/")) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_ROTATION) && format.getInteger(MediaFormat.KEY_ROTATION) % 180 != 0) {
                        val old = width; width = height; height = old
                    }
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
            }
            if (width > 0 && height > 0 && durationUs > 0) Probe(width, height, durationUs / 1000, audio) else null
        } catch (_: Exception) { null } finally { extractor.release() }
    }

    /** The bundled FFmpeg can inspect short loops/codecs unsupported by the OS parser. */
    suspend fun probeWithFallback(context: Context, file: File): Probe? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        probe(file)?.let { return@withContext it }
        val binary = FFmpegExecutor.getFFmpegBinary(context) ?: return@withContext null
        val log = File.createTempFile("media-probe-", ".log", context.cacheDir)
        var process: Process? = null
        try {
            val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
            val builder = ProcessBuilder(binary.path, "-hide_banner", "-i", file.path).redirectErrorStream(true).redirectOutput(log)
            builder.environment()["LD_LIBRARY_PATH"] = listOf(File(base, "ffmpeg/usr/lib"), File(base, "python/usr/lib"), File(context.applicationInfo.nativeLibraryDir)).joinToString(":") { it.path }
            process = builder.start()
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) return@withContext null
            parseProbeLog(log.readText().take(64000))
        } catch (_: Exception) { null } finally { process?.destroyForcibly(); log.delete() }
    }

    internal fun parseProbeLog(text: String): Probe? {
        val video = text.lineSequence().firstOrNull { it.contains("Video:") } ?: return null
        // Stream IDs such as [0x1] precede the actual 650x430 dimensions.
        val size = Regex("""(?<![a-zA-Z0-9])([1-9]\d{0,4})x([1-9]\d{0,4})(?![a-zA-Z0-9])""").find(video) ?: return null
        val time = Regex("""Duration:\s*(\d+):(\d+):([\d.]+)""").find(text) ?: return null
        val ms = ((time.groupValues[1].toDouble() * 3600 + time.groupValues[2].toDouble() * 60 + time.groupValues[3].toDouble()) * 1000).toLong()
        return if (ms > 0) Probe(size.groupValues[1].toInt(), size.groupValues[2].toInt(), ms, text.lineSequence().any { it.contains("Audio:") }) else null
    }

    suspend fun verifyTask(file: File, task: com.omni.downloader.data.model.DownloadTask, context: Context? = null): String? {
        if (!file.isFile || file.length() == 0L) return "[POSTPROCESS_FAILED] 成品不存在或为空"
        if (task.downloadType == com.omni.downloader.data.model.DownloadType.GIF) return verifyGif(file)
        if (!isVideoFile(file)) return null
        val result = (if (context != null) probeWithFallback(context, file) else probe(file)) ?: return "[POSTPROCESS_FAILED] 无法验证成品媒体信息"
        if (task.selectedWidth > 0 && task.selectedHeight > 0 && !task.qualitySelection.allowLowerQuality) {
            val expectedArea = task.selectedWidth.toLong() * task.selectedHeight
            if (result.width.toLong() * result.height < expectedArea * 0.98) return "[QUALITY_UNAVAILABLE] 成品尺寸低于所选源"
        }
        if (task.requireAudio && task.downloadType == com.omni.downloader.data.model.DownloadType.VIDEO_WITH_AUDIO && !result.hasAudio) return "[POSTPROCESS_FAILED] 成品缺少预期音轨"
        if (task.expectedDurationMs > 0 && task.downloadType != com.omni.downloader.data.model.DownloadType.GIF && kotlin.math.abs(result.durationMs - task.expectedDurationMs) > maxOf(2000L, task.expectedDurationMs / 100)) return "[POSTPROCESS_FAILED] 成品时长与目标不符"
        return null
    }

    @Suppress("DEPRECATION")
    internal fun verifyGif(file: File): String? {
        val info = GifInspector.inspect(file)
            ?: return "[POSTPROCESS_FAILED] GIF 文件格式无效或内容不完整，请重新下载"
        return try {
            val decoded = android.graphics.Movie.decodeFile(file.path)
            if (decoded == null || decoded.width() != info.width || decoded.height() != info.height)
                "[POSTPROCESS_FAILED] GIF 无法被 Android 图片解码器打开"
            else null
        } catch (_: Exception) { "[POSTPROCESS_FAILED] GIF 解码失败" }
    }

    /**
     * @return 不为 null 时表示成品与所选质量不符，应作为失败上报而不是显示“下载完成”。
     */
    fun verify(file: File, expectedHeight: Int): String? {
        if (expectedHeight <= 0) return null
        if (!isVideoFile(file)) return null
        val probe = probe(file) ?: return null
        if (probe.height <= 0) return null
        // 允许 10% 的容器/裁剪误差；明显低于所选档位才判定降级
        if (probe.height >= expectedHeight * 0.9) return null
        return MediaErrors.describe(
            MediaErrorCode.QUALITY_UNAVAILABLE,
            "实际成品 ${probe.height}p，低于所选 ${expectedHeight}p"
        )
    }
}
