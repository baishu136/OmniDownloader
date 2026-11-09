package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class DouyinVideoParserTest {
    private val id = "7696865347672319930"
    private val url = "https://www.iesdouyin.com/share/video/$id/"
    private fun item(target: String = id) = """{"aweme_id":"$target","desc":"target","video":{"duration":5500,"width":1080,"height":1920,"play_addr":{"url_list":["https://cdn.test/base.mp4"]},"bit_rate":[{"play_addr_h265":{"width":2160,"height":3840,"url_list":["https://cdn.test/high.mp4","https://cdn.test/backup.mp4"]}}]}}"""

    @Test fun skipsIdOnlyShellAndRecommendationsAndKeepsAllRealFormats() {
        val html = "<script>window._ROUTER_DATA={\"shell\":{\"aweme_id\":\"$id\",\"video\":{\"play_addr\":{}}},\"wrong\":${item("111")},\"target\":${item()}}</script>"
        val metadata = DouyinVideoParser.parseHtml(html, url)!!
        assertEquals(id, metadata.mediaId); assertEquals(5500L, metadata.expectedDurationMs)
        assertEquals(3, metadata.availableVideoFormats.size)
        assertEquals(2160, metadata.availableVideoFormats.first().width)
        assertEquals("https://cdn.test/high.mp4", DomesticMediaSource.url(metadata, metadata.availableVideoFormats.first { it.sourceUrl.endsWith("high.mp4") }))
    }

    @Test fun supportsRenderedDataAndRejectsWrongCapturedIdentity() {
        val html = "<script id=\"RENDER_DATA\">${URLEncoder.encode("{\"detail\":${item()}}", "UTF-8")}</script>"
        assertEquals(id, DouyinVideoParser.parseHtml(html, url)!!.mediaId)
        assertNull(DouyinVideoParser.parseHtml("<script>window._ROUTER_DATA=${item("111")}</script>", url))
        assertEquals(MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrors.codeOf(runCatching { DouyinVideoParser.parseItem(item("111"), url) }.exceptionOrNull()!!))
    }

    @Test fun uploadDimensionsDoNotMislabelLowerResolutionPlaybackSources() {
        val metadata = DouyinVideoParser.parseItem("""{"aweme_id":"$id","video":{"width":1080,"height":1920,"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?ratio=720p&video_id=test"]}}}""", url)
        val format = metadata.availableVideoFormats.single()
        assertEquals(0, format.width); assertEquals(0, format.height)
        assertEquals("源视频（尺寸未知）", format.resolutionLabel)
        assertTrue(format.sourceUrl.contains("ratio=720p"))
    }

    @Test fun photoPostsAndUnusableVideoDoNotPretendVideoSuccess() {
        assertEquals(MediaErrorCode.NO_MEDIA, MediaErrors.codeOf(runCatching {
            DouyinVideoParser.parseItem("""{"aweme_id":"$id","images":[{"url_list":["https://cdn.test/photo.jpg"]}]}""", url)
        }.exceptionOrNull()!!))
        assertEquals(MediaErrorCode.BROWSER_ASSIST_REQUIRED, MediaErrors.codeOf(runCatching {
            DouyinVideoParser.parseItem("""{"aweme_id":"$id","video":{"play_addr":{"url_list":["javascript:bad","https://user:password@cdn.test/file"]}}}""", url)
        }.exceptionOrNull()!!))
    }

    @Test fun mergesLowerShareAndHigherDetailForTheSameWork() {
        val low = item().replace("2160", "720").replace("3840", "1280")
            .replace("high.mp4", "low.mp4").replace("backup.mp4", "low-backup.mp4")
        val html = "<script>window._ROUTER_DATA={\"share\":$low,\"detail\":${item()}}</script>"
        val formats = DouyinVideoParser.parseHtml(html, url)!!.availableVideoFormats
        assertEquals(2160, formats.first().width)
        assertTrue(formats.any { it.width == 720 })
    }

    @Test fun desktopCamelCaseRenditionsArePreservedAndRankedBeforeUnknownLowStreams() {
        val json = """{"awemeId":"$id","video":{"playAddr":{"urlList":["https://cdn.test/low.mp4"]},
            "bitRateList":[{"bitRate":8000000,"playAddr":{"urlList":["https://cdn.test/high.mp4"]}},
            {"bitRate":1000000,"playAddr":{"urlList":["https://cdn.test/other-low.mp4"]}}]}}"""
        val formats = DouyinVideoParser.parseHtml("<script>window._ROUTER_DATA=$json</script>", url)!!.availableVideoFormats
        assertEquals(3, formats.size)
        assertTrue(formats.first().sourceUrl.endsWith("high.mp4"))
        assertEquals(8000.0, formats.first().videoBitrateKbps, 0.0)
        assertEquals(0, formats.first().width) // No invented size from bitrate.
    }
}
