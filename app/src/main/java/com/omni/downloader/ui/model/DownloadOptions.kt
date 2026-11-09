package com.omni.downloader.ui.model

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import java.util.Locale

/** A muxed source can contain audio without a separate audio-only rendition. */
internal fun VideoMetadata.supportsAudioExtraction(): Boolean =
    availableAudioFormats.any { !it.isSynthetic } || availableVideoFormats.any {
        !it.isSynthetic && !it.isVideoOnly && it.acodec.isNotBlank() && !it.acodec.equals("none", true)
    }

/** Ugoira MP4/GIF/ZIP are export choices, not competing source qualities. */
internal fun VideoMetadata.hasSourceQualityChoices(): Boolean = availableVideoFormats.any {
    it.height > 0 && !it.isSynthetic && !it.formatId.startsWith("ugoira_")
}

/** Show only source dimensions, output container and known size; never invent an estimate. */
internal fun formatDisplaySummary(format: FormatOption, alternatives: List<FormatOption> = emptyList()): String {
    // An adaptive choice is a quality intent, not a distinct unmeasured stream.
    // Real unknown URLs must never inherit another stream's dimensions.
    val reference = if (format.isSynthetic && (format.width <= 0 || format.height <= 0))
        alternatives.filter { !it.isSynthetic && it.width > 0 && it.height > 0 && !it.isAudioOnly }
            .maxByOrNull { it.width.toLong() * it.height } else null
    val shown = reference ?: format
    val dimensions = if (shown.width > 0 && shown.height > 0) "${shown.width}×${shown.height}" else "分辨率待确认"
    val container = if (format.formatId == "ugoira_zip") "ZIP + JSON" else format.ext.uppercase(Locale.ROOT).ifBlank { "格式待确认" }
    return "$dimensions · $container · ${shown.approximateSize.ifBlank { "大小待确认" }}"
}
