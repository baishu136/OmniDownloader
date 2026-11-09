package com.omni.downloader.engine

import java.net.URI
import java.net.URLDecoder

/** Match the shared video, keeping collection ordering independent from selection. */
internal object MediaSelection {
    fun childUrl(requestedUrl: String, site: String, index: Int, webpageUrl: String, originalUrl: String, mediaUrl: String, id: String,
        formatUrls: List<String> = emptyList()): String? {
        val page = listOf(webpageUrl, originalUrl).firstOrNull { runCatching { URI(it).let { u -> u.scheme in setOf("https", "http") && u.host != null } }.getOrDefault(false) }
        if (site == "X (Twitter)") {
            // 上游的 /video/N 按“原始媒体列表”定位（其中可能包含图片），
            // 过滤后的视频列表序号与它并不等价，不能按 index 拼地址。
            // 只采用平台明确返回的单媒体地址；缺失时退回该 entry 的直链。
            val postId = LinkNormalizer.contentId(requestedUrl, site)?.substringBefore(':')
            fun samePost(value: String) = isHttpUrl(value) && LinkNormalizer.platformOf(value) == site && postId != null &&
                LinkNormalizer.contentId(value, site)?.substringBefore(':') == postId
            val explicit = listOf(webpageUrl, originalUrl).firstOrNull {
                samePost(it) && Regex("/video/\\d+(?:[/?#]|$)").containsMatchIn(it)
            }
            if (explicit != null) return explicit
            // Merged HLS results can have formats but no top-level URL. Keep
            // their post page; the snapshot and task use the entry's real media
            // ID, never an invented /video/N based on the filtered entry order.
            if (id.isNotBlank() && id != "null" && formatUrls.any { source -> runCatching {
                URI(source).let { it.scheme == "https" && it.host == "video.twimg.com" && it.userInfo == null }
            }.getOrDefault(false) }) {
                listOf(webpageUrl, originalUrl).firstOrNull(::samePost)?.let { return it }
            }
            if (isHttpUrl(mediaUrl)) return mediaUrl
            return null
        }
        if (page != null) return page
        if (site == "YouTube" && Regex("[A-Za-z0-9_-]{6,64}").matches(id)) return "https://www.youtube.com/watch?v=$id"
        return mediaUrl.takeIf { runCatching { URI(it).let { u -> u.scheme in setOf("https", "http") && u.host != null } }.getOrDefault(false) }
    }

    fun initialIndex(requestedUrl: String, resolvedUrl: String, children: List<String>): Int {
        if (children.isEmpty()) return -1
        for (source in listOf(requestedUrl, resolvedUrl)) {
            if (LinkNormalizer.platformOf(source) == LinkNormalizer.PLATFORM_X &&
                !Regex("/video/\\d+(?:[/?#]|$)").containsMatchIn(source)) {
                val post = LinkNormalizer.contentId(source, LinkNormalizer.PLATFORM_X)
                children.indexOfFirst { post != null && LinkNormalizer.platformOf(it) == LinkNormalizer.PLATFORM_X &&
                    LinkNormalizer.contentId(it, LinkNormalizer.PLATFORM_X)?.substringBefore(':') == post }
                    .takeIf { it >= 0 }?.let { return it }
            }
            val key = identity(source) ?: continue
            val index = children.indexOfFirst { identity(it) == key }
            if (index >= 0) return index
        }
        // The current resolved video can be outside a partial/private collection.
        // Keep its metadata rather than silently selecting an unrelated first item.
        return if (identity(resolvedUrl) != null) -1 else 0
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching { URI(value).let { it.scheme in setOf("https", "http") && it.host != null && it.userInfo == null } }
            .getOrDefault(false)

    fun identity(value: String): String? = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path.orEmpty().trimEnd('/')
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        when {
            host == "bilibili.com" || host.endsWith(".bilibili.com") -> {
                val id = Regex("/video/([^/]+)").find(path)?.groupValues?.get(1) ?: return null
                "bilibili:$id:p${query["p"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1}"
            }
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val id = if (host == "youtu.be") path.trimStart('/').substringBefore('/')
                    else query["v"] ?: Regex("/(?:shorts|embed|live)/([^/]+)").find(path)?.groupValues?.get(1)
                id?.takeIf { it.isNotBlank() }?.let { "youtube:$it" }
            }
            host == "x.com" || host == "twitter.com" || host.endsWith(".twitter.com") -> {
                val match = Regex("/status/(\\d+)(?:/video/(\\d+))?").find(path) ?: return null
                "x:${match.groupValues[1]}:video${match.groupValues[2].ifBlank { "1" }}"
            }
            else -> "$host${if (uri.port >= 0) ":${uri.port}" else ""}$path?${uri.rawQuery.orEmpty()}"
        }
    }.getOrNull()
}
