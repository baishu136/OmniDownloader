package com.omni.downloader.engine

/**
 * 统一脱敏：所有日志、错误详情和诊断导出在写出前都必须经过这里。
 *
 * 处理三类最常见泄漏：
 * 1. Cookie / Authorization 请求头；
 * 2. 带签名或会话语义的查询参数；
 * 3. URL 中的 user:password。
 */
object LogSanitizer {

    private val HEADER_SECRET = Regex(
        """(?i)\b(cookie|set-cookie|authorization|x-api-key)\s*:\s*[^\r\n]+"""
    )

    private val SENSITIVE_QUERY_KEY = Regex(
        """(?i)([?&](?:token|xsec_token|xsec_source|sign|signature|auth_key|expires|wssecret|wsse|key|sessdata|bili_jct|dedeuserid|buvid3|buvid4|shareToken|appkey)=)([^&\s#]+)"""
    )

    private val SENSITIVE_COOKIE_KEY = Regex(
        """(?i)\b(sessdata|bili_jct|dedeuserid|buvid3|buvid4|sid|token|xsec_token|Authorization)=([^;\s&]+)"""
    )

    private val URL_USERINFO = Regex("""(?i)(https?://)[^/\s@]+@""")

    // CookieJar includes the complete offending Netscape row in LoadError.
    // Tracebacks can contain actual tabs or Python repr's literal \\t escapes.
    private val NETSCAPE_COOKIE_ROW = Regex(
        """(?im)((?:#HttpOnly_)?\.?[a-z0-9_-]+(?:\.[a-z0-9_-]+)*(?:\t|\\t)(?:TRUE|FALSE)(?:\t|\\t)[^\r\n]*?(?:\t|\\t)(?:TRUE|FALSE)(?:\t|\\t)[0-9]*(?:\t|\\t)[^\r\n]*?(?:\t|\\t))[^\r\n]*"""
    )

    /** 通用文本脱敏，用于日志与异常详情。 */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace(NETSCAPE_COOKIE_ROW) { it.groupValues[1] + "***" }
            .replace(HEADER_SECRET, "$1: ***")
            .replace(URL_USERINFO, "$1***:***@")
            .replace(SENSITIVE_QUERY_KEY, "$1***")
            .replace(SENSITIVE_COOKIE_KEY, "$1=***")
    }

    /** 单个 URL 的脱敏版本，用于“正在请求：...”一类日志。 */
    fun sanitizeUrl(url: String): String = sanitize(url)

    /** 把一组请求头转换成可安全记录的摘要。 */
    fun summarizeHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return "{}"
        return headers.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val safeValue = if (key.equals("cookie", ignoreCase = true) ||
                key.equals("authorization", ignoreCase = true)
            ) {
                "***"
            } else {
                sanitize(value)
            }
            "$key=$safeValue"
        }
    }
}
