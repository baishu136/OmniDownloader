package com.omni.downloader.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object UrlSniffer {
    private val contentRedirects = ContentRedirectCache()

    private val URL_REGEX = Pattern.compile(
        """https?://[a-zA-Z0-9_\-.]+(?::[0-9]+)?(?:/[^\s]*)?""",
        Pattern.CASE_INSENSITIVE
    )

    private val httpClient = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 跳转由 LinkNormalizer 逐跳处理，才能识别登录/验证/404 终点
    private val noRedirectClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 从杂乱的文本（例如系统剪贴板、第三方 App 分享文本）中提取出有效的首个 URL
     */
    fun extractUrl(rawText: String): String? {
        val matcher = URL_REGEX.matcher(rawText)
        if (matcher.find()) {
            var url = matcher.group(0)?.trim() ?: return null
            // 清理末尾常见的标点或中文符号
            while (url.isNotEmpty() && (url.endsWith(")") || url.endsWith("]") || url.endsWith("}") ||
                    url.endsWith("。") || url.endsWith("，") || url.endsWith("！") || url.endsWith("；") ||
                    url.endsWith(",") || url.endsWith("?") || url.endsWith(">") || url.endsWith("\"") || url.endsWith("'"))) {
                url = url.substring(0, url.length - 1)
            }
            return url
        }
        return null
    }

    /**
     * 判断并标识网站平台。
     * 平台判断统一交给 LinkNormalizer：只匹配解析后的 host，
     * 避免 contains("x.com") 误命中 netflix.com 一类域名。
     */
    fun identifySite(url: String): String = LinkNormalizer.platformOf(url)

    /**
     * 规范化与清洗 URL（针对多平台短链跟随重定向、去除追踪参数等）
     */
    suspend fun sanitizeAndResolveUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = LinkNormalizer.normalize(rawUrl)
        if (!LinkNormalizer.isShortLink(normalized.contentUrl)) {
            return@withContext normalized.contentUrl
        }

        val result = resolveContent(rawUrl)
        if (normalized.platform == LinkNormalizer.PLATFORM_DOUYIN)
            return@withContext DouyinResolution.redirectTarget(result)
        when (result) {
            is LinkNormalizer.RedirectResult.Content -> LinkNormalizer.normalize(result.url).contentUrl
            is LinkNormalizer.RedirectResult.LoginRequired -> result.contentUrl ?: normalized.contentUrl
            is LinkNormalizer.RedirectResult.ChallengeRequired -> result.contentUrl ?: normalized.contentUrl
            is LinkNormalizer.RedirectResult.Unavailable -> normalized.contentUrl
        }
    }

    /**
     * 带状态的链接恢复：调用方据此区分“可解析”“需要登录”“需要验证”“不可用”，
     * 而不是把所有失败都当成 Unsupported URL。
     */
    suspend fun resolveContent(rawUrl: String): LinkNormalizer.RedirectResult = withContext(Dispatchers.IO) {
        val normalized = LinkNormalizer.normalize(rawUrl)
        if (!LinkNormalizer.isShortLink(normalized.contentUrl)) {
            return@withContext LinkNormalizer.RedirectResult.Content(normalized.contentUrl)
        }
        val proxy = NetworkProxy.configuredUrl
        contentRedirects.get(normalized.contentUrl, proxy)?.let {
            return@withContext LinkNormalizer.RedirectResult.Content(it)
        }
        val userAgent = if (normalized.platform == LinkNormalizer.PLATFORM_XHS ||
            normalized.platform == LinkNormalizer.PLATFORM_BILIBILI ||
            normalized.platform == LinkNormalizer.PLATFORM_PINTEREST
        ) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15"
        }
        val result = if (normalized.platform == LinkNormalizer.PLATFORM_DOUYIN) {
            kotlinx.coroutines.withTimeoutOrNull(4_000) {
                LinkNormalizer.resolveRedirects(normalized.contentUrl, noRedirectClient, userAgent)
            } ?: LinkNormalizer.RedirectResult.Unavailable(normalized.contentUrl, null)
        } else LinkNormalizer.resolveRedirects(normalized.contentUrl, noRedirectClient, userAgent)
        result.also { resolved ->
            if (resolved is LinkNormalizer.RedirectResult.Content && !isDirectMediaUrl(resolved.url) &&
                !LinkNormalizer.isShortLink(resolved.url) && NetworkProxy.configuredUrl == proxy)
                contentRedirects.put(normalized.contentUrl, proxy, resolved.url)
        }
    }

    /**
     * 解析中转站提取的直链或携带 JWT Payload (如 SnapCDN/X2Twitter/TwitterSaver) 的长链接
     * 解码出真实的视频直链与规范的文件名，若无法解码则原样返回
     */
    fun unpackDirectMediaUrl(rawUrl: String, defaultTitle: String = "中转下载视频"): Pair<String, String> {
        val trimmed = rawUrl.trim()
        try {
            // 匹配 URL 中包含 token=eyJ...
            val tokenIndex = trimmed.indexOf("token=")
            if (tokenIndex != -1) {
                var tokenVal = trimmed.substring(tokenIndex + 6)
                val ampersandIndex = tokenVal.indexOf('&')
                if (ampersandIndex != -1) {
                    tokenVal = tokenVal.substring(0, ampersandIndex)
                }
                if (tokenVal.startsWith("eyJ") && tokenVal.contains(".")) {
                    val parts = tokenVal.split(".")
                    if (parts.size >= 2) {
                        val payload = parts[1]
                        val decodedBytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
                        val jsonStr = String(decodedBytes, Charsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        val realUrl = json.optString("url")
                        val filename = json.optString("filename")
                        val title = if (filename.isNotBlank()) {
                            filename.substringBeforeLast(".")
                        } else defaultTitle

                        // 关键处理：如果 token 内的 realUrl 是 m3u8 切片列表，而原始链接是 dl.snapcdn.app 等中转转码地址，
                        // 则必须优先请求 snapcdn 的转码地址（其服务端会合成完整 MP4），绝不能直接抓取未合并的 m3u8 纯文本列表！
                        if (realUrl.isNotBlank() && realUrl.startsWith("http")) {
                            if (isM3u8Url(realUrl) && (trimmed.contains("snapcdn") || trimmed.contains("get?token"))) {
                                return Pair(trimmed, title)
                            }
                            return Pair(realUrl, title)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return Pair(trimmed, defaultTitle)
    }

    /**
     * 判断是否属于 M3U8 (HLS 分片索引流)
     */
    fun isM3u8Url(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
                lower.contains("/hls/") || lower.contains("format=m3u8") ||
                lower.contains(".m3u8/")
    }

    /**
     * 判断是否属于网络媒体直链
     */
    fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()

        // 核心防御：严禁将网站内部的 API 接口调用误判为媒体直链（防止向仅支持 POST 的接口发 GET 导致 405 报错）
        val isApiRoute = lower.contains("/api/") || lower.contains("/ajax/") ||
                lower.contains("/extract/") || lower.contains("/video-tool") ||
                lower.contains("cnsimpleextract") || lower.contains("dodownload") ||
                lower.contains("getdownloadinfo")
        val isExplicitMediaExt = lower.endsWith(".mp4") || lower.contains(".mp4?") ||
                lower.endsWith(".m4a") || lower.contains(".m4a?") ||
                lower.endsWith(".mp3") || lower.contains(".mp3?") ||
                lower.endsWith(".webm") || lower.contains(".webm?") ||
                lower.endsWith(".flv") || lower.contains(".flv?") ||
                lower.endsWith(".m3u8") || lower.contains(".m3u8?")

        if (isApiRoute && !isExplicitMediaExt) {
            return false
        }

        return isExplicitMediaExt ||
                lower.contains("dl.snapcdn.app") ||
                lower.contains("video.twimg.com") ||
                lower.contains("snapany.com/api/download") ||
                lower.contains("googlevideo.com/videoplayback") ||
                lower.contains("byteoversea.com") ||
                lower.contains("ibytedtos.com") ||
                lower.contains("tiktokcdn.com") ||
                lower.contains("douyinvod.com") ||
                lower.contains("snssdk.com") ||
                lower.contains("yximgs.com") ||
                lower.contains("xhscdn.com") ||
                lower.contains("fbcdn.net") ||
                lower.contains("twcdn.net")
    }
}
