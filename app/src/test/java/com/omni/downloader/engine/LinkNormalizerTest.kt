package com.omni.downloader.engine

import com.omni.downloader.data.model.QualityMode
import com.omni.downloader.data.model.qualitySelectionFromResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkNormalizerTest {

    @Test
    fun platformDetectionUsesHostNotSubstring() {
        // netflix.com 包含 "x.com"，旧实现会误判为 X
        assertEquals(LinkNormalizer.PLATFORM_GENERIC, LinkNormalizer.platformOf("https://www.netflix.com/watch/1"))
        assertEquals(LinkNormalizer.PLATFORM_X, LinkNormalizer.platformOf("https://x.com/user/status/1"))
        assertEquals(LinkNormalizer.PLATFORM_X, LinkNormalizer.platformOf("https://twitter.com/user/status/1"))
        assertEquals(LinkNormalizer.PLATFORM_BILIBILI, LinkNormalizer.platformOf("BV1xx411c7mD"))
    }

    @Test
    fun xiaohongshuKeepsAccessParameters() {
        val url = "https://www.xiaohongshu.com/explore/abc123?xsec_token=TOKEN%3D&xsec_source=pc_feed&utm_source=share"
        val normalized = LinkNormalizer.normalize(url)
        assertEquals(LinkNormalizer.PLATFORM_XHS, normalized.platform)
        assertTrue(normalized.contentUrl.contains("xsec_token=TOKEN%3D"))
        assertTrue(normalized.contentUrl.contains("xsec_source=pc_feed"))
        assertEquals("abc123", normalized.contentId)
    }

    @Test
    fun xiaohongshuLoginWrapperIsUnwrappedOnce() {
        val login = "https://www.xiaohongshu.com/login?redirectPath=" +
            "https%3A%2F%2Fwww.xiaohongshu.com%2Fexplore%2Fabc123%3Fxsec_token%3Dtoken123"
        val normalized = LinkNormalizer.normalize(login)
        assertEquals("https://www.xiaohongshu.com/explore/abc123?xsec_token=token123", normalized.contentUrl)
        assertEquals("abc123", normalized.contentId)
    }

    @Test
    fun signedTokenIsNotDoubleDecoded() {
        // token 中的 %3D 与 + 必须原样保留
        val url = "https://www.xiaohongshu.com/explore/abc?xsec_token=a%3Db+c"
        val normalized = LinkNormalizer.normalize(url)
        assertTrue(normalized.contentUrl.contains("xsec_token=a%3Db+c"))
    }

    @Test
    fun douyinKeepsCollectionContext() {
        val url = "https://www.douyin.com/video/1234567890?modal_id=9876543210&previous_page=home"
        val normalized = LinkNormalizer.normalize(url)
        assertEquals(LinkNormalizer.PLATFORM_DOUYIN, normalized.platform)
        assertTrue(normalized.contentUrl.contains("modal_id=9876543210"))
        assertFalse(normalized.contentUrl.contains("previous_page"))
    }

    @Test
    fun youtubeKeepsPlaylistParameters() {
        val url = "https://www.youtube.com/watch?v=abc123&list=PL123&index=2&si=tracker"
        val normalized = LinkNormalizer.normalize(url)
        assertEquals(LinkNormalizer.PLATFORM_YOUTUBE, normalized.platform)
        assertTrue(normalized.contentUrl.contains("v=abc123"))
        assertTrue(normalized.contentUrl.contains("list=PL123"))
        assertFalse(normalized.contentUrl.contains("si=tracker"))
        assertTrue(LinkNormalizer.isCollectionUrl(normalized.contentUrl))
    }

    @Test
    fun bilibiliKeepsPartAndSeasonParameters() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD?p=3&spm_id_from=333.999"
        val normalized = LinkNormalizer.normalize(url)
        assertTrue(normalized.contentUrl.contains("p=3"))
        assertFalse(normalized.contentUrl.contains("spm_id_from"))
        assertEquals("BV1xx411c7mD:p3", normalized.contentId)
    }

    @Test
    fun xiaohongshuCnShortLinkIsRecognized() {
        val url = "https://xhslink.cn/o/8t9O00crzU3"
        assertEquals(LinkNormalizer.PLATFORM_XHS, LinkNormalizer.platformOf(url))
        assertTrue(LinkNormalizer.isShortLink(url))
    }

    @Test
    fun shortLinksAreBoundedAndClassified() {
        assertTrue(LinkNormalizer.isShortLink("https://b23.tv/abc"))
        assertTrue(LinkNormalizer.isShortLink("https://xhslink.com/a/b"))
        assertFalse(LinkNormalizer.isShortLink("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun heyboxShareApiIsConvertedToWebSharePage() {
        val share = "https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?h_camp=link&h_session_id=x&h_src=app_share&link_id=6d637277cc84"
        val normalized = LinkNormalizer.normalize(share)
        assertEquals(LinkNormalizer.PLATFORM_HEYBOX, normalized.platform)
        assertEquals("https://www.xiaoheihe.cn/app/bbs/link/6d637277cc84", normalized.contentUrl)
        assertEquals("6d637277cc84", normalized.contentId)
    }

    @Test
    fun legacyResolutionMigratesToQualityIntent() {
        val exact = qualitySelectionFromResolution("1080p 全高清")
        assertEquals(QualityMode.MAX_HEIGHT, exact.mode)
        assertEquals(1080, exact.maxHeight)

        val best = qualitySelectionFromResolution("自适应最高画质 (推荐)")
        assertEquals(QualityMode.BEST_AVAILABLE, best.mode)
        assertEquals(0, best.maxHeight)
    }
}
