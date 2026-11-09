package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.QualityMode
import com.omni.downloader.data.model.VideoMetadata
import java.net.URI
import java.net.URLDecoder
import kotlin.math.min

/** Refresh expiring CDN URLs without changing the requested post or rendition. */
internal object DomesticSourceRecovery {
    val platforms = setOf(LinkNormalizer.PLATFORM_DOUYIN, LinkNormalizer.PLATFORM_XHS)

    fun resourceKey(url: String): String = runCatching {
        val uri = URI(url)
        val videoId = uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "video_id" }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
        // Host/signature changes may name the same file; video_id separates the
        // different resources served through a shared /play endpoint.
        val rendition = uri.rawQuery.orEmpty().split('&').filter {
            it.substringBefore('=') in setOf("ratio", "bit_rate", "br", "gear")
        }.sorted().joinToString("&")
        if (videoId.isNotBlank()) "${uri.path}|$videoId|$rendition"
        else uri.path.takeIf { it.length > 1 }?.let { "$it|$rendition" } ?: url
    }.getOrDefault(url)

    fun candidates(task: DownloadTask, metadata: VideoMetadata?, rejected: Set<String>): List<FormatOption> {
        if (metadata == null || task.mediaId.isBlank() || metadata.mediaId != task.mediaId ||
            metadata.siteName !in platforms || LinkNormalizer.platformOf(task.url) != metadata.siteName ||
            LinkNormalizer.contentId(metadata.url, metadata.siteName) != task.mediaId) return emptyList()
        val quality = task.qualitySelection
        val exact = quality.mode == QualityMode.EXACT_FORMAT
        return QualitySelector.sorted(metadata.availableVideoFormats.filter { format ->
            val known = format.width > 0 && format.height > 0
            val selectedKnown = task.selectedWidth > 0 && task.selectedHeight > 0
            val enough = if (selectedKnown) known && format.width >= task.selectedWidth && format.height >= task.selectedHeight
                else resourceKey(format.sourceUrl) == resourceKey(task.directMediaUrl)
            val sameSize = !selectedKnown || (format.width == task.selectedWidth && format.height == task.selectedHeight)
            val edge = if (known) min(format.width, format.height) else 0
            format.sourceUrl.startsWith("https://") && format.sourceUrl !in rejected &&
                !format.isAudioOnly && !format.isVideoOnly && !format.isSynthetic &&
                (enough || (quality.allowLowerQuality && known)) && (!exact || sameSize || quality.allowLowerQuality) &&
                (quality.maxHeight <= 0 || (edge > 0 && edge <= quality.maxHeight)) &&
                (quality.targetFps <= 0 || format.fps >= quality.targetFps) &&
                (quality.codecPreference == "auto" || format.vcodec.startsWith(quality.codecPreference, true)) &&
                (quality.dynamicRangePreference == "auto" || format.dynamicRange.equals(quality.dynamicRangePreference, true))
        }).distinctBy { it.sourceUrl }
    }

    fun canRecover(error: Throwable?): Boolean {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val message = causes.joinToString(" ") { it.message.orEmpty() }
        if (message.contains("cancel", true) || message.contains("取消") ||
            message.contains("QUALITY_UNAVAILABLE") || message.contains("MEDIA_ID_MISMATCH")) return false
        if (causes.any { it is java.net.UnknownHostException || it is java.net.SocketException ||
                it is java.net.SocketTimeoutException }) return true
        return Regex("(?i)\\b(403|404|410|408|500|502|503|504)\\b|timeout|timed out|connection|reset|expired|网络|过期|失效|中断").containsMatchIn(message)
    }
}
