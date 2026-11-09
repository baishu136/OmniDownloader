package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Regression176Test {
    @Test fun kuaishouShareDomainsRetainPlatformAndPhotoIdentity() {
        val url = "https://kphm5nf3.m.chenzhongtech.com/fw/photo/3xfsgzwdzmh8dv2?shareObjectId=999"
        val normalized = LinkNormalizer.normalize(url)
        assertEquals("快手", normalized.platform)
        assertEquals("https://www.kuaishou.com/short-video/3xfsgzwdzmh8dv2", normalized.contentUrl)
        assertEquals(LinkNormalizer.PLATFORM_GENERIC, LinkNormalizer.platformOf("https://chenzhongtech.com.evil.invalid/photo/1"))
    }
    @Test fun networkFailureIsNotMisclassifiedAsQualityUnavailable() {
        assertEquals(MediaErrorCode.NETWORK_ERROR, MediaErrors.classify("Failed to download m3u8: Connection timed out; No video formats found!"))
        assertEquals(MediaErrorCode.ACCESS_DENIED, MediaErrors.classify("This content isn't available to everyone: It can't be seen by certain audiences."))
    }
    @Test fun localProbeUnderstandsSubsecondSilentLoopAndRejectsHtml() {
        val parsed = MediaVerifier.parseProbeLog("Duration: 00:00:00.55, start: 0.000000\n  Stream #0:0[0x1]: Video: h264 (High), yuv420p, 650x430, 20 fps")!!
        assertEquals(650, parsed.width); assertEquals(550L, parsed.durationMs); assertFalse(parsed.hasAudio)
        assertNull(MediaVerifier.parseProbeLog("<html>650x430</html>"))
    }
    private fun box(kind: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8).putInt(payload.size + 8).put(kind.toByteArray()).put(payload).array()
    @Test fun mp4RangeProbeRequiresCompleteTrackHeaderAndPreservesActualDimensions() {
        val tkhd = ByteBuffer.allocate(84).apply { position(76); putInt(3840 shl 16); putInt(2160 shl 16) }.array()
        val moov = box("moov", box("trak", box("tkhd", tkhd)))
        assertEquals(3840 to 2160, Mp4Dimensions.read("mdat suffix".toByteArray() + moov))
        assertNull(Mp4Dimensions.read(moov.copyOf(moov.size - 2)))
        assertNull(Mp4Dimensions.read("not MP4 moov garbage".toByteArray()))
    }
    @Test fun originalXhsSourceIsRestrictedToTheRequestedNoteAndSafeKey() {
        val url = "https://www.xiaohongshu.com/explore/abc123"
        fun page(id: String, key: String) = """window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"noteId":"$id","video":{"consumer":{"originVideoKey":"$key"}}}}}};</script>"""
        assertEquals("https://sns-video-bd.xhscdn.com/origin/safe.mp4", XiaohongshuNoteParser.originalVideoUrl(page("abc123", "origin/safe.mp4"), url))
        assertNull(XiaohongshuNoteParser.originalVideoUrl(page("abc123", "../../evil"), url))
        assertTrue(runCatching { XiaohongshuNoteParser.originalVideoUrl(page("other", "origin/safe.mp4"), url) }.isFailure)
    }
}
