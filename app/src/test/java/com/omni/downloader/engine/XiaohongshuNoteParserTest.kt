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

    private fun assertCode(code: String, html: String) {
        val error = runCatching { XiaohongshuNoteParser.parseVideo(html, url) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().startsWith("[$code]"))
    }
}
