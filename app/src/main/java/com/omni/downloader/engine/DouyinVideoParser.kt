package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder

/** Prefer a media-bearing target item over the ID-only shell or recommendations. */
internal object DouyinVideoParser {
    fun parseHtml(html: String, url: String): VideoMetadata? {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN) ?: return null
        val router = EmbeddedStateReader.read(html, Regex("""(?:window\.)?_ROUTER_DATA\s*="""))
        val render = Regex("""<script[^>]+id=["']RENDER_DATA["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { Json.parseToJsonElement(URLDecoder.decode(it, "UTF-8")) }.getOrNull() }
        val items = mutableListOf<JsonObject>()
        collect(router, id, items)
        collect(render, id, items)
        if (items.isEmpty()) return null
        val parsed = items.map { parseItem(it.toString(), url) }
        return parsed.drop(1).fold(parsed.first(), DouyinQualityPolicy::merge)
    }

    fun parseItem(json: String, url: String): VideoMetadata {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN)
            ?: error("[MEDIA_ID_MISMATCH] 无法确认目标抖音作品 ID")
        val item = Json.parseToJsonElement(json).jsonObject
        check(item.text("aweme_id").ifBlank { item.text("awemeId") } == id) { "[MEDIA_ID_MISMATCH] 抖音页面数据与目标作品不一致" }
        if (images(item)) error("[NO_MEDIA] 这是图文作品，请使用图文页")
        val video = item.obj("video") ?: error("[BROWSER_ASSIST_REQUIRED] 页面尚未提供目标视频数据，请完成验证后提取")
        val formats = mutableListOf<FormatOption>()
        fun append(address: JsonObject?, note: String, bitrate: Long = 0, fps: Int = 0, codec: String = "", range: String = "") {
            if (address == null) return
            // video.width/height describe the upload, not necessarily this
            // transcode (e.g. a 1080p upload may expose only a 720p play URL).
            val width = address.number("width").toInt().coerceAtLeast(0)
            val height = address.number("height").toInt().coerceAtLeast(0)
            ((address["url_list"] ?: address["urlList"]) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter(::mediaUrl).map(::playbackUrl).distinct().forEach { source ->
                    formats += FormatOption(formatId = source, sourceUrl = source, width = width, height = height,
                        resolutionLabel = if (width > 0 || height > 0) QualitySelector.label(width, height) else "源视频（尺寸未知）",
                        vcodec = address.text("codec_type").ifBlank { address.text("codecType") }.ifBlank { codec }, fps = fps,
                        dynamicRange = address.text("dynamic_range").ifBlank { address.text("dynamicRange") }.ifBlank { range },
                        videoBitrateKbps = bitrate.coerceAtLeast(0) / 1000.0,
                        note = note, sourceHeaders = DomesticMediaSource.headers("抖音"))
                }
        }
        ((video["bit_rate"] ?: video["bitRateList"]) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { rate ->
            addresses(rate).forEach { append(it, rate.text("gear_name").ifBlank { rate.text("gearName") },
                rate.number("bit_rate").takeIf { n -> n > 0 } ?: rate.number("bitRate"), rate.number("fps").toInt(),
                rate.text("codec_type").ifBlank { rate.text("codecType") }, rate.text("dynamic_range").ifBlank { rate.text("dynamicRange") }) }
        }
        addresses(video).forEach { append(it, "平台视频源") }
        if (formats.isEmpty()) error("[BROWSER_ASSIST_REQUIRED] 目标作品尚未提供可用视频流，请在浏览器完成验证后提取")
        val duration = video.number("duration").takeIf { it > 0 } ?: item.number("duration")
        val cover = video.obj("cover")?.get("url_list") as? JsonArray
        return VideoMetadata(url = url, siteName = "抖音", mediaId = id,
            title = item.text("desc").ifBlank { "抖音_$id" }, author = item.obj("author")?.text("nickname").orEmpty(),
            thumbnailUrl = cover.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull(::mediaUrl).orEmpty(),
            expectedDurationMs = duration, durationText = if (duration > 0) "%02d:%02d".format(duration / 60000, duration / 1000 % 60) else "",
            referenceWidth = video.number("width").toInt().coerceAtLeast(0), referenceHeight = video.number("height").toInt().coerceAtLeast(0),
            availableVideoFormats = QualitySelector.sorted(formats).distinctBy { it.sourceUrl })
    }

    private fun collect(value: JsonElement?, id: String, items: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 25 || items.size >= 16) return
        if (value is JsonObject) {
            if (value.text("aweme_id").ifBlank { value.text("awemeId") } == id &&
                (images(value) || value.obj("video")?.let(::playable) == true)) { items += value; return }
            value.values.forEach { collect(it, id, items, depth + 1) }
        } else if (value is JsonArray) value.forEach { collect(it, id, items, depth + 1) }
    }
    private fun images(item: JsonObject) = !(item["images"] as? JsonArray).isNullOrEmpty() ||
        !(item.obj("image_post_info")?.get("images") as? JsonArray).isNullOrEmpty()
    private fun playable(video: JsonObject): Boolean {
        val variants = listOf(video) + ((video["bit_rate"] ?: video["bitRateList"]) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return variants.any { variant -> addresses(variant).any { address ->
            ((address["url_list"] ?: address["urlList"]) as? JsonArray).orEmpty()
                .any { mediaUrl((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
        } }
    }
    private fun addresses(value: JsonObject) = value.filterKeys { it.startsWith("play_addr") || it.startsWith("playAddr") }
        .values.mapNotNull { it as? JsonObject }
    private fun mediaUrl(url: String) = runCatching { URI(url).let { it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    /** Legacy share streams append a platform outro. Keep the media ID and rendition
     * parameters on the standard playback route; never rewrite signed/CDN URLs. */
    internal fun playbackUrl(url: String): String = runCatching {
        val uri = URI(url)
        val fields = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        val allowed = setOf("video_id", "ratio", "line", "logo_name", "watermark", "media_type")
        val knownQuery = fields.all { it.substringBefore('=') in allowed } &&
            fields.any { it.substringBefore('=') == "video_id" && it.substringAfter('=', "").isNotBlank() }
        if (uri.scheme == "https" && uri.host.equals("aweme.snssdk.com", true) && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && uri.path == "/aweme/v1/playwm/" && knownQuery)
            url.replaceFirst("/aweme/v1/playwm/", "/aweme/v1/play/") else url
    }.getOrDefault(url)
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
