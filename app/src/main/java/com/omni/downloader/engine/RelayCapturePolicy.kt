package com.omni.downloader.engine

import java.net.URI

/** URL evidence shared by the automatic resolver and its optional browser. */
internal object RelayCapturePolicy {
    private val mediaPath = Regex("\\.(mp4|m4v|m3u8|webm|flv|m4a|mp3|aac|ogg|opus|wav|flac)$", RegexOption.IGNORE_CASE)
    private val blockedPath = Regex("\\.(exe|apk|dmg|pkg|msi|zip|rar|html?|js|css|png|jpe?g|gif|svg|mpd)$", RegexOption.IGNORE_CASE)
    private val cdns = setOf("dl.snapcdn.app", "video.twimg.com", "googlevideo.com", "byteoversea.com", "ibytedtos.com", "tiktokcdn.com", "douyinvod.com", "snssdk.com", "yximgs.com", "xhscdn.com", "fbcdn.net", "twcdn.net")

    fun accepts(url: String, mimeType: String = ""): Boolean = runCatching {
        if (url.length !in 1..65536) return false
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        if (blockedPath.containsMatchIn(path)) return false
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (mime == "application/dash+xml") return false // The direct-download pipeline currently handles HLS, not DASH manifests.
        val mediaMime = mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")
        if (mediaPath.containsMatchIn(path) || mediaMime) return true
        if (Regex("/(api|ajax|extract)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
        cdns.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
}
