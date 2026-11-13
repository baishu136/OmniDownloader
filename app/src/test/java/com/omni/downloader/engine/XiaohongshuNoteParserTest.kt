package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class XiaohongshuNoteParserTest {
    private val id = "6ab8bf110000000013019e4e"
    private val url = "https://www.xiaohongshu.com/discovery/item/$id?xsec_token=fixture"
    private val stream = """{"h264":[{"masterUrl":"https://cdn.example/hd.mp4","width":1280,"height":720,"duration":1000,"fps":30}],
        "h266":[{"backupUrls":["https://cdn.example/4k.mp4","https://cdn.example/4k-backup.mp4"],"width":3840,"height":2160,"duration":1000,"fps":60}]}"""
    private fun video(noteId: String = id) = """{"noteId":"$noteId","type":"video","title":"undefined and } braces","video":{"media":{"stream":$stream}}}"""
    private fun page(state: String) = "<script>window.__INITIAL_STATE__=$state; window.later={};</script>"
    private fun mobile(note: String) = page("""{"noteData":{"data":{"noteData":$note}}}""")
    private fun legacy(note: String) = page("""{"note":{"noteDetailMap":{"$id":{"note":$note}}}}""")

    @Test fun mobileShareStateAndLegacyDesktopStateReturnTheSameTargetVideo() {
        val desktop = XiaohongshuNoteParser.parseVideo(legacy(video()), url)
        val mobile = XiaohongshuNoteParser.parseVideo(mobile(video()), url)
        assertEquals(desktop, mobile)
        assertEquals(id, mobile.mediaId)
        assertEquals("undefined and } braces", mobile.title)
        assertEquals(3, mobile.availableVideoFormats.size)
        assertEquals(2160, mobile.availableVideoFormats.first().height)
        assertEquals(60, mobile.availableVideoFormats.first().fps)
        assertEquals(1000, mobile.expectedDurationMs)
    }

    @Test fun doesNotUseRecommendationWhenRequestedNoteIsMissing() {
        assertCode("MEDIA_ID_MISMATCH", mobile(video("other")))
        val state = """{"note":{"noteDetailMap":{"other":{"note":${video("other")}}}}}"""
        assertCode("MEDIA_ID_MISMATCH", page(state))
    }

    @Test fun rejectsWrongEmbeddedIdEvenWhenMapKeyMatches() {
        assertCode("MEDIA_ID_MISMATCH", legacy(video("other")))
    }

    @Test fun confirmedImagesAndMissingVideoStreamsAreDifferentFailures() {
        assertCode("NO_MEDIA", mobile("""{"noteId":"$id","type":"normal","imageList":[{}]}"""))
        assertCode("MEDIA_UNCONFIRMED", mobile("""{"noteId":"$id","type":"video","video":{"media":{"stream":{}}}}"""))
        assertCode("MEDIA_UNCONFIRMED", mobile("""{"noteId":"$id","type":"video"}"""))
        assertCode("EXTRACTOR_CHANGED", page("{}"))
    }

    @Test fun handlesUndefinedOutsideQuotedStringsAndStopsAtBalancedObject() {
        val state = """{"unused":undefined,"nested":[undefined],"noteData":{"data":{"noteData":${video()}}}}"""
        assertEquals("undefined and } braces", XiaohongshuNoteParser.parseVideo(page(state), url).title)
    }

    @Test fun supportsAllBackupUrlsAndIgnoresNonMediaSchemes() {
        val bad = video().replace("https://cdn.example/hd.mp4", "javascript:alert(1)")
        val formats = XiaohongshuNoteParser.parseVideo(mobile(bad), url).availableVideoFormats
        assertEquals(2, formats.size)
        assertTrue(formats.all { it.height == 2160 })
    }

    @Test fun unboundGlobalMasterUrlCannotBecomeTargetMedia() {
        assertCode("EXTRACTOR_CHANGED", """<script>window.__INITIAL_STATE__={};</script><script>{"masterUrl":"https://cdn.example/unrelated.mp4"}</script>""")
    }

    @Test fun mobileVideoCoverUsesTheSameAssetWithoutH5WatermarkProcessing() {
        val asset = "1040g2sg327co2ik7n8e05pgsope0u3qtkjikbqo"
        val image = """"imageList":[{"fileId":"$asset","url":"https://sns-webpic-qc.xhscdn.com/202611131946/0123456789abcdef0123456789abcdef/$asset!h5_1080jpg"}],"""
        val note = video().replace("\"video\":", image + "\"video\":")
        assertEquals("https://ci.xiaohongshu.com/$asset?imageView2/format/jpg",
            XiaohongshuNoteParser.parseVideo(mobile(note), url).thumbnailUrl)
    }

    @Test fun oldH5CoverWithoutFileIdStillKeepsItsExactAssetIdentity() {
        val asset = "1040g2sg327co2ik7n8e05pgsope0u3qtkjikbqo"
        assertEquals("https://ci.xiaohongshu.com/$asset?imageView2/format/jpg",
            XiaohongshuImageSource.originalUrl("https://sns-webpic-qc.xhscdn.com/202611131946/hash/$asset!h5_1080jpg"))
    }

    @Test fun unknownHostsCredentialsAndTraversalDoNotBecomeImageSources() {
        for (url in listOf("https://sns-webpic-qc.xhscdn.com.evil.test/image!h5_1080jpg",
                "https://user:password@sns-webpic-qc.xhscdn.com/1040g2sg327co2ik7n8e05pgsope0u3qtkjikbqo!h5_1080jpg",
                "https://sns-webpic-qc.xhscdn.com/%2e%2e%2fsecret!h5_1080jpg"))
            assertNull(XiaohongshuImageSource.originalUrl(url))
    }

    @Test fun suppliedAssetSourceKeepsFormatAndSignature() {
        val url = "https://ci.xiaohongshu.com/1040g2sg327co2ik7n8e05pgsope0u3qtkjikbqo?imageView2/format/png&signature=KEEP%2FEXACT"
        assertEquals(url, XiaohongshuImageSource.originalUrl(url))
    }

    @Test fun unlocatableStyledCoverIsNotSilentlyDownloadedAsTheOriginal() {
        val error = runCatching { XiaohongshuImageSource.downloadUrl("https://sns-webpic-qc.xhscdn.com/unknown!h5_1080jpg") }.exceptionOrNull()
        assertEquals(MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrors.codeOf(error!!))
        assertEquals("https://unrelated.test/image.jpg", XiaohongshuImageSource.downloadUrl("https://unrelated.test/image.jpg"))
    }

    private fun assertCode(code: String, html: String) {
        val error = runCatching { XiaohongshuNoteParser.parseVideo(html, url) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().startsWith("[$code]"))
    }
}
