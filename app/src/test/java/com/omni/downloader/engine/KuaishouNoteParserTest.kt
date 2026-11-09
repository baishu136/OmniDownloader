package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class KuaishouNoteParserTest {
    private val id = "3x7drpm369pyk2i"
    private val url = "https://www.kuaishou.com/short-video/$id"
    private val video = """{"id":"$id","caption":"fixture undefined }","duration":33000,"videoResource":{"type":"json","json":{"h264":{"adaptationSet":[{"representation":[{"url":"https://cdn.invalid/720.mp4","backupUrl":["https://backup.invalid/720.mp4"],"width":1280,"height":720,"frameRate":30}]}]},"hevc":""}}}"""
    private fun page(photo: String = video, key: String = id) = """<script>window.__APOLLO_STATE__={"defaultClient":{"VisionVideoDetailPhoto:$key":$photo}};window.later={};</script>"""
    @Test fun extractsTargetApolloMediaAndPreservesMetadata() {
        val result = KuaishouNoteParser.parse(page(), url)
        assertEquals(id, result.mediaId)
        assertEquals(33000L, result.expectedDurationMs)
        assertEquals("fixture undefined }", result.title)
        assertEquals(2, result.availableVideoFormats.size)
        assertEquals(720, result.availableVideoFormats.first().height)
    }
    @Test fun wrongKeyOrEmbeddedIdentityCannotUseRecommendationMedia() {
        assertCode("MEDIA_ID_MISMATCH", page(key = "other"))
        assertCode("MEDIA_ID_MISMATCH", page(video.replace("\"id\":\"$id\"", "\"id\":\"other\"")))
    }
    @Test fun riskOrEmptyStateRequestsBrowserAssistanceRatherThanNoMedia() {
        assertCode("BROWSER_ASSIST_REQUIRED", "<html>risk response</html>")
        assertCode("BROWSER_ASSIST_REQUIRED", page("""{"id":"$id"}"""))
    }
    @Test fun supportsTargetPhotoUrlWhenManifestIsAbsent() {
        val result = KuaishouNoteParser.parse(page("""{"id":"$id","photoUrl":"https://cdn.invalid/video.mp4"}"""), url)
        assertEquals(1, result.availableVideoFormats.size)
    }
    private fun assertCode(code: String, html: String) {
        val error = runCatching { KuaishouNoteParser.parse(html, url) }.exceptionOrNull()
        assertNotNull(error); assertTrue(error!!.message.orEmpty().startsWith("[$code]"))
    }
}
