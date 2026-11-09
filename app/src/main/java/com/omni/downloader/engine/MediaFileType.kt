package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadType

/** Prefer actual bytes when opening legacy tasks whose output type changed. */
internal object MediaFileType {
    fun mime(header: ByteArray?, name: String, declared: String?, type: DownloadType): String {
        val prefix = header?.take(12)?.toByteArray()
        if (prefix != null && prefix.size >= 6 && prefix.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return "image/gif"
        if (prefix != null && prefix.size >= 8 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            val brand = if (prefix.size >= 12) prefix.copyOfRange(8, 12).toString(Charsets.US_ASCII) else ""
            return when {
                brand in setOf("avif", "avis") -> "image/avif"
                brand in setOf("heic", "heix", "hevc", "hevx", "mif1") -> "image/heic"
                type == DownloadType.AUDIO_ONLY || declared?.startsWith("audio/") == true || name.endsWith(".m4a", true) -> "audio/mp4"
                else -> "video/mp4"
            }
        }
        if (prefix != null && prefix.size >= 4 && prefix.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)) return "video/webm"
        if (!declared.isNullOrBlank() && declared != "application/octet-stream") return declared
        return when (name.substringBefore('?').substringAfterLast('.').lowercase()) {
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4", "m4v", "mov" -> "video/mp4"
            "webm", "mkv" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> when (type) {
                DownloadType.GIF -> "image/gif"
                DownloadType.IMAGE, DownloadType.COVER -> "image/*"
                DownloadType.AUDIO_ONLY -> "audio/*"
                else -> "video/*"
            }
        }
    }
}
