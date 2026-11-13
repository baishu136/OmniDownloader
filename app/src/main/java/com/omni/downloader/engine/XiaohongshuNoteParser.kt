package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.serialization.json.*
import java.net.URI

/** Parse only the requested note. Never use a recommended note or a page-wide media regex. */
object XiaohongshuNoteParser {
    private val assignment = Regex("""(?:window\.)?__INITIAL_STATE__\s*=""")

    fun originalVideoUrl(html: String, url: String): String? {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_XHS) ?: return null
        val root = state(html) ?: return null
        val key = findNote(root, id).obj("video")?.obj("consumer")?.text("originVideoKey").orEmpty()
        if (key.isBlank() || !Regex("[a-zA-Z0-9_/-]+(?:\\.[a-zA-Z0-9]+)?").matches(key) || key.contains("..")) return null
        return "https://sns-video-bd.xhscdn.com/$key"
    }

    fun parseVideo(html: String, url: String): VideoMetadata {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_XHS)
            ?: error("[MEDIA_ID_MISMATCH] 无法确认目标小红书笔记 ID")
        val state = state(html) ?: error("[EXTRACTOR_CHANGED] 未能读取小红书笔记状态，请重试或更新解析规则")
        val note = findNote(state, id)
        val type = note.text("type")
        if (type == "normal") error("[NO_MEDIA] 该笔记为图文内容，请使用图文下载页")
        val video = note.obj("video")
            ?: error("[MEDIA_UNCONFIRMED] 目标笔记尚未返回视频信息，请验证会话后重试")
        val stream = video.obj("media")?.obj("stream")
        val formats = mutableListOf<FormatOption>()
        stream?.forEach { (codec, entries) ->
            val items = (entries as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: listOfNotNull(entries as? JsonObject)
            items.forEach { item ->
                val urls = listOf(item.text("masterUrl")) +
                    (item["backupUrls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val width = item.number("width").toInt()
                val height = item.number("height").toInt()
                urls.filter(::isMediaUrl).distinct().forEach { source ->
                    formats += FormatOption(
                        formatId = source, sourceUrl = source,
                        resolutionLabel = if (width > 0 || height > 0) QualitySelector.label(width, height) else "原画（尺寸未知）",
                        width = width, height = height, fps = item.number("fps").toInt(),
                        ext = if (URI(source).path.endsWith(".m3u8", true)) "m3u8" else "mp4",
                        vcodec = item.text("videoCodec").ifBlank { codec }, acodec = item.text("audioCodec"),
                        note = listOf(codec.uppercase(), item.text("qualityType")).filter { it.isNotBlank() }.joinToString(" · ")
                    )
                }
            }
        }
        if (formats.isEmpty()) error("[MEDIA_UNCONFIRMED] 目标视频笔记尚未返回可用媒体流，可重试或尝试通用解析")
        val image = (note["imageList"] as? JsonArray)?.firstOrNull() as? JsonObject
        val duration = stream?.values?.flatMap { (it as? JsonArray).orEmpty() }
            ?.mapNotNull { (it as? JsonObject)?.number("duration") }?.maxOrNull() ?: 0
        return VideoMetadata(
            url = url, title = note.text("title").ifBlank { note.text("desc") }.ifBlank { "小红书视频" },
            author = note.obj("user")?.text("nickname").orEmpty(),
            thumbnailUrl = XiaohongshuImageSource.originalUrl(image) ?: image?.text("urlDefault").orEmpty()
                .ifBlank { image?.text("urlPre").orEmpty() }.ifBlank { image?.text("url").orEmpty() },
            siteName = LinkNormalizer.PLATFORM_XHS, mediaId = id, expectedDurationMs = duration,
            durationText = if (duration > 0) "%02d:%02d".format(duration / 60_000, duration / 1000 % 60) else "",
            availableVideoFormats = QualitySelector.sorted(formats.distinctBy { it.formatId })
        )
    }

    private fun findNote(root: JsonObject, id: String): JsonObject {
        val detailMap = root.obj("note")?.obj("noteDetailMap")
        val legacy = detailMap?.get(id) as? JsonObject
        val candidates = listOfNotNull(legacy?.obj("note") ?: legacy,
            root.obj("noteData")?.obj("data")?.obj("noteData"))
        candidates.forEach { note ->
            val actual = note.text("noteId").ifBlank { note.text("note_id") }
            if (actual == id || (actual.isBlank() && legacy != null && note === candidates.first())) return note
        }
        if (candidates.isNotEmpty() || !detailMap.isNullOrEmpty())
            error("[MEDIA_ID_MISMATCH] 页面未包含目标笔记，已拒绝使用其他内容")
        error("[EXTRACTOR_CHANGED] 页面未返回可识别的目标笔记结构")
    }

    private fun state(html: String): JsonObject? = EmbeddedStateReader.read(html, assignment)

    private fun isMediaUrl(url: String): Boolean = runCatching {
        val parsed = URI(url)
        parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.userInfo == null &&
            !parsed.path.endsWith(".html", true) && !parsed.path.contains("/api/")
    }.getOrDefault(false)

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
