package com.omni.downloader.engine

import android.net.Uri
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 轻量级网页标题检索工具类：
 * 使用已有的 OkHttp 与 Okio 流式读取前 64KB 数据，
 * 在探测到 </title> 或 </head> 后提前中断连接，实现毫秒级且超低流量消耗的网站标题抓取。
 */
object WebTitleFetcher {

    private const val MAX_HEAD_BYTES = 64 * 1024L // 最多只读前 64KB
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val TITLE_PATTERN = Pattern.compile(
        """<title[^>]*>(.*?)</title>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val CHARSET_META_PATTERN = Pattern.compile(
        """(?:charset=["']?([a-zA-Z0-9_\-]+)["']?|content=["'][^"']*charset=([a-zA-Z0-9_\-]+)["'])""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * 异步拉取目标网址对应的网页标题
     * @param url 用户输入的原始网址
     * @return 提取并解码后的标题；若请求失败或无法提取则返回基于 Host 的保底名称或 null
     */
    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        val cleanUrl = formatUrl(url) ?: return@withContext null
        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext fallbackHostName(cleanUrl)
                }
                val body = response.body ?: return@withContext fallbackHostName(cleanUrl)

                // 1. 流式部分读取前 64KB（探测到 </title> 或 </head> 提前熔断退出）
                val source = body.source()
                val buffer = Buffer()
                var totalBytesRead = 0L

                while (totalBytesRead < MAX_HEAD_BYTES) {
                    val toRead = minOf(4096L, MAX_HEAD_BYTES - totalBytesRead)
                    val read = source.read(buffer, toRead)
                    if (read == -1L) break
                    totalBytesRead += read

                    // 快速探测是否已读到闭合的 </title> 或 </head>
                    val peekUtf8 = buffer.clone().readUtf8()
                    if (peekUtf8.contains("</title>", ignoreCase = true) ||
                        peekUtf8.contains("</head>", ignoreCase = true)
                    ) {
                        break
                    }
                }

                val rawBytes = buffer.readByteArray()
                if (rawBytes.isEmpty()) {
                    return@withContext fallbackHostName(cleanUrl)
                }

                // 2. 编码嗅探 (Header -> Meta -> 默认 UTF-8)
                val charset = detectCharset(response.body?.contentType()?.charset(), rawBytes)
                val html = String(rawBytes, charset)

                // 3. 正则提取与 HTML Entity 转义解码
                val matcher = TITLE_PATTERN.matcher(html)
                if (matcher.find()) {
                    val rawTitle = matcher.group(1)?.trim() ?: ""
                    val decodedTitle = HtmlCompat.fromHtml(rawTitle, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        .toString()
                        .replace(Regex("""[\r\n\t]+"""), " ")
                        .trim()

                    return@withContext sanitizeTitle(decodedTitle, cleanUrl)
                }

                // 4. 未找到 title 标签时回退至域名
                return@withContext fallbackHostName(cleanUrl)
            }
        } catch (e: Exception) {
            return@withContext fallbackHostName(cleanUrl)
        }
    }

    private fun detectCharset(headerCharset: Charset?, rawBytes: ByteArray): Charset {
        if (headerCharset != null) return headerCharset
        val preview = String(rawBytes.take(4096).toByteArray(), Charsets.ISO_8859_1)
        val matcher = CHARSET_META_PATTERN.matcher(preview)
        if (matcher.find()) {
            val detected = matcher.group(1) ?: matcher.group(2)
            if (!detected.isNullOrBlank()) {
                try {
                    return Charset.forName(detected.trim())
                } catch (_: Exception) {}
            }
        }
        return Charsets.UTF_8
    }

    private fun sanitizeTitle(title: String, url: String): String {
        val lower = title.lowercase()
        val isInvalid = title.isBlank() ||
                lower.contains("404 not found") ||
                lower.contains("attention required") ||
                lower.contains("just a moment") ||
                lower.contains("security check") ||
                lower.contains("robot check")

        if (isInvalid) {
            return fallbackHostName(url)
        }
        return title.take(50)
    }

    private fun fallbackHostName(url: String): String {
        return try {
            val host = Uri.parse(url).host ?: ""
            val cleanHost = host.removePrefix("www.")
            cleanHost.split('.').firstOrNull()?.replaceFirstChar { it.uppercase() } ?: cleanHost
        } catch (_: Exception) {
            "备用解析网站"
        }
    }

    fun formatUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
