package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.engine.SafeLog as Log
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 国内短视频平台（抖音、快手、小红书）原生轻量直接解析器
 * 提供免 Python、毫秒级的无水印视频元数据嗅探与极速流式下载
 */
object DomesticDirectExtractor {

    private const val TAG = "DomesticExtractor"

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val platform = CookieRequestPolicy.platformFor(url, url)
            val repository = com.omni.downloader.data.repository.SessionRepository.currentOrNull()
            val next = request.newBuilder()
            if (repository != null && !repository.isCookieEnabled(platform)) next.removeHeader("Cookie")
            else repository?.cookieHeader(platform, url)?.let { saved ->
                // OkHttp's CookieJar may replace an explicit account header with visitor cookies.
                val savedNames = com.omni.downloader.data.repository.CookieHeaderParser.parse(saved).map { it.first }.toSet()
                val visitor = com.omni.downloader.data.repository.CookieHeaderParser.parse(request.header("Cookie").orEmpty())
                    .filter { it.first !in savedNames }.joinToString("; ") { "${it.first}=${it.second}" }
                next.header("Cookie", listOf(saved, visitor).filter { it.isNotBlank() }.joinToString("; "))
            }
            chain.proceed(next.build())
        }
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
    private data class MetadataEntry(val value: VideoMetadata, val time: Long, val scope: String)
    private val metadataCache = linkedMapOf<String, MetadataEntry>()
    internal fun metadataScope(site: String) = "${NetworkProxy.configuredUrl}|${com.omni.downloader.data.repository.SessionRepository.currentOrNull()?.requestGeneration(site)}"

    @Synchronized internal fun rememberMetadata(metadata: VideoMetadata, expectedScope: String? = null) {
        if (metadata.siteName !in DomesticSourceRecovery.platforms) return
        val scope = expectedScope ?: metadataScope(metadata.siteName)
        if (expectedScope != null && scope != metadataScope(metadata.siteName)) return
        if (metadataCache.size >= 64) metadataCache.remove(metadataCache.keys.first())
        metadataCache[metadata.url] = MetadataEntry(metadata, System.nanoTime(), scope)
    }

    @Synchronized internal fun cachedMetadata(url: String): VideoMetadata? = metadataCache[url]?.takeIf {
        System.nanoTime() - it.time < TimeUnit.MINUTES.toNanos(10) && it.scope == metadataScope(it.value.siteName)
    }?.value

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
    suspend fun extract(
        url: String,
        site: String,
        cookieHeader: String? = null,
        cacheResult: Boolean = true
    ): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            when (site) {
                "抖音" -> extractDouyin(url, cacheResult)
                "快手" -> extractKuaishou(url, cookieHeader)
                "小红书" -> extractXiaohongshu(url, cookieHeader)
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
    private fun extractDouyin(rawUrl: String, cacheResult: Boolean = true): Result<VideoMetadata> {
        // 合集/mix 链接必须交给能展开列表的引擎，不能静默只返回 URL 里的单个视频
        if (LinkNormalizer.isCollectionUrl(rawUrl, LinkNormalizer.PLATFORM_DOUYIN)) {
            return Result.failure(
                Exception(MediaErrors.describe(MediaErrorCode.UNKNOWN, "抖音合集链接交由通用引擎展开"))
            )
        }
        val requestedId = LinkNormalizer.contentId(rawUrl, LinkNormalizer.PLATFORM_DOUYIN)
            ?: return Result.failure(Exception("[MEDIA_ID_MISMATCH] 未能确认目标抖音作品 ID"))
        val kind = if (LinkNormalizer.normalize(rawUrl).contentUrl.contains("/note/")) "note" else "video"
        // Inspect every response, including the initial visit. An initial full
        // SSR must not be discarded while only its redirected URL is retained.
        val shareUrl = "https://www.iesdouyin.com/share/$kind/$requestedId/"
        val pageDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        repeat(2) { attempt ->
            val request = Request.Builder().url(shareUrl).header("User-Agent", if (attempt == 0) MOBILE_UA else
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://www.douyin.com/").build()
            val shareCall = httpClient.newCall(request)
            shareCall.timeout().deadlineNanoTime(pageDeadline)
            shareCall.execute().use { response ->
                if (!response.isSuccessful) {
                    val code = when (response.code) {
                        401 -> MediaErrorCode.AUTH_REQUIRED
                        403 -> MediaErrorCode.BROWSER_ASSIST_REQUIRED
                        429 -> MediaErrorCode.RATE_LIMITED
                        404, 410 -> MediaErrorCode.CONTENT_UNAVAILABLE
                        else -> MediaErrorCode.NETWORK_ERROR
                    }
                    return Result.failure(Exception(MediaErrors.describe(code, "抖音目标分享页 HTTP ${response.code}")))
                }
                val resolvedUrl = response.request.url.toString()
                if (LinkNormalizer.platformOf(resolvedUrl) != LinkNormalizer.PLATFORM_DOUYIN ||
                    LinkNormalizer.contentId(resolvedUrl, LinkNormalizer.PLATFORM_DOUYIN) != requestedId)
                    return Result.failure(Exception("[MEDIA_ID_MISMATCH] 抖音分享页未停留在目标作品"))
                val html = response.body?.string().orEmpty()
                if (html.contains("_$" + "jsvmprt") && html.contains("byted_acrawler"))
                    return Result.failure(Exception("[BROWSER_ASSIST_REQUIRED] 抖音页面需要执行网页脚本，尝试自动加载目标页面"))
                val parsed = DouyinVideoParser.parseHtml(html, resolvedUrl)
                if (parsed != null) {
                    // The mobile SSR may expose only a low rendition. Inspect the
                    // requested desktop detail too; never read the random feed.
                    val richer = runCatching {
                        val detailUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id=$requestedId"
                        val detailRequest = Request.Builder().url(detailUrl).header("User-Agent", DESKTOP_UA)
                            .header("Referer", "https://www.douyin.com/video/$requestedId").build()
                        val detailCall = httpClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(detailRequest)
                        detailCall.timeout().deadlineNanoTime(pageDeadline)
                        detailCall.execute().use { detail ->
                            if (!detail.isSuccessful) null else {
                                val body = detail.body?.source()?.let { source ->
                                    if (source.request(4_194_305)) null else source.readUtf8()
                                }.orEmpty()
                                DouyinVideoParser.parseHtml("<script>window._ROUTER_DATA=$body</script>", resolvedUrl)
                            }
                        }
                    }.getOrNull()
                    val merged = if (richer == null) parsed else DouyinQualityPolicy.merge(parsed, richer)
                    val confirmed = confirmDouyinMetadata(merged, budgetMillis = 6_000, remember = cacheResult)
                    return Result.success(confirmed)
                }
            }
        }

        return Result.failure(Exception("[BROWSER_ASSIST_REQUIRED] 抖音未返回目标作品详情，请在内置浏览器打开目标作品后提取"))
    }

    /** Worker-thread only. Confirm missing stream dimensions with bounded MP4
     * ranges; never promote an upload's dimensions to every playback variant. */
    internal fun confirmDouyinMetadata(metadata: VideoMetadata, verifyHighest: Boolean = false, budgetMillis: Long = 20_000,
        remember: Boolean = true): VideoMetadata {
        var probes = 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        val confirmedSources = mutableMapOf<String, HttpMp4Probe.Info>()
        val topKeys = if (verifyHighest) QualitySelector.sorted(metadata.availableVideoFormats)
            .map { DomesticSourceRecovery.resourceKey(it.sourceUrl) }.distinct().take(2).toSet() else emptySet()
        val formats = metadata.availableVideoFormats.map { format ->
            val playbackOutro = runCatching { java.net.URI(format.sourceUrl).path.contains("/playwm/") }.getOrDefault(false)
            val key = DomesticSourceRecovery.resourceKey(format.sourceUrl)
            if (format.dimensionsVerified || (format.width > 0 && format.height > 0 && !playbackOutro && key !in topKeys)) return@map format
            val info = confirmedSources[key] ?: run {
                if (probes >= (if (verifyHighest) 2 else 4) || System.nanoTime() >= deadline) return@map format
                probes++
                HttpMp4Probe.probe(format.sourceUrl, httpClient, DomesticMediaSource.headers("抖音"),
                    minOf(6_000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
                    ?.also { confirmedSources[key] = it }
            } ?: return@map format
            format.copy(width = info.width, height = info.height, resolutionLabel = QualitySelector.label(info.width, info.height),
                sourceDurationMs = info.durationMs ?: 0, dimensionsVerified = true)
        }
        return metadata.copy(availableVideoFormats = QualitySelector.sorted(formats)).also { if (remember) rememberMetadata(it) }
    }

    /**
     * 快手无水印直接解析
     */
    private fun extractKuaishou(rawUrl: String, cookieHeader: String?): Result<VideoMetadata> {
        if (LinkNormalizer.isCollectionUrl(rawUrl, LinkNormalizer.PLATFORM_KUAISHOU))
            return Result.failure(Exception("[MEDIA_UNCONFIRMED] 快手合集交由通用引擎展开"))
        var current = LinkNormalizer.normalize(rawUrl).contentUrl
        val requestedId = LinkNormalizer.contentId(current, LinkNormalizer.PLATFORM_KUAISHOU)
        val client = httpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
        repeat(6) {
            val uri = java.net.URI(current)
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            if (uri.scheme !in setOf("http", "https") || !(host == "kuaishou.com" || host.endsWith(".kuaishou.com")))
                return Result.failure(Exception("[MEDIA_ID_MISMATCH] 快手跳转到了非平台内容地址"))
            val path = uri.path.orEmpty().lowercase()
            if (path.contains("/login")) return Result.failure(Exception("[AUTH_REQUIRED] 请登录快手后重新解析原链接"))
            if (path.contains("/captcha") || path.contains("/verify")) return Result.failure(Exception("[CHALLENGE_REQUIRED] 请在快手浏览器完成验证"))
            val request = Request.Builder().url(current).header("User-Agent", DESKTOP_UA).header("Referer", "https://www.kuaishou.com/")
            if (uri.scheme == "https" && !cookieHeader.isNullOrBlank()) request.header("Cookie", cookieHeader)
            client.newCall(request.build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    current = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: return Result.failure(Exception("[NETWORK_ERROR] 快手跳转缺少合法地址"))
                    current = LinkNormalizer.normalize(current).contentUrl
                } else {
                    if (!response.isSuccessful) return Result.failure(Exception(MediaErrors.describe(MediaErrors.classify("HTTP ${response.code}", response.code))))
                    if (requestedId != null && LinkNormalizer.contentId(current, LinkNormalizer.PLATFORM_KUAISHOU) != requestedId)
                        return Result.failure(Exception("[MEDIA_ID_MISMATCH] 快手跳转后作品 ID 改变"))
                    val html = response.body?.string().orEmpty()
                    if (html.contains("验证码") && html.contains("captcha", true))
                        return Result.failure(Exception("[CHALLENGE_REQUIRED] 请在快手浏览器完成验证"))
                    val metadata = KuaishouNoteParser.parse(html, current)
                    val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.kuaishou.com/")
                    val stream = DirectStreamInfo(metadata.availableVideoFormats.first().formatId, headers)
                    directStreamCache[current] = stream; directStreamCache[rawUrl] = stream
                    return Result.success(metadata)
                }
            }
        }
        return Result.failure(Exception("[NETWORK_ERROR] 快手跳转次数过多"))
    }

    /**
     * 小红书无水印直接解析
     */
    private fun extractXiaohongshu(rawUrl: String, cookieHeader: String?): Result<VideoMetadata> {
        var current = LinkNormalizer.normalize(rawUrl).contentUrl
        val requestedId = LinkNormalizer.contentId(current, LinkNormalizer.PLATFORM_XHS)
        val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(6) {
            val uri = java.net.URI(current)
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            val firstParty = host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com")
            val shortLink = host in setOf("xhslink.cn", "xhslink.com") || host.endsWith(".xhslink.cn") || host.endsWith(".xhslink.com")
            if (uri.scheme !in setOf("http", "https") || (!firstParty && !shortLink)) {
                return Result.failure(Exception("[MEDIA_ID_MISMATCH] 小红书链接跳转到了非平台地址"))
            }
            val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
            if (path.contains("/login") || path.contains("/passport"))
                return Result.failure(Exception("[AUTH_REQUIRED] 请在小红书网页登录后重新解析原始分享链接"))
            if (path.contains("/captcha") || path.contains("/verify") || path.contains("/challenge"))
                return Result.failure(Exception("[CHALLENGE_REQUIRED] 请在小红书浏览器完成验证"))
            val request = Request.Builder().url(current).header("User-Agent", MOBILE_UA)
                .header("Referer", "https://www.xiaohongshu.com/")
            // A note-domain account header must never be sent to the short-link domain.
            if (firstParty && uri.scheme == "https" && !cookieHeader.isNullOrBlank()) request.header("Cookie", cookieHeader)
            client.newCall(request.build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val target = response.header("Location")
                        ?: return Result.failure(Exception("[NETWORK_ERROR] 小红书跳转缺少目标地址"))
                    current = response.request.url.resolve(target)?.toString()
                        ?: return Result.failure(Exception("[NETWORK_ERROR] 小红书跳转地址无效"))
                } else {
                    if (!response.isSuccessful) {
                        return Result.failure(Exception(MediaErrors.describe(MediaErrors.classify("HTTP ${response.code}", response.code))))
                    }
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) return Result.failure(Exception("[NETWORK_ERROR] 无法获取小红书页面内容"))
                    if (requestedId != null && LinkNormalizer.contentId(current, LinkNormalizer.PLATFORM_XHS) != requestedId)
                        return Result.failure(Exception("[MEDIA_ID_MISMATCH] 小红书跳转后的笔记与原链接不一致"))
                    var metadata = try {
                        XiaohongshuNoteParser.parseVideo(html, current)
                    } catch (error: Exception) {
                        val code = MediaErrors.classify(error.message.orEmpty())
                        if (code in setOf(MediaErrorCode.EXTRACTOR_CHANGED, MediaErrorCode.MEDIA_UNCONFIRMED)) {
                            classifyXhsAuthHtml(html)?.let { return Result.failure(Exception(MediaErrors.describe(it))) }
                        }
                        return Result.failure(error)
                    }
                    XiaohongshuNoteParser.originalVideoUrl(html, current)?.let { original ->
                        val info = HttpMp4Probe.probe(original, httpClient, mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.xiaohongshu.com/"))
                        if (info != null) {
                            val format = FormatOption(formatId = original, sourceUrl = original,
                                width = info.width, height = info.height, resolutionLabel = QualitySelector.label(info.width, info.height),
                                approximateSize = info.fileBytes?.let { bytes ->
                                    if (bytes >= 1073741824) String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / 1073741824.0)
                                    else String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
                                }.orEmpty(),
                                note = "平台原始视频源 · 已探测尺寸")
                            metadata = metadata.copy(availableVideoFormats = QualitySelector.sorted(metadata.availableVideoFormats + format).distinctBy { it.formatId })
                        } else {
                            metadata = metadata.copy(qualityWarning = "原始视频源暂未确认尺寸，当前画质列表不代表该作品的最高画质。可重试解析，或选择尺寸待确认的原始源。",
                                availableVideoFormats = metadata.availableVideoFormats + FormatOption(formatId = original, sourceUrl = original,
                                    resolutionLabel = "原始源（尺寸待确认）", note = "平台返回的原始视频地址，尺寸探测暂未成功"))
                        }
                    }
                    val headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.xiaohongshu.com/")
                    rememberMetadata(metadata)
                    val stream = DirectStreamInfo(metadata.availableVideoFormats.first().formatId, headers)
                    directStreamCache[current] = stream
                    directStreamCache[rawUrl] = stream
                    return Result.success(metadata)
                }
            }
        }
        return Result.failure(Exception("[NETWORK_ERROR] 小红书跳转次数过多"))
    }

    private fun classifyXhsAuthHtml(html: String): MediaErrorCode? {
        val lower = html.lowercase(java.util.Locale.ROOT)
        return when {
            lower.contains("验证码") && (lower.contains("滑块") || lower.contains("captcha")) -> MediaErrorCode.CHALLENGE_REQUIRED
            lower.contains("登录后查看") || lower.contains("请先登录") || lower.contains("扫码登录") ||
                lower.contains("/login?redirectpath") -> MediaErrorCode.AUTH_REQUIRED
            else -> null
        }
    }

    private fun isHttpMediaUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        // 防止把 HTML/JSON 地址误当媒体
        val lower = url.lowercase(java.util.Locale.ROOT)
        return !lower.endsWith(".html") && !lower.contains("/api/") && !lower.contains("javascript:")
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
        val sessionCookie = com.omni.downloader.data.repository.SessionRepository
            .getInstance(context).cookieHeader(site, mediaUrl)
        val sessionHeaders = if (!sessionCookie.isNullOrBlank()) mapOf("Cookie" to sessionCookie) else emptyMap()
        val headers = DomesticMediaSource.headers(site) + cached?.headers.orEmpty() +
            task.directMediaHeaders + sessionHeaders
        // 共用已有的取消、重试、媒体校验和后处理，不能将视频简单改名为音频。
        DownloadEngine.downloadDomesticMediaStream(
            context, task, mediaUrl, proxyUrl, stagingDir, publicDir, onProgressUpdate, headers
        )
    }

    private fun followRedirects(
        startUrl: String,
        userAgent: String,
        cookieHeader: String? = null
    ): String {
        return try {
            val builder = Request.Builder()
                .url(startUrl)
                .header("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
            val req = builder.build()
            httpClient.newCall(req).execute().use { it.request.url.toString() }
        } catch (e: Exception) {
            startUrl
        }
    }

    private fun fetchHtml(url: String, userAgent: String, cookieHeader: String? = null): String {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
            val req = builder.build()
            httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        } catch (e: Exception) {
            ""
        }
    }

}
