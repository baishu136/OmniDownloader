package com.omni.downloader.engine

import com.omni.downloader.data.model.VideoMetadata
import kotlin.math.min

/** Upload dimensions are evidence of a possible gap, never playback dimensions. */
internal object DouyinQualityPolicy {
    fun needsPage(metadata: VideoMetadata?): Boolean {
        val best = metadata?.availableVideoFormats?.firstOrNull() ?: return true
        val edge = min(best.width, best.height)
        val reference = metadata.referenceWidth.toLong() * metadata.referenceHeight
        val playback = best.width.toLong() * best.height
        if (best.width <= 0 || best.height <= 0) return true
        if (reference > 0) return reference > playback
        return (edge <= 720 && reference <= 0) || reference > playback ||
            metadata.availableVideoFormats.any { it.width == 0 || it.height == 0 }
    }

    fun merge(base: VideoMetadata?, extra: VideoMetadata): VideoMetadata {
        require(extra.siteName == LinkNormalizer.PLATFORM_DOUYIN && extra.mediaId.isNotBlank())
        if (base == null) return extra
        check(base.mediaId == extra.mediaId && base.siteName == extra.siteName) { "[MEDIA_ID_MISMATCH] 自动页面返回了其他作品" }
        val reference = if (base.referenceWidth.toLong() * base.referenceHeight >= extra.referenceWidth.toLong() * extra.referenceHeight) base else extra
        return base.copy(title = base.title.takeUnless { it == "抖音_${base.mediaId}" } ?: extra.title,
            author = base.author.ifBlank { extra.author }, thumbnailUrl = base.thumbnailUrl.ifBlank { extra.thumbnailUrl },
            expectedDurationMs = base.expectedDurationMs.takeIf { it > 0 } ?: extra.expectedDurationMs,
            referenceWidth = reference.referenceWidth, referenceHeight = reference.referenceHeight,
            availableVideoFormats = QualitySelector.sorted((extra.availableVideoFormats + base.availableVideoFormats)
                .groupBy { it.sourceUrl }.values.map { variants -> variants.firstOrNull { it.dimensionsVerified } ?: variants.first() }))
    }
}
