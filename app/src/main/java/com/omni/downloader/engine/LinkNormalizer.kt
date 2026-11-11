package com.omni.downloader.engine

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * 平台感知的链接规范化。
 *
 * 目标：
 * - 平台识别只基于解析后的 host，不再对完整字符串做 contains("x.com") 这类判断；
 * - 按平台保留会影响内容访问的参数（小红书 xsec_token、抖音 modal_id/mix_id、B站 sid/p 等）；
 * - 小红书登录包装地址只解一层 redirectPath，不循环 URL-decode；
 * - 短链跳转有总预算与最大跳数，并区分登录/验证/不可用。
 *
 * 本对象中的 normalize / platformOf / contentId 为纯函数，便于单元测试；
 * 只有 resolveRedirects 会发起网络请求。
 */
object LinkNormalizer {

    const val PLATFORM_BILIBILI = "哔哩哔哩"
    const val PLATFORM_YOUTUBE = "YouTube"
    const val PLATFORM_X = "X (Twitter)"
    const val PLATFORM_TIKTOK = "TikTok"
    const val PLATFORM_DOUYIN = "抖音"
    const val PLATFORM_KUAISHOU = "快手"
    const val PLATFORM_XHS = "小红书"
    const val PLATFORM_INSTAGRAM = "Instagram"
    const val PLATFORM_FACEBOOK = "Facebook"
    const val PLATFORM_PINTEREST = "Pinterest"
    const val PLATFORM_PIXIV = "Pixiv"
    const val PLATFORM_HEYBOX = "小黑盒"
    const val PLATFORM_GENERIC = "网络视频"

    private const val MAX_REDIRECT_HOPS = 5

    private val URL_REGEX = Regex("""https?://[a-zA-Z0-9_\-.]+(?::[0-9]+)?(?:/[^\s]*)?""")
    private val BV_REGEX = Regex("""[Bb][Vv][0-9A-Za-z]{8,}""")
    private val AV_REGEX = Regex("""(?i)(?<![A-Za-z0-9])av(\d+)""")

    data class NormalizedLink(
        val originalInput: String,
        val platform: String,
        val contentUrl: String,
        val contentId: String?,
        val isShortLink: Boolean
    )

    sealed interface RedirectResult {
        data class Content(val url: String) : RedirectResult
        data class LoginRequired(val url: String, val contentUrl: String?) : RedirectResult
        data class ChallengeRequired(val url: String, val contentUrl: String?) : RedirectResult
        data class Unavailable(val url: String, val statusCode: Int?) : RedirectResult
    }

    /** 从分享文本中提取第一个 URL；纯 BV/av 输入按 B 站处理。 */
    fun normalize(input: String): NormalizedLink {
        val original = input.trim()
        val rawUrl = URL_REGEX.find(original)?.value ?: original.substringBefore(' ')

        val bv = BV_REGEX.find(rawUrl)?.value
        if (bv != null && hostOf(rawUrl) == null) {
            val url = "https://www.bilibili.com/video/$bv"
            return NormalizedLink(original, PLATFORM_BILIBILI, url, contentId(url, PLATFORM_BILIBILI), false)
        }
        val av = AV_REGEX.find(rawUrl)?.value
        if (av != null && hostOf(rawUrl) == null) {
            val url = "https://www.bilibili.com/video/$av"
            return NormalizedLink(original, PLATFORM_BILIBILI, url, contentId(url, PLATFORM_BILIBILI), false)
        }

        val platform = platformOf(rawUrl)
        var current = rewriteHost(rawUrl, platform)

        // 小红书登录包装：/login?redirectPath=<内容页>，只解一层
        if (platform == PLATFORM_XHS) {
            current = unwrapXhsLogin(current) ?: current
        }

        if (platform == PLATFORM_KUAISHOU && hostOf(current)?.let { hostMatches(it, "chenzhongtech.com") } == true) {
            contentId(current, platform)?.let { current = "https://www.kuaishou.com/short-video/$it" }
        }

        val normalizedUrl = applyQueryPolicy(current, platform)
        return NormalizedLink(
            originalInput = original,
            platform = platform,
            contentUrl = normalizedUrl,
            contentId = contentId(normalizedUrl, platform),
            isShortLink = isShortLink(normalizedUrl)
        )
    }

