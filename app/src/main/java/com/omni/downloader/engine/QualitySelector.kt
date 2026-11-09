package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.QualityMode
import com.omni.downloader.data.model.QualitySelection
import kotlin.math.max
import kotlin.math.min

/** Pure selection policy shared by native and yt-dlp adapters. No UI labels as IDs. */
object QualitySelector {
    fun frameRate(value: String): Double {
        val parts = value.split('/')
        val numerator = parts.firstOrNull()?.toDoubleOrNull() ?: return 0.0
        val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (denominator > 0) numerator / denominator else 0.0
    }

    fun label(width: Int, height: Int): String {
        val edge = if (width > 0 && height > 0) min(width, height) else max(width, height)
        val tier = when (edge) { 4320 -> "8K"; 3240 -> "6K"; 2880 -> "5K"; 2160 -> "4K"; 1440 -> "1440p"; else -> "${edge}p" }
        return if (width > 0 && height > 0) "$tier · ${width}×${height}" else tier
    }

    fun sorted(formats: Collection<FormatOption>): List<FormatOption> {
        val unique = formats.distinctBy { listOf(it.formatId, it.width, it.height, it.fps, it.vcodec, it.acodec, it.dynamicRange) }
        fun rangeRank(f: FormatOption) = when { f.dynamicRange.isBlank() -> 0; f.dynamicRange.equals("SDR", true) -> 1; else -> 2 }
        fun family(f: FormatOption) = when (val codec = f.vcodec.lowercase().substringBefore('.')) {
            "avc1", "h264", "avc" -> "avc"; "hvc1", "hev1", "h265", "hevc" -> "hevc"; "av01", "av1" -> "av1"; else -> codec
        }
        fun key(f: FormatOption) = listOf(f.width, f.height, f.fps, f.isUpscaled, rangeRank(f), family(f))
        val codecOrder = unique.map(::key).distinct().withIndex().associate { it.value to it.index }
        return unique.sortedWith(compareByDescending<FormatOption> { it.width.toLong() * it.height }
            .thenByDescending { min(it.width, it.height) }.thenByDescending { it.fps }.thenBy { it.isUpscaled }
            .thenByDescending(::rangeRank).thenByDescending { it.dimensionsVerified }
            .thenBy { codecOrder[key(it)] ?: 0 }.thenByDescending { it.videoBitrateKbps })
    }

    fun select(formats: Collection<FormatOption>, quality: QualitySelection, exactId: String = ""): FormatOption {
        var candidates = formats.filter { !it.isAudioOnly && !it.isSynthetic }
        if (quality.dynamicRangePreference != "auto") candidates = candidates.filter {
            if (quality.dynamicRangePreference == "SDR") it.dynamicRange.equals("SDR", true)
            else it.dynamicRange.isNotBlank() && !it.dynamicRange.equals("SDR", true)
        }
        if (quality.codecPreference != "auto") candidates = candidates.filter { it.vcodec.startsWith(quality.codecPreference, true) }
        if (quality.targetFps > 0) candidates = candidates.filter { it.fps >= quality.targetFps }
        if (quality.mode == QualityMode.EXACT_FORMAT) {
            candidates.firstOrNull { it.formatId == exactId }?.let { return it }
            if (!quality.allowLowerQuality) error("[QUALITY_UNAVAILABLE] 指定格式已经失效，请重新选择")
        }
        if (quality.maxHeight > 0) candidates = candidates.filter {
            val edge = if (it.width > 0) min(it.width, it.height) else it.height
            edge > 0 && edge <= quality.maxHeight && (quality.mode != QualityMode.TARGET_TIER || quality.allowLowerQuality || edge == quality.maxHeight)
        }
        if (candidates.any { !it.isUpscaled }) candidates = candidates.filter { !it.isUpscaled }
        return sorted(candidates).firstOrNull() ?: error("[QUALITY_UNAVAILABLE] 当前没有满足画质条件的真实视频流")
    }
}
