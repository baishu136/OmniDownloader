package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DomesticSourceRecoveryTest {
    private val id = "7699370307513319141"
    private val task = DownloadTask("fixture", "https://www.douyin.com/video/$id", "fixture",
        downloadType = DownloadType.VIDEO_WITH_AUDIO, mediaId = id, directMediaUrl = "https://a.test/high.mp4?expired=1",
        selectedWidth = 1080, selectedHeight = 1920, qualitySelection = QualitySelection(QualityMode.EXACT_FORMAT, 1080))
    private fun format(url: String, w: Int = 1080, h: Int = 1920) = FormatOption(url, "fixture", w, h, sourceUrl = url)
    private fun metadata(vararg formats: FormatOption) = VideoMetadata(task.url, "fixture", siteName = "抖音", mediaId = id, availableVideoFormats = formats.toList())

    @Test fun refreshesSignedUrlsAndSkipsLowerResolutionAndRejectedEndpoints() {
        val options = metadata(format(task.directMediaUrl), format("https://a.test/low.mp4", 720, 1280), format("https://b.test/high.mp4?fresh=1"))
        val result = DomesticSourceRecovery.candidates(task, options, setOf(task.directMediaUrl))
        assertEquals(listOf("https://b.test/high.mp4?fresh=1"), result.map { it.sourceUrl })
    }
    @Test fun differentPostOrPlatformCannotBeUsedAsFallback() {
        val data = metadata(format("https://cdn.test/high.mp4"))
        assertTrue(DomesticSourceRecovery.candidates(task, data.copy(mediaId = "other"), emptySet()).isEmpty())
        assertTrue(DomesticSourceRecovery.candidates(task, data.copy(siteName = "小红书"), emptySet()).isEmpty())
        assertTrue(DomesticSourceRecovery.candidates(task, data.copy(url = "https://www.douyin.com/video/111"), emptySet()).isEmpty())
    }
    @Test fun exactSelectionDoesNotChangeItsResolutionOrUseUnknownStreams() {
        assertTrue(DomesticSourceRecovery.candidates(task, metadata(format("https://cdn.test/bigger.mp4", 2160, 3840),
            format("https://cdn.test/unknown.mp4", 0, 0)), emptySet()).isEmpty())
    }
    @Test fun sourceIdentityIncludesVideoAndQualityButExcludesRotatingSignatures() {
        assertEquals(DomesticSourceRecovery.resourceKey("https://a.test/play?video_id=v&ratio=1080p&sign=old"),
            DomesticSourceRecovery.resourceKey("https://b.test/play?sign=new&ratio=1080p&video_id=v"))
        assertNotEquals(DomesticSourceRecovery.resourceKey("https://a.test/play?video_id=v&ratio=720p"),
            DomesticSourceRecovery.resourceKey("https://a.test/play?video_id=v&ratio=1080p"))
    }
    @Test fun authorizationAndQualityFailuresAreNotBlindlyRetried() {
        assertTrue(DomesticSourceRecovery.canRecover(Exception("HTTP 403 Forbidden")))
        assertTrue(DomesticSourceRecovery.canRecover(Exception("Connection reset")))
        assertTrue(DomesticSourceRecovery.canRecover(Exception("wrapped", java.net.UnknownHostException("CDN unavailable"))))
        assertFalse(DomesticSourceRecovery.canRecover(Exception("[QUALITY_UNAVAILABLE] 低于所选画质")))
        assertFalse(DomesticSourceRecovery.canRecover(Exception("下载已取消")))
    }
}
