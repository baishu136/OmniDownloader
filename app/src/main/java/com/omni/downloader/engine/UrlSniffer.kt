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

    private val URL_REGEX = Pattern.compile(
        """https?://[a-zA-Z0-9_\-.]+(?::[0-9]+)?(?:/[^\s]*)?""",
        Pattern.CASE_INSENSITIVE
    )

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
     * 判断并标识网站平台
     */
    fun identifySite(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("bilibili.com") || lower.contains("b23.tv") || lower.startsWith("bv") || lower.startsWith("av") || BilibiliDirectExtractor.extractBvid(url) != null -> "哔哩哔哩"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("twitter.com") || lower.contains("x.com") -> "X (Twitter)"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("douyin.com") || lower.contains("iesdouyin.com") -> "抖音"
            lower.contains("kuaishou.com") || lower.contains("kwai.com") || lower.contains("gifshow.com") -> "快手"
            lower.contains("xiaohongshu.com") || lower.contains("xhslink.com") || lower.contains("rednote.com") -> "小红书"
            lower.contains("instagram.com") || lower.contains("instagr.am") -> "Instagram"
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.me") -> "Facebook"
            lower.contains("pinterest.com") || lower.contains("pin.it") -> "Pinterest"
            else -> "网络视频"
        }
    }

    /**
     * 规范化与清洗 URL（针对多平台短链跟随重定向、去除追踪参数等）
     */
    suspend fun sanitizeAndResolveUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        var cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty()) return@withContext ""

        // 若用户直接输入或粘贴了纯 BV 号或 av 号
        val directBvid = BilibiliDirectExtractor.extractBvid(cleanUrl)
        if (directBvid != null && !cleanUrl.contains("bilibili.com") && !cleanUrl.contains("b23.tv")) {
            cleanUrl = "https://www.bilibili.com/video/$directBvid"
        }

        // 短链探测列表（需要进行网络 301/302 重定向解析）
        val isShortLink = cleanUrl.contains("b23.tv") ||
                cleanUrl.contains("v.douyin.com") ||
                cleanUrl.contains("v.kuaishou.com") ||
                cleanUrl.contains("xhslink.com") ||
                cleanUrl.contains("vm.tiktok.com") ||
                cleanUrl.contains("vt.tiktok.com") ||
                cleanUrl.contains("pin.it") ||
                cleanUrl.contains("fb.watch") ||
                cleanUrl.contains("fb.me")

        if (isShortLink || cleanUrl.contains("url_shortener")) {
            try {
                val ua = if (cleanUrl.contains("xhslink.com") || cleanUrl.contains("b23.tv") || cleanUrl.contains("pin.it") || cleanUrl.contains("pinterest.com")) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
                }

                val nonRedirectClient = httpClient.newBuilder().followRedirects(false).build()
                var current = cleanUrl
                var hops = 0
                while (hops < 10) {
                    hops++
                    val request = Request.Builder()
                        .url(current)
                        .header("User-Agent", ua)
                        .build()
                    val response = nonRedirectClient.newCall(request).execute()
                    val code = response.code
                    val loc = response.header("Location")
                    response.close()

                    if (code in 300..399 && !loc.isNullOrBlank()) {
                        val nextUrl = response.request.url.resolve(loc)?.toString() ?: loc
                        current = nextUrl
                    } else {
                        break
                    }
                }
                cleanUrl = current
            } catch (ignored: Exception) {
            }
        }

        // 专门处理 Pinterest：提取标准 pin ID，防止短链跳转接口或尾部路径导致误匹配为 Board 报错 404
        if (cleanUrl.contains("pinterest.") || cleanUrl.contains("pin.it")) {
            val pinPattern = Pattern.compile("""(?:pinterest\.[a-z.]+|pin\.it)/pin/(?:[\w-]+--)?(\d+)""", Pattern.CASE_INSENSITIVE)
            val matcher = pinPattern.matcher(cleanUrl)
            if (matcher.find()) {
                val pinId = matcher.group(1)
                if (!pinId.isNullOrEmpty()) {
                    cleanUrl = "https://www.pinterest.com/pin/$pinId/"
                }
            }
        }

        // 专门处理抖音：规整为标准视频 URL，杜绝 iesdouyin.com/share/video/ 被误判或导致解析报错
        if (cleanUrl.contains("douyin.com") || cleanUrl.contains("iesdouyin.com")) {
            val douyinPattern = Pattern.compile("""(?:video|note)/(\d+)""")
            val matcher = douyinPattern.matcher(cleanUrl)
            if (matcher.find()) {
                val videoId = matcher.group(1)
                if (!videoId.isNullOrEmpty()) {
                    cleanUrl = "https://www.douyin.com/video/$videoId"
                }
            }
        }

        // 专门处理 TikTok：短链跳转后规整为标准视频 URL
        if (cleanUrl.contains("tiktok.com")) {
            val tiktokPattern = Pattern.compile("""/video/(\d+)""")
            val matcher = tiktokPattern.matcher(cleanUrl)
            if (matcher.find()) {
                val videoId = matcher.group(1)
                if (!videoId.isNullOrEmpty()) {
                    cleanUrl = "https://www.tiktok.com/@user/video/$videoId"
                }
            }
        }

        // 规范化 B 站 URL（处理手机端 m.bilibili.com -> www.bilibili.com 并清理追踪参数，保留 p=）
        if (cleanUrl.contains("bilibili.com")) {
            cleanUrl = cleanUrl.replace("m.bilibili.com/video/", "www.bilibili.com/video/")
            val qIndex = cleanUrl.indexOf('?')
            if (qIndex != -1) {
                val base = cleanUrl.substring(0, qIndex)
                val query = cleanUrl.substring(qIndex + 1)
                val params = query.split("&").filter { it.startsWith("p=") }
                cleanUrl = if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
            }
        }

        // 清洗其它平台的冗余跟踪参数（? 之后的内容）
        val shouldStripQueryParams = cleanUrl.contains("x.com") ||
                cleanUrl.contains("twitter.com") ||
                cleanUrl.contains("douyin.com") ||
                cleanUrl.contains("iesdouyin.com") ||
                cleanUrl.contains("kuaishou.com") ||
                cleanUrl.contains("xiaohongshu.com") ||
                cleanUrl.contains("rednote.com") ||
                cleanUrl.contains("tiktok.com") ||
                cleanUrl.contains("instagram.com") ||
                cleanUrl.contains("facebook.com") ||
                cleanUrl.contains("pinterest.com")

        if (shouldStripQueryParams) {
            val qIndex = cleanUrl.indexOf('?')
            if (qIndex != -1) {
                cleanUrl = cleanUrl.substring(0, qIndex)
            }
        }

        cleanUrl
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
        return lower.endsWith(".mp4") || lower.contains(".mp4?") ||
                lower.endsWith(".m4a") || lower.contains(".m4a?") ||
                lower.endsWith(".mp3") || lower.contains(".mp3?") ||
                lower.endsWith(".webm") || lower.contains(".webm?") ||
                lower.endsWith(".flv") || lower.contains(".flv?") ||
                lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
                lower.contains("dl.snapcdn.app") ||
                lower.contains("video.twimg.com") ||
                lower.contains("snapany.com/api/download") ||
                lower.contains("greenvideo.cc/api/video/download") ||
                lower.contains("googlevideo.com/videoplayback") ||
                lower.contains("byteoversea.com") ||
                lower.contains("ibytedtos.com") ||
                lower.contains("tiktokcdn.com") ||
                lower.contains("twcdn.net")
    }
}
