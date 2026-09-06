package com.omni.downloader.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * TikTok 原生轻量直接解析器
 * 提供免登录、绕过官方 WAF 拦截的极速无水印视频元数据嗅探与流式下载
 */
object TikTokDirectExtractor {

    private const val TAG = "TikTokExtractor"

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // 缓存已解析出的直链以供下载时直接复用
    private val directStreamCache = ConcurrentHashMap<String, DirectStreamInfo>()

    data class DirectStreamInfo(
        val videoUrl: String,
        val audioUrl: String = "",
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * 判断是否属于 TikTok 链接
     */
    fun isSupported(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com")
    }

    /**
     * 解析 TikTok 视频元数据与直链
     */
    suspend fun extract(rawUrl: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = followRedirects(rawUrl)
            Log.d(TAG, "TikTok 解析重定向目标: $resolvedUrl")

            // 提取数字视频 ID
            val idMatcher = Pattern.compile("""/video/(\d+)""").matcher(resolvedUrl)
            val videoId = if (idMatcher.find()) idMatcher.group(1) ?: "" else ""

            val targetQueryUrl = if (videoId.isNotEmpty()) {
                "https://www.tiktok.com/@user/video/$videoId"
            } else {
                resolvedUrl
            }

            val encoded = URLEncoder.encode(targetQueryUrl, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"

            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", MOBILE_UA)
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("TikWM 返回为空响应"))
            val json = JSONObject(body)

            if (json.optInt("code", -1) != 0) {
                val msg = json.optString("msg", "解析失败")
                return@withContext Result.failure(Exception("TikTok 直连解析接口返回错误: $msg"))
            }

            val data = json.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("未获取到 TikTok 视频详情数据"))

            val titleRaw = data.optString("title", "").trim()
            val title = if (titleRaw.isNotEmpty()) titleRaw else if (videoId.isNotEmpty()) "TikTok视频_$videoId" else "TikTok短视频"
            val author = data.optJSONObject("author")?.optString("nickname", "TikTok用户") ?: "TikTok用户"
            val duration = data.optInt("duration", 0)
            val cover = data.optString("cover", data.optString("origin_cover", ""))

            val playUrl = data.optString("play", "")
            val wmPlayUrl = data.optString("wmplay", "")
            val musicUrl = data.optString("music", "")

            val finalVideoUrl = if (playUrl.isNotEmpty()) playUrl else wmPlayUrl
            if (finalVideoUrl.isEmpty()) {
                return@withContext Result.failure(Exception("未能从 TikTok 接口提取到有效视频流"))
            }

            val videoFormats = mutableListOf<FormatOption>()
            videoFormats.add(
                FormatOption(
                    formatId = finalVideoUrl,
                    resolutionLabel = if (playUrl.isNotEmpty()) "原画 (无水印)" else "原画 (标清)",
                    ext = "mp4",
                    note = "极速直连原画"
                )
            )

            val audioFormats = mutableListOf<FormatOption>()
            if (musicUrl.isNotEmpty()) {
                audioFormats.add(
                    FormatOption(
                        formatId = musicUrl,
                        resolutionLabel = "独立音频 (MP3)",
                        ext = "mp3",
                        isAudioOnly = true,
                        note = "高品质原声"
                    )
                )
            }

            val metadata = VideoMetadata(
                url = resolvedUrl,
                title = title,
                author = author,
                durationText = if (duration > 0) "${duration}秒" else "",
                thumbnailUrl = cover,
                siteName = "TikTok",
                availableVideoFormats = videoFormats,
                availableAudioFormats = audioFormats
            )

            val streamInfo = DirectStreamInfo(
                videoUrl = finalVideoUrl,
                audioUrl = musicUrl,
                headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.tiktok.com/")
            )
            directStreamCache[resolvedUrl] = streamInfo
            directStreamCache[rawUrl] = streamInfo
            if (videoId.isNotEmpty()) {
                directStreamCache["https://www.tiktok.com/@user/video/$videoId"] = streamInfo
            }

            Result.success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "TikTok 直连解析异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 执行 TikTok 原生直连极速流式下载
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val streamInfo = directStreamCache[cleanUrl] ?: directStreamCache[task.url]

        val isAudio = task.downloadType == DownloadType.AUDIO_ONLY
        val targetDownloadUrl = if (isAudio) {
            if (streamInfo?.audioUrl?.isNotEmpty() == true) {
                streamInfo.audioUrl
            } else if (task.selectedResolution.startsWith("http")) {
                task.selectedResolution
            } else {
                streamInfo?.videoUrl ?: ""
            }
        } else {
            if (streamInfo != null && streamInfo.videoUrl.isNotEmpty()) {
                streamInfo.videoUrl
            } else if (task.selectedResolution.startsWith("http")) {
                task.selectedResolution
            } else {
                val reExtract = extract(cleanUrl)
                if (reExtract.isSuccess) {
                    reExtract.getOrNull()?.availableVideoFormats?.firstOrNull()?.formatId ?: ""
                } else {
                    ""
                }
            }
        }

        if (targetDownloadUrl.isEmpty() || !targetDownloadUrl.startsWith("http")) {
            return@withContext Result.failure(Exception("未找到有效的 TikTok 下载地址"))
        }

        val cleanTitle = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        val ext = if (isAudio) "mp3" else "mp4"
        val tempFile = File(stagingDir, "tiktok_${task.id}.$ext")
        val finalFile = File(publicDir, "$cleanTitle.$ext")

        try {
            onProgressUpdate(5f, "", "准备极速下载...", TaskStatus.DOWNLOADING)

            val reqBuilder = Request.Builder().url(targetDownloadUrl)
            streamInfo?.headers?.forEach { (k, v) ->
                reqBuilder.header(k, v)
            }
            if (streamInfo?.headers?.containsKey("User-Agent") != true) {
                reqBuilder.header("User-Agent", MOBILE_UA)
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP 请求失败: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("响应体为空"))
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            var lastReportTime = SystemClock.elapsedRealtime()
            var bytesSinceLastReport = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        bytesSinceLastReport += read

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReportTime >= 400) {
                            val timeDiffSec = (now - lastReportTime) / 1000f
                            val speedBytesPerSec = if (timeDiffSec > 0) bytesSinceLastReport / timeDiffSec else 0f
                            val speedStr = formatSpeed(speedBytesPerSec)

                            val progress = if (totalBytes > 0) {
                                (downloadedBytes.toFloat() / totalBytes * 100f).coerceIn(0f, 99f)
                            } else 50f

                            val etaStr = if (totalBytes > downloadedBytes && speedBytesPerSec > 0) {
                                val remainingSec = ((totalBytes - downloadedBytes) / speedBytesPerSec).toLong()
                                formatEta(remainingSec)
                            } else ""

                            onProgressUpdate(progress, speedStr, etaStr, TaskStatus.DOWNLOADING)
                            lastReportTime = now
                            bytesSinceLastReport = 0L
                        }
                    }
                    output.flush()
                }
            }

            onProgressUpdate(99f, "", "正在保存到媒体库...", TaskStatus.PROCESSING)
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()

            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
            } catch (ignored: Exception) {}

            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            Result.success(finalFile.absolutePath)
        } catch (e: Exception) {
            tempFile.delete()
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            Result.failure(e)
        }
    }

    private fun followRedirects(startUrl: String): String {
        return try {
            val req = Request.Builder()
                .url(startUrl)
                .header("User-Agent", MOBILE_UA)
                .build()
            val resp = httpClient.newCall(req).execute()
            resp.request.url.toString()
        } catch (e: Exception) {
            startUrl
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.getDefault(), "%.1f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.getDefault(), "%d B/s", bytesPerSec.toInt())
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }
}
