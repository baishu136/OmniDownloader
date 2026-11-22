package com.omni.downloader.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpPlatformsTest {
    private val generic = LinkNormalizer.PLATFORM_GENERIC
    private val playlist = "https://vimeo.com/showcase/42"

    @Test fun catalogExcludesBrokenAndGenericExtractorsAndSearchesAliases() {
        val catalog = YtDlpPlatforms.parseCatalog("""
            generic
            generic:quoted-html
            vimeo
            vimeo:album
            douyuTV
            sample (CURRENTLY BROKEN)
            [debug] ignored
            vimeo
        """.trimIndent())
        assertEquals(listOf("douyuTV", "vimeo", "vimeo:album"), catalog.map { it.id })
        assertEquals(listOf("douyuTV"), catalog.filter { it.matches("斗鱼") }.map { it.id })
        assertEquals(2, catalog.count { it.matches("ViMeO") })
    }

    @Test fun platformNameAndUnknownDimensionsComeFromActualEngineOutput() {
        val metadata = parse("""{
            "id":"123","extractor":"vimeo","formats":[
                {"format_id":"progressive","url":"https://cdn.example/123.mp4","ext":"mp4"},
                {"format_id":"separate-audio","url":"https://cdn.example/123.m4a","ext":"m4a","vcodec":"none","acodec":"aac"},
                {"format_id":"preview","url":"https://cdn.example/storyboard.jpg","ext":"jpg","vcodec":"none"},
                {"format_id":"drm","url":"https://cdn.example/drm.mp4","ext":"mp4","height":1080,"has_drm":true}
            ]
        }""")
        assertEquals("Vimeo", metadata.siteName)
        val video = metadata.availableVideoFormats.single()
        assertEquals("progressive", video.formatId)
        assertEquals(0, video.height)
        assertFalse(video.isVideoOnly)
        assertEquals("separate-audio", metadata.availableAudioFormats.single().formatId)
    }

    @Test fun shareLinksKeepQueryAndFragmentEvenWithoutASlash() {
        val url = "https://example.org?v=123&signature=a%2Fb#player"
        assertEquals(url, UrlSniffer.extractUrl("分享 $url。"))
        assertEquals(url, LinkNormalizer.normalize("分享 $url。").contentUrl)
    }

    @Test fun protectedAndNonWebStreamsDoNotCountAsDownloadableVideos() {
        val error = assertThrows(IllegalStateException::class.java) {
            parse("""{"formats":[
                {"format_id":"protected","url":"https://cdn.example/123.mp4","ext":"mp4","has_drm":"maybe"},
                {"format_id":"local","url":"file:///private/video.mp4","ext":"mp4","height":720}
            ]}""")
        }
        assertTrue(error.message.orEmpty().contains("[MEDIA_UNCONFIRMED]"))
    }

    @Test fun collectionWithDistinctPagesAndMediaOnlyEntriesKeepsTheirIdentity() {
        val metadata = parse("""{"entries":[
            null,
            {"id":"123","extractor":"vimeo","webpage_url":"https://vimeo.com/123","formats":[
                {"format_id":"http","url":"https://cdn.example/123.mp4","ext":"mp4"}]},
            {"id":"audio","extractor":"vimeo","formats":[
                {"format_id":"http","url":"https://cdn.example/track.m4a","ext":"m4a","vcodec":"none","acodec":"aac"}]},
            {"id":"456","extractor":"vimeo","url":"https://cdn.example/456.mp4","formats":[
                {"format_id":"http","url":"https://cdn.example/456.mp4","ext":"mp4"}]}
        ]}""")
        assertEquals(listOf("123", "456"), metadata.multiMediaList.map { it.mediaId })
        assertEquals(listOf("https://vimeo.com/123", playlist), metadata.multiMediaList.map { it.url })
        assertTrue(metadata.multiMediaList.any { it.mediaId == metadata.mediaId })
    }

    @Test fun collectionWithSeparatePagesReturnsAPreviewWithoutRootFormats() {
        val metadata = parse("""{"entries":[
            {"id":"123","webpage_url":"https://vimeo.com/123","formats":[
                {"format_id":"http","url":"https://cdn.example/123.mp4","ext":"mp4"}]},
            {"id":"456","webpage_url":"https://vimeo.com/456","formats":[
                {"format_id":"http","url":"https://cdn.example/456.mp4","ext":"mp4"}]}
        ]}""")
        assertEquals("123", metadata.mediaId)
        assertEquals(listOf("123", "456"), metadata.multiMediaList.map { it.mediaId })
    }

    @Test fun collectionWithOneVideoDownloadsItsPageInsteadOfTheCollection() {
        val metadata = parse("""{"entries":[
            {"id":"123","extractor":"vimeo","webpage_url":"https://vimeo.com/123","formats":[
                {"format_id":"http","url":"https://cdn.example/123.mp4","ext":"mp4"}]}
        ]}""")
        assertEquals("https://vimeo.com/123", metadata.url)
        assertTrue(metadata.multiMediaList.isEmpty())
    }

    private fun parse(json: String) = DownloadEngine.parseMetadataFromJson(JSONObject(json), playlist, generic)
}
