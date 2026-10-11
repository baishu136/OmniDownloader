package com.omni.downloader.engine

import android.content.Context
import android.util.Log
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 国内短视频平台（抖音、快手、小红书）原生轻量直接解析器
 * 提供免 Python、毫秒级的无水印视频元数据嗅探与极速流式下载
 */
object DomesticDirectExtractor {

    private const val TAG = "DomesticExtractor"

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            private val sessionCookies = mutableListOf<Cookie>()
            @Synchronized
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                sessionCookies.removeAll { old -> old.expiresAt <= System.currentTimeMillis() || cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                sessionCookies.addAll(cookies)
            }
            @Synchronized
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                sessionCookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        })
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // 缓存已解析出的直链以供下载时直接复用
    private val directStreamCache = ConcurrentHashMap<String, DirectStreamInfo>()

    data class DirectStreamInfo(
        val videoUrl: String,
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * 判断是否属于本解析器支持的国内平台
     */
    fun isSupported(site: String): Boolean {
        return site == "抖音" || site == "快手" || site == "小红书"
    }

    /**
     * 解析视频元数据
     */
    suspend fun extract(url: String, site: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            when (site) {
                "抖音" -> extractDouyin(url)
                "快手" -> extractKuaishou(url)
                "小红书" -> extractXiaohongshu(url)
                else -> Result.failure(IllegalArgumentException("不支持的国内平台: $site"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 $site 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 抖音无水印直接解析
     */
    private fun extractDouyin(rawUrl: String): Result<VideoMetadata> {
        val resolvedUrl = followRedirects(rawUrl, MOBILE_UA)
        Log.d(TAG, "抖音重定向目标: $resolvedUrl")

        // 提取视频 ID：兼容 video/xxx, note/xxx, item_ids=xxx, 以及纯数字
        val idMatcher = Pattern.compile("""(?:video|note|item_ids=)/?(\d+)""").matcher(resolvedUrl)
        val videoId = if (idMatcher.find()) {
            idMatcher.group(1) ?: ""
        } else {
            val fallbackMatcher = Pattern.compile("""(\d{18,20})""").matcher(resolvedUrl)
            if (fallbackMatcher.find()) fallbackMatcher.group(1) ?: "" else ""
        }

        if (videoId.isNotEmpty()) {
            // 1. 优先调用字节原生极速 Feed API (免登录、免 cookie、毫秒级下发无水印原画直链)
            val apiResult = requestDouyinFeedApi(videoId, resolvedUrl)
            if (apiResult != null) {
                return Result.success(apiResult)
            }
        }

        // 2. 备用：尝试抓取页面内嵌 SSR 数据
        val html = fetchHtml(resolvedUrl, MOBILE_UA)
        if (html.isNotEmpty()) {
            val parseResult = parseDouyinHtml(html, resolvedUrl, videoId)
            if (parseResult != null) {
                directStreamCache[parseResult.url] = DirectStreamInfo(
                    videoUrl = parseResult.availableVideoFormats.firstOrNull()?.formatId ?: "",
                    headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")
                )
                return Result.success(parseResult)
            }
        }

        return Result.failure(Exception("未能从抖音提取到无水印视频流，将尝试通用引擎"))
    }

    private fun requestDouyinFeedApi(videoId: String, pageUrl: String): VideoMetadata? {
        val apiUrl = "https://api.amemv.com/aweme/v1/feed/?aweme_id=$videoId"
        try {
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", MOBILE_UA)
                .build()
            val body = httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            } ?: return null
            val json = JSONObject(body)
            val awemeList = json.optJSONArray("aweme_list") ?: return null

            // 查找与 videoId 匹配的 item，若未找到则取首个
            var matchedItem: JSONObject? = null
            for (i in 0 until awemeList.length()) {
                val item = awemeList.optJSONObject(i) ?: continue
                if (item.optString("aweme_id") == videoId) {
                    matchedItem = item
                    break
                }
            }
            if (matchedItem == null && awemeList.length() > 0) {
                matchedItem = awemeList.optJSONObject(0)
            }
            if (matchedItem == null) return null

            val title = matchedItem.optString("desc", "抖音视频_$videoId").trim()
            val author = matchedItem.optJSONObject("author")?.optString("nickname", "抖音创作者") ?: "抖音创作者"
            val duration = matchedItem.optInt("duration", 0) / 1000

            val videoObj = matchedItem.optJSONObject("video")
            val cover = videoObj?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0, "") ?: ""

            // 提取播放直链 (play_addr -> url_list)
            val playObj = videoObj?.optJSONObject("play_addr")
            val urlList = playObj?.optJSONArray("url_list")
            var videoDirectUrl = ""
            if (urlList != null && urlList.length() > 0) {
                for (i in 0 until urlList.length()) {
                    val candidate = urlList.optString(i, "")
                    if (candidate.isNotEmpty()) {
                        videoDirectUrl = candidate
                        break
                    }
                }
            }

            // 若在 play_addr 中未找到，遍历 bit_rate
            if (videoDirectUrl.isEmpty()) {
                val bitRates = videoObj?.optJSONArray("bit_rate")
                if (bitRates != null && bitRates.length() > 0) {
                    for (i in 0 until bitRates.length()) {
                        val br = bitRates.optJSONObject(i) ?: continue
                        val brUrls = br.optJSONObject("play_addr")?.optJSONArray("url_list")
                        if (brUrls != null && brUrls.length() > 0) {
                            videoDirectUrl = brUrls.optString(0, "")
                            if (videoDirectUrl.isNotEmpty()) break
                        }
                    }
                }
            }

            // 无水印替换 (playwm -> play)
            val cleanVideoUrl = videoDirectUrl.replace("playwm", "play")

            // 提取独立原声音频 (music -> play_url)
            val musicObj = matchedItem.optJSONObject("music")
            val musicUrl = musicObj?.optJSONObject("play_url")?.optJSONArray("url_list")?.optString(0, "") ?: ""

            val audioOptions = mutableListOf<FormatOption>()
            if (musicUrl.isNotEmpty()) {
                audioOptions.add(
                    FormatOption(
                        formatId = musicUrl,
                        resolutionLabel = "独立原声音频 (MP3)",
                        ext = "mp3",
                        isAudioOnly = true,
                        note = "高品质原声"
                    )
                )
            }

            if (cleanVideoUrl.isNotEmpty()) {
                val metadata = VideoMetadata(
                    url = pageUrl,
                    title = if (title.isNotBlank()) title else "抖音视频_$videoId",
                    author = author,
                    durationText = if (duration > 0) "${duration}秒" else "",
                    thumbnailUrl = cover,
                    siteName = "抖音",
                    availableVideoFormats = listOf(
                        FormatOption(
                            formatId = cleanVideoUrl,
                            resolutionLabel = "原画 (无水印)",
                            ext = "mp4",
                            note = "极速直连原画"
                        )
                    ),
                    availableAudioFormats = audioOptions
                )
                directStreamCache[pageUrl] = DirectStreamInfo(
                    videoUrl = cleanVideoUrl,
                    headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")
                )
                directStreamCache["https://www.douyin.com/video/$videoId"] = DirectStreamInfo(
                    videoUrl = cleanVideoUrl,
                    headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")
                )
                return metadata
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestDouyinFeedApi 异常: ${e.message}", e)
        }
        return null
    }

    private fun parseDouyinHtml(html: String, pageUrl: String, videoId: String): VideoMetadata? {
        var videoUrl = ""
        var title = "抖音短视频"
        var author = "抖音用户"
        var coverUrl = ""

        val routerMatcher = Pattern.compile("""window\._ROUTER_DATA\s*=\s*(\{.*?\});\s*</script>""").matcher(html)
        if (routerMatcher.find()) {
            val jsonStr = routerMatcher.group(1).orEmpty()
            try {
                val playMatcher = Pattern.compile(""""play_addr"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)"""").matcher(jsonStr)
                if (playMatcher.find()) {
                    videoUrl = playMatcher.group(1)?.replace("""\/""", "/") ?: ""
                }
                val titleMatcher = Pattern.compile(""""desc"\s*:\s*"([^"]*)"""").matcher(jsonStr)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)?.trim() ?: title
                }
                val authorMatcher = Pattern.compile(""""nickname"\s*:\s*"([^"]*)"""").matcher(jsonStr)
                if (authorMatcher.find()) {
                    author = authorMatcher.group(1)?.trim() ?: author
                }
                val coverMatcher = Pattern.compile(""""cover"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)"""").matcher(jsonStr)
                if (coverMatcher.find()) {
                    coverUrl = coverMatcher.group(1)?.replace("""\/""", "/") ?: ""
                }
            } catch (ignored: Exception) {}
        }

        if (videoUrl.isEmpty()) {
            val directMatcher = Pattern.compile("""https?://[a-zA-Z0-9_\-.]+(?:douyinvod|snssdk)\.com/[^\s"']+""").matcher(html)
            if (directMatcher.find()) {
                videoUrl = directMatcher.group(0) ?: ""
            }
        }

        if (videoUrl.isNotEmpty()) {
            val cleanVideoUrl = videoUrl.replace("playwm", "play")
            return VideoMetadata(
                url = pageUrl,
                title = if (title.isNotBlank()) title else "抖音视频_$videoId",
                author = author,
                thumbnailUrl = coverUrl,
                siteName = "抖音",
                availableVideoFormats = listOf(
                    FormatOption(
                        formatId = cleanVideoUrl,
                        resolutionLabel = "原画 (无水印)",
                        ext = "mp4",
                        note = "极速直连原画"
                    )
                )
            )
        }
        return null
    }

    /**
     * 快手无水印直接解析
     */
    private fun extractKuaishou(rawUrl: String): Result<VideoMetadata> {
        val resolvedUrl = followRedirects(rawUrl, MOBILE_UA)
        Log.d(TAG, "快手重定向目标: $resolvedUrl")

        val html = fetchHtml(resolvedUrl, MOBILE_UA)
        if (html.isEmpty()) {
            return Result.failure(Exception("无法获取快手分享页面内容"))
        }

        var videoUrl = ""
        var title = "快手短视频"
        var author = "快手创作者"
        var coverUrl = ""

        // 1. 匹配无水印视频直链
        val patterns = listOf(
            Pattern.compile(""""srcNoMark"\s*:\s*"([^"]+)""""),
            Pattern.compile(""""playUrl"\s*:\s*"([^"]+)""""),
            Pattern.compile(""""photoUrl"\s*:\s*"([^"]+)""""),
            Pattern.compile("""<video[^>]+src="([^"]+)""""),
            Pattern.compile("""https?://[a-zA-Z0-9_\-.]+(?:kwaicdn|yximgs)\.com/[^\s"']+\.mp4""")
        )

        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = (if (m.groupCount() >= 1) m.group(1) else m.group(0)) ?: ""
                if (candidate.isNotEmpty()) {
                    videoUrl = candidate.replace("""\/""", "/")
                    break
                }
            }
        }

        // 标题与创作者
        val titleMatcher = Pattern.compile(""""caption"\s*:\s*"([^"]*)"""").matcher(html)
        if (titleMatcher.find()) {
            title = titleMatcher.group(1)?.trim() ?: title
        } else {
            val docTitle = Pattern.compile("""<title>(.*?)</title>""").matcher(html)
            if (docTitle.find()) {
                title = docTitle.group(1)?.replace(" - 快手", "")?.trim() ?: title
            }
        }

        val authorMatcher = Pattern.compile(""""userName"\s*:\s*"([^"]*)"""").matcher(html)
        if (authorMatcher.find()) {
            author = authorMatcher.group(1)?.trim() ?: author
        }

        val coverMatcher = Pattern.compile(""""poster"\s*:\s*"([^"]+)"""").matcher(html)
        if (coverMatcher.find()) {
            coverUrl = coverMatcher.group(1)?.replace("""\/""", "/") ?: ""
        }

        if (videoUrl.isNotEmpty()) {
            directStreamCache[resolvedUrl] = DirectStreamInfo(
                videoUrl = videoUrl,
                headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.kuaishou.com/")
            )
            return Result.success(
                VideoMetadata(
                    url = resolvedUrl,
                    title = title,
                    author = author,
                    thumbnailUrl = coverUrl,
                    siteName = "快手",
                    availableVideoFormats = listOf(
                        FormatOption(
                            formatId = videoUrl,
                            resolutionLabel = "原画 (无水印)",
                            ext = "mp4",
                            note = "极速直连原画"
                        )
                    )
                )
            )
        }

        return Result.failure(Exception("未能从快手页面提取到有效视频直链，将尝试通用引擎"))
    }

    /**
     * 小红书无水印直接解析
     */
    private fun extractXiaohongshu(rawUrl: String): Result<VideoMetadata> {
        val resolvedUrl = followRedirects(rawUrl, DESKTOP_UA)
        Log.d(TAG, "小红书重定向目标: $resolvedUrl")

        val html = fetchHtml(resolvedUrl, DESKTOP_UA)
        if (html.isEmpty()) {
            return Result.failure(Exception("无法获取小红书页面内容"))
        }

        var videoUrl = ""
        var title = "小红书视频"
        var author = "小红书作者"
        var coverUrl = ""

        // 1. 尝试解析 window.__INITIAL_STATE__
        val stateMatcher = Pattern.compile("""window\.__INITIAL_STATE__\s*=\s*(.*?)</script>""").matcher(html)
        if (stateMatcher.find()) {
            val stateStr = stateMatcher.group(1)?.trim() ?: ""
            // 规范化 JSON（小红书 SSR 可能包含 undefined）
            val validJsonStr = stateStr.replace("undefined", "null")
            try {
                // 查找 masterUrl
                val masterMatcher = Pattern.compile(""""masterUrl"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                if (masterMatcher.find()) {
                    videoUrl = masterMatcher.group(1)?.replace("""\/""", "/") ?: ""
                }

                // 查找 originVideoKey
                if (videoUrl.isEmpty()) {
                    val keyMatcher = Pattern.compile(""""originVideoKey"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                    if (keyMatcher.find()) {
                        val key = keyMatcher.group(1)
                        if (!key.isNullOrEmpty()) {
                            videoUrl = "https://sns-video-bd.xhscdn.com/$key"
                        }
                    }
                }

                val titleMatcher = Pattern.compile(""""title"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                if (titleMatcher.find() && !titleMatcher.group(1).isNullOrBlank()) {
                    title = titleMatcher.group(1)!!
                } else {
                    val descMatcher = Pattern.compile(""""desc"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                    if (descMatcher.find() && !descMatcher.group(1).isNullOrBlank()) {
                        title = descMatcher.group(1)!!
                    }
                }

                val userMatcher = Pattern.compile(""""nickname"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                if (userMatcher.find()) {
                    author = userMatcher.group(1) ?: author
                }

                val coverMatcher = Pattern.compile(""""firstFrameFileid"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                if (coverMatcher.find()) {
                    coverUrl = "https://sns-webpic-qc.xhscdn.com/${coverMatcher.group(1)}"
                }
            } catch (ignored: Exception) {
            }
        }

        // 备选正则探测
        if (videoUrl.isEmpty()) {
            val cdnMatcher = Pattern.compile("""https?://[a-zA-Z0-9_\-.]*xhscdn\.com/[^\s"']+\.mp4""").matcher(html)
            if (cdnMatcher.find()) {
                videoUrl = cdnMatcher.group(0) ?: ""
            }
        }

        if (videoUrl.isNotEmpty()) {
            directStreamCache[resolvedUrl] = DirectStreamInfo(
                videoUrl = videoUrl,
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.xiaohongshu.com/")
            )
            return Result.success(
                VideoMetadata(
                    url = resolvedUrl,
                    title = title,
                    author = author,
                    thumbnailUrl = coverUrl,
                    siteName = "小红书",
                    availableVideoFormats = listOf(
                        FormatOption(
                            formatId = videoUrl,
                            resolutionLabel = "原画 (无水印)",
                            ext = "mp4",
                            note = "极速直连原画"
                        )
                    )
                )
            )
        }

        return Result.failure(Exception("未能从小红书页面提取到视频直链，将尝试通用引擎"))
    }

    /**
     * 执行国内平台原生直连极速流式下载
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit,
        proxyUrl: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val site = UrlSniffer.identifySite(cleanUrl)
        val cached = directStreamCache[cleanUrl] ?: directStreamCache[task.url]
        val mediaUrl = task.directMediaUrl.ifBlank {
            task.selectedResolution.takeIf { it.startsWith("http") }
                ?: cached?.videoUrl?.takeIf { it.isNotBlank() }
                ?: extract(cleanUrl, site).getOrElse { return@withContext Result.failure(it) }
                    .availableVideoFormats.firstOrNull()?.formatId.orEmpty()
        }
        if (!mediaUrl.startsWith("http")) {
            return@withContext Result.failure(Exception("未找到有效的国内媒体直链，请重新解析链接"))
        }
        val headers = DomesticMediaSource.headers(site) + cached?.headers.orEmpty() + task.directMediaHeaders
        // 共用已有的取消、重试、媒体校验和后处理，不能将视频简单改名为音频。
        DownloadEngine.downloadDirectMediaStream(
            context, task, mediaUrl, proxyUrl, stagingDir, publicDir, onProgressUpdate, headers
        )
    }

    private fun followRedirects(startUrl: String, userAgent: String): String {
        return try {
            val req = Request.Builder()
                .url(startUrl)
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { it.request.url.toString() }
        } catch (e: Exception) {
            startUrl
        }
    }

    private fun fetchHtml(url: String, userAgent: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        } catch (e: Exception) {
            ""
        }
    }

}