    fun platformOf(input: String): String {
        val raw = input.trim()
        val host = hostOf(raw)
            ?: return if (BV_REGEX.containsMatchIn(raw) || AV_REGEX.containsMatchIn(raw)) {
                PLATFORM_BILIBILI
            } else {
                PLATFORM_GENERIC
            }
        return when {
            hostMatches(host, "bilibili.com") || hostMatches(host, "b23.tv") -> PLATFORM_BILIBILI
            hostMatches(host, "youtube.com") || hostMatches(host, "youtu.be") ||
                hostMatches(host, "youtube-nocookie.com") -> PLATFORM_YOUTUBE
            hostMatches(host, "x.com") || hostMatches(host, "twitter.com") || host == "t.co" -> PLATFORM_X
            hostMatches(host, "tiktok.com") -> PLATFORM_TIKTOK
            hostMatches(host, "douyin.com") || hostMatches(host, "iesdouyin.com") -> PLATFORM_DOUYIN
            hostMatches(host, "kuaishou.com") || hostMatches(host, "kwai.com") ||
                hostMatches(host, "gifshow.com") || hostMatches(host, "chenzhongtech.com") -> PLATFORM_KUAISHOU
            hostMatches(host, "xiaohongshu.com") || hostMatches(host, "xhslink.com") ||
                hostMatches(host, "xhslink.cn") || hostMatches(host, "rednote.com") -> PLATFORM_XHS
            hostMatches(host, "instagram.com") || hostMatches(host, "instagr.am") -> PLATFORM_INSTAGRAM
            hostMatches(host, "facebook.com") || host == "fb.watch" || host == "fb.me" -> PLATFORM_FACEBOOK
            hostMatches(host, "pinterest.com") || host == "pin.it" -> PLATFORM_PINTEREST
            hostMatches(host, "pixiv.net") -> PLATFORM_PIXIV
            hostMatches(host, "xiaoheihe.cn") || hostMatches(host, "heybox.cn") -> PLATFORM_HEYBOX
            else -> PLATFORM_GENERIC
        }
    }

