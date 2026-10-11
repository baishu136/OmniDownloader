package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata

/** 仅国内原生直连格式可作为完整媒体源，避免误用 B站 DASH 的单独视频轨。 */
object DomesticMediaSource {
    fun url(metadata: VideoMetadata, selected: FormatOption? = null): String {
        if (metadata.siteName !in setOf("抖音", "快手", "小红书")) return ""
        val formats = metadata.availableVideoFormats
        return formats.firstOrNull { it.formatId == selected?.formatId && it.formatId.startsWith("http") }?.formatId
            ?: formats.firstOrNull { it.formatId.startsWith("http") }?.formatId
            ?: ""
    }

    fun headers(site: String): Map<String, String> {
        val referer = when (site) {
            "抖音" -> "https://www.douyin.com/"
            "快手" -> "https://www.kuaishou.com/"
            "小红书" -> "https://www.xiaohongshu.com/"
            else -> return emptyMap()
        }
        val userAgent = if (site == "小红书") {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        }
        return mapOf("User-Agent" to userAgent, "Referer" to referer)
    }
}
