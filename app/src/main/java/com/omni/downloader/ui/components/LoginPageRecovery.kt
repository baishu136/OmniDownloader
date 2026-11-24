package com.omni.downloader.ui.components

import java.net.URI

/**
 * 登录页 HTTP 错误与裸 JSON 响应自动恢复策略（纯函数，便于 JVM 单元测试）。
 * 当平台登录页或风控重定向返回 4xx / {"result":2,...} 时，平滑切换至备选登录入口。
 */
internal object LoginPageRecovery {
    const val KUAISHOU_PASSPORT_URL =
        "https://passport.kuaishou.com/pc/account/login?sid=kuaishou.web.api&followUrl=https%3A%2F%2Fwww.kuaishou.com%2Fnew-reco"

    fun httpErrorFallbackUrl(platform: String, failedUrl: String, statusCode: Int, attempts: Int): String? {
        if (attempts > 0 || statusCode < 400) return null
        val path = runCatching { URI(failedUrl).path.orEmpty() }.getOrDefault("")
        return when (platform) {
            "Instagram" -> if (path != "/") "https://www.instagram.com/" else null
            "Facebook" -> if (path != "/") "https://www.facebook.com/" else null
            "快手" -> KUAISHOU_PASSPORT_URL
            "TikTok" -> if (path != "/") "https://www.tiktok.com/" else null
            "X (Twitter)" -> if (path != "/login") "https://x.com/login" else null
            "小红书" -> if (path != "/") "https://www.xiaohongshu.com/" else null
            else -> null
        }
    }

    fun looksLikeRawErrorJsonPayload(bodyText: String): Boolean {
        val trimmed = bodyText.trim()
        if (trimmed.isEmpty() || trimmed.length > 1024) return false
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return false
        if (trimmed.contains("<html", ignoreCase = true)) return false
        return (trimmed.contains("\"result\"") &&
            (trimmed.contains("\"request_id\"") || trimmed.contains("\"error_msg\""))) ||
            (trimmed.contains("\"error\"") && trimmed.contains("\"message\""))
    }

    fun jsonPayloadFallbackUrl(platform: String, currentUrl: String, bodyText: String, attempts: Int): String? {
        if (attempts > 0 || !looksLikeRawErrorJsonPayload(bodyText)) return null
        val fallback = when (platform) {
            "快手" -> KUAISHOU_PASSPORT_URL
            else -> loginConfigFor(platform).cookieUrl
        }
        return if (fallback.equals(currentUrl, ignoreCase = true)) null else fallback
    }
}
