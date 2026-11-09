package com.omni.downloader.engine

/** Resolve a captured CDN request back to its platform before considering browser cookies. */
object CookieRequestPolicy {
    fun platformFor(originUrl: String, targetUrl: String): String {
        val original = LinkNormalizer.platformOf(originUrl)
        if (original != LinkNormalizer.PLATFORM_GENERIC) return original
        val target = LinkNormalizer.platformOf(targetUrl)
        if (target != LinkNormalizer.PLATFORM_GENERIC) return target
        val host = runCatching { java.net.URI(targetUrl).host.orEmpty().lowercase() }.getOrDefault("")
        fun matches(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            matches("twimg.com") -> LinkNormalizer.PLATFORM_X
            matches("xhscdn.com") -> LinkNormalizer.PLATFORM_XHS
            matches("bilivideo.com") || matches("hdslb.com") -> LinkNormalizer.PLATFORM_BILIBILI
            matches("kwaicdn.com") || matches("yximgs.com") -> LinkNormalizer.PLATFORM_KUAISHOU
            matches("pximg.net") -> LinkNormalizer.PLATFORM_PIXIV
            matches("pinimg.com") -> LinkNormalizer.PLATFORM_PINTEREST
            else -> original
        }
    }
    fun filter(headers: Map<String, String>, enabled: Boolean): Map<String, String> =
        if (enabled) headers else headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Cookie2", true) }
}
