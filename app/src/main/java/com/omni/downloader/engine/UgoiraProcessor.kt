package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import com.omni.downloader.engine.SafeLog as Log
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pixiv Ugoira 帧资源处理。
 *
 * 只按 ugoira_meta 返回的帧序与 delay 读取，不依赖 ZIP 条目顺序或字典序。
 * 导出类型由任务选择决定：MP4 / GIF / 原始帧资源（ZIP + JSON）。
 */
object UgoiraProcessor {

    private const val TAG = "UgoiraProcessor"
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 5000

    data class Frame(val file: String, val delayMs: Int)

    private val httpClient = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun totalDurationMs(frames: List<Frame>): Long =
        frames.sumOf { it.delayMs.coerceAtLeast(0).toLong() }

    /** 生成 FFmpeg concat 清单；末帧重复一次以让 concat 识别最后一帧的时长。 */
    fun buildConcatContent(frames: List<Frame>, framesDir: File): String {
        if (frames.isEmpty()) return ""
        return buildString {
            append("ffconcat version 1.0\n")
            frames.forEach { frame ->
                val file = File(framesDir, frame.file).absolutePath.replace("'", "'\\''")
                append("file '").append(file).append("'\n")
                append("option framerate 1000\n")
                append("duration ").append(String.format(Locale.US, "%.3f", frame.delayMs / 1000.0)).append('\n')
            }
            val last = File(framesDir, frames.last().file).absolutePath.replace("'", "'\\''")
            append("file '").append(last).append("'\noption framerate 1000\n")
        }
    }

