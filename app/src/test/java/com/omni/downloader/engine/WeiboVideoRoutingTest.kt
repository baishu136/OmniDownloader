package com.omni.downloader.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboVideoRoutingTest {
    @Test fun normalizesVideoSharesWithoutLosingTheirIds() {
        val post = LinkNormalizer.normalize("转发微博 https://weibo.com/7827771738/N4xlMvjhI?from=share。")
        assertEquals(LinkNormalizer.PLATFORM_WEIBO, post.platform)
        assertEquals("https://m.weibo.cn/detail/N4xlMvjhI", post.contentUrl)
        assertEquals("N4xlMvjhI", post.contentId)
        assertEquals("https://m.weibo.cn/detail/4189191225395228",
            LinkNormalizer.normalize("https://m.weibo.cn/status/4189191225395228").contentUrl)
        assertEquals("https://weibo.com/tv/show/1034:4967272104787984",
            LinkNormalizer.normalize("https://video.weibo.com/show?from=share&fid=1034%3A4967272104787984").contentUrl)
        assertEquals(MediaPage.VIDEO, MediaAnalysisRouter.knownPage(post.contentUrl))
        assertTrue(LinkNormalizer.normalize("https://t.cn/A6WTpbEu").isShortLink)
        assertNull(LinkNormalizer.normalize("https://weibo.com/u/7827771738").contentId)
        assertEquals(LinkNormalizer.PLATFORM_GENERIC, LinkNormalizer.platformOf("https://weibo.com.example.org/123/abc"))
    }

    @Test fun mixedPostPreservesSeparateVideoIdentitiesWithTheSamePostUrl() {
        val url = "https://m.weibo.cn/detail/5120561132606436"
        val json = JSONObject("""{"entries":[
            {"id":"1034:5120560485826576","webpage_url":"$url","formats":[
                {"format_id":"720","url":"https://video.example/first.mp4","width":720,"height":900,"ext":"mp4"}]},
            {"id":"1034:5120560498409506","webpage_url":"$url","formats":[
                {"format_id":"original","url":"https://video.example/second.mp4","ext":"mp4"}]}
        ]}""")
        val media = DownloadEngine.parseMetadataFromJson(json, url, LinkNormalizer.PLATFORM_WEIBO)
        assertEquals(listOf("1034:5120560485826576", "1034:5120560498409506"), media.multiMediaList.map { it.mediaId })
        assertEquals(listOf(url, url), media.multiMediaList.map { it.url })
        assertEquals(0, media.multiMediaList[1].availableVideoFormats.single().height)
    }
}
