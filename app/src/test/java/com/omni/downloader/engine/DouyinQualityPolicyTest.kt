package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DouyinQualityPolicyTest {
    private val url = "https://www.douyin.com/video/7699370307513319141"
    private fun format(id: String, w: Int = 720, h: Int = 1280, codec: String = "avc1", bitrate: Double = 0.0) =
        FormatOption(id, "$w", w, h, vcodec = codec, videoBitrateKbps = bitrate, sourceUrl = "https://cdn.test/$id.mp4")
    private fun metadata(vararg formats: FormatOption) = VideoMetadata(url, "fixture", siteName = "抖音", mediaId = "7699370307513319141", availableVideoFormats = formats.toList())

    @Test fun lowAndUnknownSourcesTriggerBoundedEnhancementButVerifiedHighDoesNot() {
        assertTrue(DouyinQualityPolicy.needsPage(null))
        assertTrue(DouyinQualityPolicy.needsPage(metadata(format("low"))))
        assertFalse(DouyinQualityPolicy.needsPage(metadata(format("high", 1080, 1920))))
        assertTrue(DouyinQualityPolicy.needsPage(metadata(format("high", 1080, 1920)).copy(referenceWidth = 2160, referenceHeight = 3840)))
        assertFalse(DouyinQualityPolicy.needsPage(metadata(format("native720")).copy(referenceWidth = 720, referenceHeight = 1280)))
        assertTrue(DouyinQualityPolicy.needsPage(metadata(format("unknown", 0, 0)).copy(referenceWidth = 720, referenceHeight = 1280)))
        assertFalse(DouyinQualityPolicy.needsPage(metadata(format("complete", 1080, 1920), format("backup", 0, 0))
            .copy(referenceWidth = 1080, referenceHeight = 1920)))
    }
    @Test fun mergeKeepsAllSourcesAndNeverPromotesTheUploadReference() {
        val low = metadata(format("low")).copy(referenceWidth = 2160, referenceHeight = 3840)
        val merged = DouyinQualityPolicy.merge(low, metadata(format("high", 1080, 1920)))
        assertEquals(1080, merged.availableVideoFormats.first().width)
        assertEquals(2, merged.availableVideoFormats.size)
        assertEquals(2160, merged.referenceWidth)
        assertEquals(720, merged.availableVideoFormats.last().width)
    }
    @Test fun advertisedPageUpdatesDoNotEraseMeasuredDimensionsForTheSameUrl() {
        val measured = metadata(format("same").copy(dimensionsVerified = true))
        val advertised = metadata(format("same", 1080, 1920))
        val merged = DouyinQualityPolicy.merge(measured, advertised)
        assertEquals(720, merged.availableVideoFormats.single().width)
        assertTrue(merged.availableVideoFormats.single().dimensionsVerified)
    }
    @Test fun portraitAndLandscapeReferencesAreNotCombinedIntoASquare() {
        val result = DouyinQualityPolicy.merge(metadata(format("a")).copy(referenceWidth = 1080, referenceHeight = 1920),
            metadata(format("b")).copy(referenceWidth = 1920, referenceHeight = 1080))
        assertEquals(1080L * 1920, result.referenceWidth.toLong() * result.referenceHeight)
    }
    @Test fun rejectsDifferentWorks() {
        assertTrue(runCatching { DouyinQualityPolicy.merge(metadata(format("a")), metadata(format("b")).copy(mediaId = "other")) }.isFailure)
    }
    @Test fun bitrateComparisonStaysWithinCodecFamily() {
        val hevc = format("hevc", codec = "hvc1", bitrate = 800.0)
        val low = format("avc-low", bitrate = 1000.0)
        val high = format("avc-high", codec = "h264", bitrate = 2000.0)
        assertEquals(listOf("hevc", "avc-high", "avc-low"), QualitySelector.sorted(listOf(hevc, low, high)).map { it.formatId })
    }
    @Test fun realHighFrameRateAndHdrAreKept() {
        val normal = format("sdr").copy(fps = 60, dynamicRange = "SDR")
        val hdr = format("hdr").copy(fps = 60, dynamicRange = "HLG")
        assertEquals("hdr", QualitySelector.sorted(listOf(normal, hdr, hdr.copy(formatId = "slow", fps = 30))).first().formatId)
    }
    @Test fun measuredSourceWinsAnOtherwiseEqualAdvertisedBackup() {
        val unverified = format("old")
        assertEquals("live", QualitySelector.sorted(listOf(unverified, unverified.copy(formatId = "live", dimensionsVerified = true))).first().formatId)
    }
    @Test fun webViewProxyRulesUseTheSupportedSocksSchemeAndKeepIpv6() {
        assertEquals("socks://[::1]:1080", ProxyConfiguration.parse("socks5h://[::1]:1080")!!.webViewUrl)
        assertEquals("https://proxy.test:443", ProxyConfiguration.parse("https://proxy.test")!!.webViewUrl)
    }
}
