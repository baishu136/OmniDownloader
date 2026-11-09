package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.serialization.json.*
import java.net.URI

/** Kuaishou Apollo entities are keyed by photo ID; recommendations are never fallback media. */
object KuaishouNoteParser {
    fun parse(html: String, url: String): VideoMetadata {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_KUAISHOU)
            ?: error("[MEDIA_ID_MISMATCH] 未能确认目标快手作品 ID")
        val state = EmbeddedStateReader.read(html, Regex("""(?:window\.)?__APOLLO_STATE__\s*="""))
            ?: error("[BROWSER_ASSIST_REQUIRED] 快手未返回可解析的内容页，请在内置浏览器登录并播放目标作品")
        val entities = state.obj("defaultClient") ?: state
        val photo = entities.obj("VisionVideoDetailPhoto:$id")
            ?: error("[MEDIA_ID_MISMATCH] 快手页面没有目标作品，拒绝使用推荐视频")
        check(photo.text("id") == id) { "[MEDIA_ID_MISMATCH] 快手作品 ID 不一致" }
        val formats = mutableListOf<FormatOption>()
        fun unwrap(value: JsonObject?): JsonObject? = value?.obj("json") ?: value
        fun appendManifest(manifest: JsonObject?, codec: String) {
            (manifest?.get("adaptationSet") as? JsonArray).orEmpty().forEach { rawSet ->
                val set = rawSet as? JsonObject ?: return@forEach
                (set["representation"] as? JsonArray).orEmpty().forEach inner@{ raw ->
                    val item = raw as? JsonObject ?: return@inner
                    val backup = item["backupUrl"]
                    val urls = listOf(item.text("url")) + when (backup) {
                        is JsonArray -> backup.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> listOfNotNull(backup.contentOrNull)
                        else -> emptyList()
                    }
                    val w = item.number("width").toInt(); val h = item.number("height").toInt()
                    urls.distinct().filter(::mediaUrl).forEach { source -> formats += FormatOption(
                        formatId = source, sourceUrl = source, resolutionLabel = if (w > 0 || h > 0) QualitySelector.label(w, h) else "原画（尺寸未知）",
                        width = w, height = h, fps = (item["frameRate"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                        vcodec = item.text("codecs").ifBlank { codec }, note = item.text("qualityLabel").ifBlank { codec }
                    ) }
                }
            }
        }
        val resource = unwrap(photo.obj("videoResource"))
        resource?.forEach { (codec, value) -> appendManifest(value as? JsonObject, codec) }
        appendManifest(unwrap(photo.obj("manifest")), "h264")
        appendManifest(unwrap(photo.obj("manifestH265")), "hevc")
        if (formats.isEmpty()) {
            listOf("photoUrl", "photoH265Url").map { photo.text(it) }.filter(::mediaUrl).forEach { source ->
                formats += FormatOption(source, "平台视频源（尺寸未知）", sourceUrl = source)
            }
        }
        if (formats.isEmpty()) error("[BROWSER_ASSIST_REQUIRED] 目标快手作品尚未返回视频流，请在内置浏览器播放后捕获")
        val duration = photo.number("duration")
        return VideoMetadata(url = url, mediaId = id, siteName = LinkNormalizer.PLATFORM_KUAISHOU,
            title = photo.text("caption").ifBlank { "快手视频" }, thumbnailUrl = photo.text("coverUrl"),
            durationText = if (duration > 0) "%02d:%02d".format(duration / 60_000, duration / 1000 % 60) else "",
            expectedDurationMs = duration, availableVideoFormats = QualitySelector.sorted(formats.distinctBy { it.formatId }))
    }
    private fun mediaUrl(url: String) = runCatching { URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