    fun isShortLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return when {
            hostMatches(host, "b23.tv") -> true
            hostMatches(host, "v.douyin.com") -> true
            hostMatches(host, "v.kuaishou.com") -> true
            hostMatches(host, "xhslink.com") || hostMatches(host, "xhslink.cn") -> true
            hostMatches(host, "vm.tiktok.com") || hostMatches(host, "vt.tiktok.com") -> true
            host == "pin.it" || host == "fb.watch" || host == "fb.me" || host == "t.co" -> true
            else -> false
        }
    }

    /** 合集/播放列表判断：决定是否允许 yt-dlp 展开 entries。 */
    fun isCollectionUrl(url: String, platform: String = platformOf(url)): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val query = parseQuery(uri.rawQuery)
        return when (platform) {
            PLATFORM_YOUTUBE -> query.containsKey("list")
            PLATFORM_BILIBILI -> path.contains("/list/") ||
                query.containsKey("sid") || query.containsKey("season_id")
            PLATFORM_DOUYIN -> query.containsKey("modal_id") || query.containsKey("mix_id") ||
                path.contains("/collection") || path.contains("/mix")
            PLATFORM_XHS -> path.contains("/collection")
            PLATFORM_KUAISHOU -> path.contains("/collection")
            else -> path.contains("/playlist") || path.contains("/collection") || path.contains("/mix")
        }
    }

    fun contentId(url: String, platform: String = platformOf(url)): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty()
        val query = parseQuery(uri.rawQuery)
        return when (platform) {
            PLATFORM_BILIBILI -> {
                val id = Regex("/video/([^/]+)").find(path)?.groupValues?.get(1)
                    ?: BV_REGEX.find(url)?.value
                    ?: AV_REGEX.find(url)?.value
                id?.let {
                    val p = query["p"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "$it:p$p"
                }
            }
            PLATFORM_YOUTUBE -> {
                val id = query["v"]
                    ?: if (uri.host?.lowercase()?.removePrefix("www.") == "youtu.be") {
                        path.trimStart('/').substringBefore('/')
                    } else {
                        Regex("/(?:shorts|embed|live)/([^/]+)").find(path)?.groupValues?.get(1)
                    }
                id?.takeIf { it.isNotBlank() }
            }
            PLATFORM_X -> Regex("/status/(\\d+)(?:/video/(\\d+))?").find(path)?.let { m ->
                val status = m.groupValues[1]
                val media = m.groupValues.getOrNull(2).orEmpty()
                if (media.isBlank()) status else "$status:video$media"
            }
            PLATFORM_DOUYIN -> Regex("/(?:video|note|collection|mix)/(\\d+)").find(path)?.groupValues?.get(1)
                ?: query["modal_id"] ?: query["mix_id"]
            PLATFORM_TIKTOK -> Regex("/video/(\\d+)").find(path)?.groupValues?.get(1)
            PLATFORM_KUAISHOU -> Regex("/(?:short-video|photo)/([^/?]+)").find(path)?.groupValues?.get(1)
            PLATFORM_XHS -> Regex("/(?:explore|discovery/item|item)/([0-9a-zA-Z]+)").find(path)?.groupValues?.get(1)
            PLATFORM_INSTAGRAM -> Regex("/(?:p|reel|tv)/([^/]+)").find(path)?.groupValues?.get(1)
            PLATFORM_PINTEREST -> Regex("/pin/(?:[^/]+--)?(\\d+)").find(path)?.groupValues?.get(1)
            PLATFORM_PIXIV -> Regex("/artworks/(\\d+)").find(path)?.groupValues?.get(1)
            PLATFORM_HEYBOX -> Regex("/(?:a|app/bbs/link)/([0-9a-zA-Z]+)").find(path)?.groupValues?.get(1)
                ?: query["link_id"]
            else -> null
        }
    }

    /**
     * 有界跳转解析。最多 5 跳，最终地址若为登录/验证页则显式返回对应状态，
     * 由调用方决定进入 WAITING_FOR_AUTH，而不是继续把它当视频页解析。
     */
    suspend fun resolveRedirects(
        startUrl: String,
        client: OkHttpClient,
        userAgent: String,
        cookieHeader: String? = null,
        maxHops: Int = MAX_REDIRECT_HOPS
    ): RedirectResult = withContext(Dispatchers.IO) {
        var current = startUrl
        var hops = 0

        classifyPage(current)?.let { return@withContext it }

        while (hops < maxHops) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank() && platformOf(current) == platformOf(startUrl)) {
                builder.header("Cookie", cookieHeader)
            }
            val result = try {
                awaitResponse(client.newCall(builder.build())).use { response ->
                    val code = response.code
                    val location = response.header("Location")
                    if (code in 300..399 && !location.isNullOrBlank()) {
                        val next = response.request.url.resolve(location)?.toString() ?: location
                        HopResult.Redirect(next)
                    } else if (code == 401 || code == 407) {
                        HopResult.Login(current)
                    } else if (code == 403) {
                        HopResult.Challenge(current)
                    } else if (code == 404 || code == 410) {
                        HopResult.Unavailable(current, code)
                    } else {
                        HopResult.Content(current)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                return@withContext RedirectResult.Unavailable(current, null)
            }

            when (result) {
                is HopResult.Redirect -> {
                    current = result.url
                    hops++
                    classifyPage(current)?.let { return@withContext it }
                }
                is HopResult.Content -> return@withContext RedirectResult.Content(current)
                is HopResult.Login -> return@withContext RedirectResult.LoginRequired(
                    current,
                    contentUrlFromLogin(current)
                )
                is HopResult.Challenge -> return@withContext RedirectResult.ChallengeRequired(
                    current,
                    contentUrlFromLogin(current)
                )
                is HopResult.Unavailable -> return@withContext RedirectResult.Unavailable(
                    current,
                    result.code
                )
            }
        }
        // 超过预算时返回最终地址，由上层决定是否继续；不再无限跟随。
        RedirectResult.Content(current)
    }

    private suspend fun awaitResponse(call: okhttp3.Call): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val received = java.util.concurrent.atomic.AtomicReference<okhttp3.Response?>()
        continuation.invokeOnCancellation { call.cancel(); received.getAndSet(null)?.close() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                received.set(response)
                if (continuation.isActive) continuation.resumeWith(Result.success(response))
                else received.getAndSet(null)?.close()
            }
        })
    }

    private sealed interface HopResult {
        data class Redirect(val url: String) : HopResult
        data class Content(val url: String) : HopResult
        data class Login(val url: String) : HopResult
        data class Challenge(val url: String) : HopResult
        data class Unavailable(val url: String, val code: Int?) : HopResult
    }

    private fun classifyPage(url: String): RedirectResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val isLogin = path.contains("/login") || path.contains("/signin") ||
            host.startsWith("passport.")
        val isChallenge = path.contains("captcha") || path.contains("challenge") ||
            path.contains("/verify") || host.contains("captcha")
        return when {
            isChallenge -> RedirectResult.ChallengeRequired(url, contentUrlFromLogin(url))
            isLogin -> RedirectResult.LoginRequired(url, contentUrlFromLogin(url))
            else -> null
        }
    }

    /** 从登录包装地址中取回内容地址；只解一层，避免破坏 token 中的 %3D / +。 */
    private fun contentUrlFromLogin(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val raw = parseQuery(uri.rawQuery)
        val redirect = raw["redirectPath"] ?: raw["redirect_path"] ?: raw["redirect_uri"] ?: return null
        val decoded = runCatching { URLDecoder.decode(redirect, "UTF-8") }.getOrNull() ?: return null
        if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) return null
        return applyQueryPolicy(decoded, platformOf(decoded))
    }

    private fun unwrapXhsLogin(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (!path.contains("/login") && !path.contains("/signin")) return null
        val redirect = contentUrlFromLogin(url) ?: return null
        return rewriteHost(redirect, PLATFORM_XHS)
    }

    private fun rewriteHost(url: String, platform: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery

        val rewritten = when {
            platform == PLATFORM_BILIBILI && host == "m.bilibili.com" ->
                rebuild(uri, "www.bilibili.com", path, query)

            platform == PLATFORM_DOUYIN && (host == "iesdouyin.com" || host == "www.iesdouyin.com") -> {
                val id = Regex("/(?:share/)?(?:video|note)/(\\d+)").find(path)?.groupValues?.get(1)
                if (id != null) "https://www.douyin.com/video/$id" else url
            }

            platform == PLATFORM_X && (host == "mobile.twitter.com" || host == "twitter.com") ->
                rebuild(uri, "x.com", path, query)

            platform == PLATFORM_TIKTOK && host != "www.tiktok.com" && host.endsWith(".tiktok.com") ->
                rebuild(uri, "www.tiktok.com", path, query)

            // 小黑盒 App 分享 API 转成可访问的网页分享页
            platform == PLATFORM_HEYBOX && host == "api.xiaoheihe.cn" &&
                path.contains("/web/share") -> {
                val linkId = parseQuery(query)["link_id"]
                if (!linkId.isNullOrBlank()) "https://www.xiaoheihe.cn/app/bbs/link/$linkId" else url
            }

            else -> url
        }
        return rewritten
    }

    /** 按平台保留会影响内容访问的参数，其余追踪参数清除。 */
    private fun applyQueryPolicy(url: String, platform: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val rawQuery = uri.rawQuery ?: return url
        if (rawQuery.isBlank()) return url

        val keepKeys: Set<String>? = when (platform) {
            // 小红书必须完整保留，xsec_token / xsec_source 失效会导致内容页直接跳登录
            PLATFORM_XHS -> null
            PLATFORM_BILIBILI -> setOf("p", "page", "sid", "season_id", "bvid", "aid")
            PLATFORM_YOUTUBE -> setOf("v", "list", "index", "t", "start")
            PLATFORM_DOUYIN -> setOf("modal_id", "mix_id", "item_ids", "aweme_id", "video_id", "collection_id", "region")
            PLATFORM_KUAISHOU -> setOf("photoid", "fid", "sharetoken")
            PLATFORM_X, PLATFORM_TIKTOK, PLATFORM_INSTAGRAM,
            PLATFORM_FACEBOOK, PLATFORM_PINTEREST, PLATFORM_PIXIV -> emptySet()
            else -> null
        }

        if (keepKeys == null) return url

        val kept = rawQuery.split('&').filter { pair ->
            val key = pair.substringBefore('=').substringBefore(';').lowercase(Locale.ROOT)
            key in keepKeys
        }
        val newQuery = kept.joinToString("&").takeIf { it.isNotBlank() }
        return rebuild(uri, uri.host, uri.rawPath.orEmpty(), newQuery)
    }

    private fun rebuild(uri: URI, host: String?, path: String, query: String?): String {
        val scheme = uri.scheme ?: "https"
        val authority = buildString {
            if (!uri.userInfo.isNullOrBlank()) {
                append(uri.userInfo).append('@')
            }
            append(host ?: return@buildString)
            if (uri.port >= 0) append(':').append(uri.port)
        }
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (!query.isNullOrBlank()) append('?').append(query)
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) {
                val key = pair.substring(0, index)
                val value = pair.substring(index + 1)
                map.putIfAbsent(key, value)
            }
        }
        return map
    }

    private fun hostOf(value: String): String? {
        return runCatching {
            val uri = URI(value.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                null
            } else {
                uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            }
        }.getOrNull()
    }

    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
