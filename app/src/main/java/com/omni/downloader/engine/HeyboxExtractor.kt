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
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 小黑盒分享页解析（受限支持）。
 *
 * 规划要求先验证分享页是否公开提供视频源。当前分享页是 SPA，只有在静态正文
 * 或页面内嵌数据中出现 MP4/HLS 时才可解析；否则必须明确返回受限状态，
 * 不能把图文帖或空页面当成视频。
 */
object HeyboxExtractor {

    private const val TAG = "HeyboxExtractor"
    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private val httpClient = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaUrlRegex = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val mediaCache = ConcurrentHashMap<String, List<String>>()

    suspend fun extract(context: Context, rawUrl: String): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            try {
                val pageUrl = LinkNormalizer.normalize(rawUrl).contentUrl
                val contentId = LinkNormalizer.contentId(pageUrl, LinkNormalizer.PLATFORM_HEYBOX)
                    ?: return@withContext Result.failure(
                        Exception(MediaErrors.describe(MediaErrorCode.NO_MEDIA, "未能识别小黑盒内容 ID"))
                    )
                val cookie = SessionRepository.getInstance(context).cookieHeader(SessionPlatforms.HEYBOX)
                val html = fetchPage(pageUrl, cookie)

                authError(pageUrl, html)?.let { code ->
                    return@withContext Result.failure(Exception(MediaErrors.describe(code)))
                }

                val mediaUrls = extractMediaUrls(html)
                if (mediaUrls.isEmpty()) {
                    return@withContext Result.failure(
                        Exception(
                            MediaErrors.describe(
                                MediaErrorCode.BROWSER_ASSIST_REQUIRED,
                                "该分享页未返回公开视频源，请在内置浏览器打开目标帖子并播放以捕获媒体地址"
                            )
                        )
                    )
                }
                mediaCache[pageUrl] = mediaUrls

                val formats = mediaUrls.mapIndexed { index, url ->
                    val isHls = url.lowercase(Locale.ROOT).contains(".m3u8")
                    FormatOption(
                        formatId = url,
                        resolutionLabel = if (isHls) "HLS 视频 ${index + 1}" else "MP4 视频 ${index + 1}",
                        ext = if (isHls) "m3u8" else "mp4",
                        note = if (isHls) "HLS 切片流" else "直接视频文件"
                    )
                }

                val title = extractTitle(html).ifBlank { "小黑盒视频 $contentId" }
                val cover = extractMetaContent(html, "og:image")

                Result.success(
                    VideoMetadata(
                        url = pageUrl,
                        title = title,
                        author = "",
                        thumbnailUrl = cover,
                        siteName = SessionPlatforms.HEYBOX,
                        availableVideoFormats = formats,
                        availableAudioFormats = emptyList()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "小黑盒解析失败", e)
                Result.failure(
                    Exception(
                        MediaErrors.describe(MediaErrors.classify(e.message.orEmpty()), e.message),
                        e
                    )
                )
            }
        }

    suspend fun download(
        context: Context,
        task: DownloadTask,
        proxyUrl: String,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val pageUrl = LinkNormalizer.normalize(task.url).contentUrl
        val cookie = SessionRepository.getInstance(context).cookieHeader(SessionPlatforms.HEYBOX)

        val mediaUrl = task.directMediaUrl.ifBlank {
            val cached = mediaCache[pageUrl]?.firstOrNull()
            cached ?: runCatching { extract(context, pageUrl).getOrThrow() }
                .getOrNull()
                ?.availableVideoFormats
                ?.firstOrNull()
                ?.formatId
                .orEmpty()
        }

        if (mediaUrl.isBlank()) {
            return@withContext Result.failure(
                Exception(MediaErrors.describe(MediaErrorCode.NO_MEDIA, "未能取得小黑盒视频地址"))
            )
        }

        if (task.downloadType == com.omni.downloader.data.model.DownloadType.AUDIO_ONLY ||
            task.downloadType == com.omni.downloader.data.model.DownloadType.COVER
        ) {
            return@withContext Result.failure(
                Exception(
                    MediaErrors.describe(
                        MediaErrorCode.NO_MEDIA,
                        "小黑盒受限支持：暂不支持音频提取或仅封面导出"
                    )
                )
            )
        }

        val headers = buildMap {
            put("User-Agent", MOBILE_UA)
            put("Referer", "https://www.xiaoheihe.cn/")
            // Platform credentials are never sent to a media CDN.
        }
        DownloadEngine.downloadDirectMediaStream(
            context = context,
            task = task,
            targetUrl = mediaUrl,
            proxyUrl = proxyUrl,
            stagingDir = stagingDir,
            publicDir = publicDir,
            onProgressUpdate = onProgressUpdate,
            requestHeaders = headers
        )
    }

    /** 抽取页面内可能公开的 MP4/HLS 地址；纯函数，便于测试。 */
    fun extractMediaUrls(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
        return mediaUrlRegex.findAll(unescaped)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    private fun authError(url: String, html: String): MediaErrorCode? {
        val lowerUrl = url.lowercase(Locale.ROOT)
        if (lowerUrl.contains("/login")) return MediaErrorCode.AUTH_REQUIRED
        val lowerHtml = html.lowercase(Locale.ROOT)
        return when {
            lowerHtml.contains("验证码") && lowerHtml.contains("滑块") -> MediaErrorCode.CHALLENGE_REQUIRED
            lowerHtml.contains("登录后查看") || lowerHtml.contains("请先登录") -> MediaErrorCode.AUTH_REQUIRED
            else -> null
        }
    }

    private fun fetchPage(url: String, cookie: String?): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", "https://www.xiaoheihe.cn/")
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val code = when (response.code) {
                    401, 403 -> MediaErrorCode.AUTH_REQUIRED
                    404, 410 -> MediaErrorCode.CONTENT_UNAVAILABLE
                    429 -> MediaErrorCode.RATE_LIMITED
                    else -> MediaErrorCode.NETWORK_ERROR
                }
                error(MediaErrors.describe(code, "HTTP ${response.code}"))
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun extractTitle(html: String): String {
        val ogTitle = extractMetaContent(html, "og:title")
        if (ogTitle.isNotBlank()) return cleanTitle(ogTitle)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
            .orEmpty()
        return cleanTitle(title)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace(Regex("\\s*[-_|]\\s*小黑盒\\s*$"), "")
        .trim()

    private fun extractMetaContent(html: String, property: String): String {
        val pattern = Regex(
            "<meta[^>]+(?:property|name)=[\"']${java.util.regex.Pattern.quote(property)}[\"'][^>]*content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            .orEmpty()
    }
}