    /** 拒绝绝对路径、盘符、路径穿越与可疑条目。 */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) return false
        if (normalized.contains("..")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return false
        val base = normalized.substringAfterLast('/')
        return base.isNotBlank() && base != "." && base != ".."
    }

    suspend fun process(
        context: Context,
        task: DownloadTask,
        zipUrl: String,
        headers: Map<String, String>,
        frames: List<Frame>,
        artworkId: String,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) {
            return@withContext Result.failure(Exception(MediaErrors.describe(MediaErrorCode.NO_MEDIA, "Ugoira 没有返回帧列表")))
        }

        val workDir = File(stagingDir, "ugoira_${task.id}").apply { mkdirs() }
        val framesDir = File(workDir, "frames").apply { mkdirs() }
        val archive = File(workDir, "ugoira.zip")

        try {
            onProgressUpdate(3f, "", "正在下载 Ugoira 帧包...", TaskStatus.DOWNLOADING)
            downloadZip(zipUrl, headers, archive) { progress ->
                onProgressUpdate(3f + progress * 0.6f, "", "正在下载 Ugoira 帧包...", TaskStatus.DOWNLOADING)
            }

            onProgressUpdate(66f, "", "正在校验并解压帧资源...", TaskStatus.PROCESSING)
            val missing = extractFrames(archive, framesDir, frames)
            if (missing.isNotEmpty()) {
                return@withContext Result.failure(
                    Exception(
                        MediaErrors.describe(
                            MediaErrorCode.POSTPROCESS_FAILED,
                            "帧包缺少 ${missing.size} 帧（例如 ${missing.first()}）"
                        )
                    )
                )
            }

            val exportType = resolveExportType(task)
            val safeTitle = task.title
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trim()
                .ifBlank { "ugoira_$artworkId" }
                .take(60)

            val finalFile = when (exportType) {
                ExportType.ZIP -> exportZip(framesDir, frames, artworkId, safeTitle, publicDir)
                ExportType.GIF -> encodeGif(context, framesDir, frames, safeTitle, publicDir, onProgressUpdate)
                ExportType.MP4 -> encodeMp4(context, framesDir, frames, safeTitle, publicDir, onProgressUpdate)
            }

            if (finalFile == null || !finalFile.exists() || finalFile.length() <= 0) {
                return@withContext Result.failure(
                    Exception(MediaErrors.describe(MediaErrorCode.POSTPROCESS_FAILED, "Ugoira 导出失败"))
                )
            }

            if (exportType == ExportType.MP4) {
                val actual = MediaVerifier.probe(finalFile) ?: error("无法读取Ugoira成品")
                require(kotlin.math.abs(actual.durationMs - totalDurationMs(frames)) <= maxOf(50L, totalDurationMs(frames) / 100)) { "Ugoira成品时长与帧清单不符" }
            }
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
            }
            framesDir.deleteRecursively()
            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            Result.success(finalFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Ugoira 处理失败", e)
            Result.failure(Exception(MediaErrors.describe(MediaErrorCode.POSTPROCESS_FAILED, e.message), e))
        }
    }

    private enum class ExportType { MP4, GIF, ZIP }

    private fun resolveExportType(task: DownloadTask): ExportType {
        val marker = "${task.selectedVideoFormatId} ${task.selectedResolution}".lowercase(Locale.ROOT)
        return when {
            marker.contains("zip") -> ExportType.ZIP
            task.downloadType == com.omni.downloader.data.model.DownloadType.GIF ||
                marker.contains("gif") -> ExportType.GIF
            else -> ExportType.MP4
        }
    }

    private fun downloadZip(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("帧包下载失败: HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("帧包响应为空")
            val total = body.contentLength()
            if (total > MAX_ARCHIVE_BYTES) throw Exception("帧包超过大小限制")
            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (read > MAX_ARCHIVE_BYTES) throw Exception("帧包超过大小限制")
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250) {
                            lastReport = now
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    private fun extractFrames(archive: File, framesDir: File, frames: List<Frame>): List<String> {
        val needed = frames.map { it.file.substringAfterLast('/') }.toSet()
        val found = mutableSetOf<String>()
        var entryCount = 0
        var expandedBytes = 0L

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRY_COUNT) throw Exception("帧包条目过多")
                if (entry.isDirectory) continue
                if (!isSafeEntryName(entry.name)) throw Exception("帧包包含不安全路径: ${entry.name}")

                val base = entry.name.substringAfterLast('/')
                if (base !in needed || base in found) continue

                val output = File(framesDir, base)
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count <= 0) break
                        expandedBytes += count
                        require(expandedBytes <= 512L * 1024 * 1024) { "帧包解压超过限制" }
                        out.write(buffer, 0, count)
                    }
                }
                found.add(base)
            }
        }
        return needed.filter { it !in found }
    }

    private fun exportZip(
        framesDir: File,
        frames: List<Frame>,
        artworkId: String,
        safeTitle: String,
        publicDir: File
    ): File {
        val target = File(publicDir, "$safeTitle.zip")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            frames.forEach { frame ->
                val base = frame.file.substringAfterLast('/')
                val file = File(framesDir, base)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(base))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val json = JSONObject().apply {
                put("artworkId", artworkId)
                put("kind", "UGOIRA")
                put("totalDurationMs", totalDurationMs(frames))
                put("frames", org.json.JSONArray().apply {
                    frames.forEach { frame ->
                        put(JSONObject().apply {
                            put("file", frame.file)
                            put("delayMs", frame.delayMs)
                        })
                    }
                })
            }
            zip.putNextEntry(ZipEntry("frames.json"))
            zip.write(json.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return target
    }

    private suspend fun encodeMp4(
        context: Context,
        framesDir: File,
        frames: List<Frame>,
        safeTitle: String,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): File? {
        onProgressUpdate(72f, "", "正在编码 Ugoira MP4...", TaskStatus.PROCESSING)
        val concat = File(framesDir.parentFile, "concat.txt")
        concat.writeText(buildConcatContent(frames, framesDir))
        val target = File(publicDir, "$safeTitle.mp4")
        val ffmpeg = FFmpegExecutor.getFFmpegBinary(context) ?: return null

        val base = listOf(
            ffmpeg.absolutePath, "-y",
            "-f", "concat", "-safe", "0", "-i", concat.absolutePath,
            "-vsync", "vfr", "-t", String.format(Locale.US, "%.3f", (totalDurationMs(frames) + 1) / 1000.0),
            "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart"
        )
        val h264 = base + listOf("-c:v", "libx264", "-bf", "0", "-preset", "medium", "-crf", "18", target.absolutePath)
        val result = FFmpegExecutor.executeCommand(context, h264)
        if (result.isSuccess && target.length() > 0) return target

        Log.w(TAG, "H.264编码失败，保留原始帧包: ${result.exceptionOrNull()?.message}")
        return null
    }

    private suspend fun encodeGif(
        context: Context,
        framesDir: File,
        frames: List<Frame>,
        safeTitle: String,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): File? {
        onProgressUpdate(72f, "", "正在编码 Ugoira GIF...", TaskStatus.PROCESSING)
        val concat = File(framesDir.parentFile, "concat.txt")
        concat.writeText(buildConcatContent(frames, framesDir))
        val target = File(publicDir, "$safeTitle.gif")
        val ffmpeg = FFmpegExecutor.getFFmpegBinary(context) ?: return null

        val base = listOf(
            ffmpeg.absolutePath, "-y",
            "-f", "concat", "-safe", "0", "-i", concat.absolutePath,
            "-vsync", "vfr", "-t", String.format(Locale.US, "%.3f", (totalDurationMs(frames) + 1) / 1000.0), "-loop", "0"
        )
        val palette = base + listOf(
            "-vf", "split[s0][s1];[s0]palettegen=max_colors=256[p];[s1][p]paletteuse=dither=bayer",
            target.absolutePath
        )
        val result = FFmpegExecutor.executeCommand(context, palette)
        if (result.isSuccess && MediaVerifier.verifyGif(target) == null) return target

        Log.w(TAG, "调色板 GIF 失败，降级直接编码: ${result.exceptionOrNull()?.message}")
        val fallback = base + listOf(
            "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
            target.absolutePath
        )
        val fallbackResult = FFmpegExecutor.executeCommand(context, fallback)
        return if (fallbackResult.isSuccess && MediaVerifier.verifyGif(target) == null) target else null.also { target.delete() }
    }
}
