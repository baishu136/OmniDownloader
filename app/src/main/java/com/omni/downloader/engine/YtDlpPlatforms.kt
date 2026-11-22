package com.omni.downloader.engine

import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal object YtDlpPlatforms {
    const val COLLECTION_LIMIT = 100

    // yt-dlp supplies the complete catalog. Aliases keep common names searchable
    // when the engine uses separate IDs for videos, live streams and playlists.
    private val displayNames = mapOf(
        "acfunbangumi" to "AcFun",
        "acfunvideo" to "AcFun",
        "archive.org" to "Internet Archive",
        "bbc" to "BBC",
        "bbc.co.uk" to "BBC iPlayer",
        "dailymotion" to "Dailymotion",
        "douyushow" to "斗鱼 / Douyu",
        "douyutv" to "斗鱼 / Douyu",
        "huya" to "虎牙 / Huya",
        "iqiyi" to "爱奇艺 / iQIYI",
        "iq.com" to "爱奇艺国际版 / iQIYI",
        "ixigua" to "西瓜视频 / Xigua Video",
        "kick" to "Kick",
        "peertube" to "PeerTube",
        "reddit" to "Reddit",
        "tedtalk" to "TED",
        "tedplaylist" to "TED",
        "tedseries" to "TED",
        "twitch" to "Twitch",
        "vimeo" to "Vimeo",
        "weibo" to "微博 / Weibo",
        "weibouser" to "微博 / Weibo",
        "weibovideo" to "微博 / Weibo",
        "wetv" to "WeTV",
        "wetvseries" to "WeTV",
        "xiaohongshu" to "小红书 / Xiaohongshu",
        "xinpianchang" to "新片场 / Xinpianchang",
        "youku" to "优酷 / Youku"
    )

    data class Extractor(val id: String, val platform: String?) {
        fun matches(query: String): Boolean =
            id.contains(query, ignoreCase = true) || platform?.contains(query, ignoreCase = true) == true
    }

    private fun family(id: String): String = id.substringBefore(':').lowercase(Locale.ROOT)

    fun parseCatalog(output: String): List<Extractor> = output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('[') && !it.endsWith("(CURRENTLY BROKEN)", ignoreCase = true) }
        .filter { family(it) != "generic" }
        .distinct()
        .map { Extractor(it, displayNames[family(it)]) }
        .sortedBy { it.id.lowercase(Locale.ROOT) }
        .toList()

    fun displayName(json: JSONObject): String? {
        val extractor = json.optString("extractor").ifBlank { json.optString("extractor_key") }
        val family = family(extractor)
        return displayNames[family]
            ?: extractor.takeUnless { family in setOf("", "generic", "null") }
    }

    private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "ts", "mpeg", "mpg", "ogv", "avi", "wmv")

    fun isHttpUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme in setOf("https", "http") && it.host != null && it.rawUserInfo == null }
    }.getOrDefault(false)

    fun isClearWebFormat(format: JSONObject): Boolean =
        !format.optBoolean("has_drm") &&
            !format.optString("has_drm").equals("maybe", ignoreCase = true) &&
            isHttpUrl(format.optString("url"))

    fun isVideoFormat(format: JSONObject): Boolean {
        val codec = format.optString("vcodec").lowercase(Locale.ROOT)
        return codec != "none" && (
            format.optInt("width") > 0 || format.optInt("height") > 0 ||
                codec !in setOf("", "unknown", "null") ||
                format.optString("ext").lowercase(Locale.ROOT) in videoExtensions
            )
    }
}
