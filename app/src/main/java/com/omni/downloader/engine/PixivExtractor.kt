package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.engine.SafeLog as Log
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SessionPlatforms
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pixiv Ugoira 专用解析器。
 *
 * 首版范围：单个公开 Ugoira 作品。插画、多页漫画、Sketch、FANBOX 明确返回不支持，
 * 不把它们混入视频流程。会话通过 SessionRepository 的 Pixiv 档案提供。
 */
object PixivExtractor {

    private const val TAG = "PixivExtractor"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class UgoiraMeta(
        val artworkId: String,
        val zipUrl: String,
        val frames: List<UgoiraProcessor.Frame>
    )

    private val metaCache = ConcurrentHashMap<String, UgoiraMeta>()

    suspend fun extract(context: Context, rawUrl: String): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            try {
                val artworkId = LinkNormalizer.contentId(rawUrl, LinkNormalizer.PLATFORM_PIXIV)
                    ?: return@withContext Result.failure(
                        Exception(MediaErrors.describe(MediaErrorCode.NO_MEDIA, "未能识别 Pixiv 作品 ID"))
                    )
                val illust = fetchIllust(context, artworkId).getOrElse {
                    return@withContext Result.failure(it)
                }

                check(illust.optString("id") == artworkId) { MediaErrors.describe(MediaErrorCode.MEDIA_ID_MISMATCH, "Pixiv 作品信息与请求 ID 不一致") }
                val illustType = illust.optInt("illustType", -1)
                if (illustType != 2) {
                    return@withContext Result.failure(
                        Exception(
                            MediaErrors.describe(
                                MediaErrorCode.NO_MEDIA,
                                "该作品不是 Ugoira 动图（类型 $illustType），当前仅支持动图"
                            )
                        )
                    )
                }

                val meta = fetchUgoiraMeta(context, artworkId, knownRestrictedArtwork = illust.optInt("xRestrict", 0) > 0).getOrElse {
                    return@withContext Result.failure(it)
                }
                metaCache[artworkId] = meta

                val title = illust.optString("title").ifBlank { "Pixiv Ugoira $artworkId" }
                val author = illust.optString("userName").ifBlank { "Pixiv" }
                val thumbnail = illust.optJSONObject("urls")
                    ?.optString("regular")
                    ?.ifBlank { illust.optJSONObject("urls")?.optString("original").orEmpty() }
                    .orEmpty()

                val duration = UgoiraProcessor.totalDurationMs(meta.frames)
                val formats = listOf(
                    FormatOption(
                        formatId = "ugoira_mp4",
                        resolutionLabel = "Ugoira MP4 (H.264)",
                        width = illust.optInt("width"), height = illust.optInt("height"),
                        ext = "mp4",
                        note = "${meta.frames.size} 帧 · ${duration}ms · 保留每帧时长",
                        vcodec = "h264"
                    ),
                    FormatOption(
                        formatId = "ugoira_gif",
                        resolutionLabel = "Ugoira GIF",
                        width = illust.optInt("width"), height = illust.optInt("height"),
                        ext = "gif",
                        note = "调色板 GIF，时间精度受格式限制"
                    ),
                    FormatOption(
                        formatId = "ugoira_zip",
                        resolutionLabel = "原始帧资源 (ZIP + JSON)",
                        width = illust.optInt("width"), height = illust.optInt("height"),
                        ext = "zip",
                        note = "保留平台返回的帧序与时序"
                    )
                )

                Result.success(
                    VideoMetadata(
                        url = "https://www.pixiv.net/artworks/$artworkId",
                        title = title,
                        author = author,
                        durationText = "${duration}ms",
                        thumbnailUrl = thumbnail,
                        siteName = SessionPlatforms.PIXIV,
                        expectedDurationMs = duration,
                        mediaId = artworkId,
                        availableVideoFormats = formats,
                        availableAudioFormats = emptyList()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pixiv 解析失败", e)
                Result.failure(Exception(MediaErrors.describe(MediaErrors.classify(e.message.orEmpty()), e.message), e))
            }
        }

    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (task.downloadType == com.omni.downloader.data.model.DownloadType.AUDIO_ONLY ||
                task.downloadType == com.omni.downloader.data.model.DownloadType.COVER
            ) {
                return@withContext Result.failure(
                    Exception(
                        MediaErrors.describe(
                            MediaErrorCode.NO_MEDIA,
                            "Ugoira 不支持音频提取或仅封面导出"
                        )
                    )
                )
            }
            onProgressUpdate(0f, "", "正在准备 Ugoira...", TaskStatus.DOWNLOADING)
            val artworkId = LinkNormalizer.contentId(task.url, LinkNormalizer.PLATFORM_PIXIV)
                ?: return@withContext Result.failure(
                    Exception(MediaErrors.describe(MediaErrorCode.NO_MEDIA, "任务缺少 Pixiv 作品 ID"))
                )
            val meta = fetchUgoiraMeta(context, artworkId).getOrElse {
                return@withContext Result.failure(it)
            }
            metaCache[artworkId] = meta

            val headers = baseHeaders(null) + mapOf("Referer" to "https://www.pixiv.net/")
            UgoiraProcessor.process(
                context = context,
                task = task,
                zipUrl = meta.zipUrl,
                headers = headers,
                frames = meta.frames,
                artworkId = artworkId,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pixiv 下载失败", e)
            Result.failure(Exception(MediaErrors.describe(MediaErrorCode.POSTPROCESS_FAILED, e.message), e))
        }
    }

    private fun fetchIllust(context: Context, artworkId: String): Result<JSONObject> =
        requestJson(context, "https://www.pixiv.net/ajax/illust/$artworkId?lang=zh")
            .mapCatching { it.optJSONObject("body") ?: error("Pixiv 作品信息为空") }

    private fun fetchUgoiraMeta(context: Context, artworkId: String, knownRestrictedArtwork: Boolean = false): Result<UgoiraMeta> =
        requestJson(context, "https://www.pixiv.net/ajax/illust/$artworkId/ugoira_meta?lang=zh", knownRestrictedArtwork)
            .mapCatching { root ->
                val body = root.optJSONObject("body") ?: error("Ugoira 元数据为空")
                val zipUrl = body.optString("originalSrc").ifBlank { body.optString("src") }
                require(zipUrl.startsWith("http")) { "Ugoira 帧包地址无效" }
                val framesJson = body.optJSONArray("frames") ?: error("Ugoira 帧列表为空")
                val frames = buildList {
                    for (i in 0 until framesJson.length()) {
                        val item = framesJson.optJSONObject(i) ?: continue
                        val file = item.optString("file")
                        if (file.isBlank()) continue
                        add(
                            UgoiraProcessor.Frame(
                                file = file,
                                delayMs = item.optInt("delay", 0).also { require(it in 1..60_000) { "无效帧时长" } }
                            )
                        )
                    }
                }
                require(frames.isNotEmpty()) { "Ugoira 帧列表为空" }
                UgoiraMeta(artworkId, zipUrl, frames)
            }

    private fun requestJson(context: Context, url: String, knownRestrictedArtwork: Boolean = false): Result<JSONObject> =
        PixivJsonClient(httpClient) { requestUrl ->
            SessionRepository.getInstance(context).cookieHeader(SessionPlatforms.PIXIV, requestUrl)
        }.get(url, knownRestrictedArtwork)

    private fun baseHeaders(cookie: String?): Map<String, String> = buildMap {
        put("User-Agent", DESKTOP_UA)
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }
}
