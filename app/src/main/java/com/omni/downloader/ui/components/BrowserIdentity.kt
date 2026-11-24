package com.omni.downloader.ui.components

import java.net.URI

/** Desktop identity policy, independent of WebView and Android lifecycle. */
internal object BrowserIdentity {
    const val DESKTOP_VIEWPORT = 1280
    private val desktopHosts = setOf("xiaohongshu.com", "douyin.com", "iesdouyin.com", "kuaishou.com",
        "gifshow.com", "kwai.com", "ksapisrv.com",
        "bilibili.com", "x.com", "twitter.com", "pixiv.net", "xiaoheihe.cn", "heybox.cn", "pinterest.com",
        "instagram.com", "facebook.com", "tiktok.com", "xhslink.cn", "xhslink.com", "b23.tv",
        "t.co", "pin.it", "fb.watch", "fb.me", "rednote.com", "chenzhongtech.com")

    fun prefersDesktop(platform: String) = platform != "YouTube" && platform != "Instagram"
    fun prefersDesktopUrl(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase() ?: return false
        desktopHosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun chromeVersion(original: String): String? = Regex("Chrome/([0-9]+(?:\\.[0-9]+){0,3})").find(original)?.groupValues?.get(1)
    fun desktopUserAgent(original: String): String {
        val chrome = chromeVersion(original)?.let { " Chrome/$it" }.orEmpty()
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)$chrome Safari/537.36"
    }

    /** 清理 Android WebView 的 "; wv" 与 "Version/x.y" 标记，还原标准移动版 Chrome UA。 */
    fun mobileUserAgent(original: String): String {
        val cleaned = original
            .replace(Regex(";\\s*wv\\b"), "")
            .replace(Regex("\\bwv;\\s*"), "")
            .replace(Regex("\\s+Version/[0-9]+(?:\\.[0-9]+)*"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return cleaned.ifEmpty { original }
    }

    fun permitsNavigation(url: String) = runCatching {
        URI(url).scheme?.lowercase() in setOf("https", "http", "about")
    }.getOrDefault(false)
}
