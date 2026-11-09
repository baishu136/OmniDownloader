package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadType
import org.junit.Assert.*
import org.junit.Test

class MediaFileTypeTest {
    @Test fun legacyMp4FromGifFallbackOpensAsVideo() {
        val header = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray()
        assertEquals("video/mp4", MediaFileType.mime(header, "old.gif", "image/gif", DownloadType.GIF))
        assertEquals("video/mp4", MediaFileType.mime(null, "old.mp4", null, DownloadType.GIF))
    }
    @Test fun actualGifAlwaysUsesImageMimeForPreviewAndSharing() {
        assertEquals("image/gif", MediaFileType.mime("GIF89a123456".toByteArray(), "1", "video/mp4", DownloadType.VIDEO_WITH_AUDIO))
        assertEquals("image/gif", MediaFileType.mime(null, "download.GIF", null, DownloadType.GIF))
    }
    @Test fun numericContentUrisUseTheirProviderMime() {
        assertEquals("image/gif", MediaFileType.mime(null, "content://media/external/downloads/1", "image/gif", DownloadType.GIF))
        assertEquals("video/mp4", MediaFileType.mime(null, "content://media/external/downloads/1", "video/mp4", DownloadType.GIF))
    }
    @Test fun mp4AudioAndAvifAreNotMisclassifiedAsVideo() {
        assertEquals("audio/mp4", MediaFileType.mime(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray(), "1", null, DownloadType.AUDIO_ONLY))
        assertEquals("image/avif", MediaFileType.mime(byteArrayOf(0, 0, 0, 24) + "ftypavif".toByteArray(), "1", null, DownloadType.IMAGE))
    }
}
