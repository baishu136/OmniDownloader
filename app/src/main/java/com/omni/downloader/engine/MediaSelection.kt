package com.omni.downloader.engine

import java.net.URI
import java.net.URLDecoder

/** Match the shared video, keeping collection ordering independent from selection. */
internal object MediaSelection {
    fun childUrl(requestedUrl: String, site: String, index: Int, webpageUrl: String, originalUrl: String, mediaUrl: String, id: String): String? {
        val page = listOf(webpageUrl, originalUrl).firstOrNull { runCatching { URI(it).let { u -> u.scheme in setOf("https", "http") && u.host != null } }.getOrDefault(false) }
        if (site == "X (Twitter)") {
            if (page != null && Regex("/video/\\d+").containsMatchIn(page)) return page
            val base = (page ?: requestedUrl).substringBefore('?').substringBefore('#').replace(Regex("/video/\\d+/?$"), "").trimEnd('/')
            return "$base/video/${index + 1}"
        }
        if (page != null) return page
        if (site == "YouTube" && Regex("[A-Za-z0-9_-]{6,64}").matches(id)) return "https://www.youtube.com/watch?v=$id"
        return mediaUrl.takeIf { runCatching { URI(it).let { u -> u.scheme in setOf("https", "http") && u.host != null } }.getOrDefault(false) }
    }

    fun initialIndex(requestedUrl: String, resolvedUrl: String, children: List<String>): Int {
        if (children.isEmpty()) return -1
        for (source in listOf(requestedUrl, resolvedUrl)) {
            val key = identity(source) ?: continue
            val index = children.indexOfFirst { identity(it) == key }
            if (index >= 0) return index
        }
        // The current resolved video can be outside a partial/private collection.
        // Keep its metadata rather than silently selecting an unrelated first item.
        return if (identity(resolvedUrl) != null) -1 else 0
    }

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
