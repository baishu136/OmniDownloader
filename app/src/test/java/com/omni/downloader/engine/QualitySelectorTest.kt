package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import org.junit.Assert.*
import org.junit.Test

class QualitySelectorTest {
    private fun fmt(id: String, w: Int, h: Int, fps: Int = 30, codec: String = "av01") =
        FormatOption(id, QualitySelector.label(w, h), w, h, fps, vcodec = codec)
    @Test fun rationalFrameRateAndUnknownRangeAreNotGuessed() {
        assertEquals(59.94005994, QualitySelector.frameRate("60000/1001"), 0.001)
        assertEquals(0.0, QualitySelector.frameRate("30/0"), 0.0)
        assertThrows(IllegalStateException::class.java) { QualitySelector.select(listOf(fmt("source", 3840, 2160)), QualitySelection(dynamicRangePreference = "HDR")) }
    }
    @Test fun preservesEightKAndPrioritizesPixelsOverCodecAndFps() {
        val formats = listOf(fmt("hd", 1920, 1080, 60, "avc1"), fmt("4k", 3840, 2160), fmt("8k", 7680, 4320))
        assertEquals("8k", QualitySelector.select(formats, QualitySelection()).formatId)
        assertEquals(3, QualitySelector.sorted(formats).size)
        assertTrue(formats.last().resolutionLabel.startsWith("8K"))
    }
    @Test fun maxIsStrictAndPortraitUsesShortDimension() {
        val formats = listOf(fmt("8k", 7680, 4320), fmt("portrait", 2160, 3840), fmt("hd", 1920, 1080))
        assertEquals("portrait", QualitySelector.select(formats, QualitySelection(QualityMode.MAX_HEIGHT, 2160)).formatId)
    }
    @Test fun unavailableExactDoesNotSilentlyFallback() {
        assertThrows(IllegalStateException::class.java) { QualitySelector.select(listOf(fmt("hd", 1920, 1080)), QualitySelection(QualityMode.EXACT_FORMAT), "8k") }
    }
    @Test fun legacyEightKDoesNotBecomeFourK() {
        assertEquals(4320, parseResolutionHeight("8K · 7680×4320"))
        assertEquals(2880, parseResolutionHeight("5K"))
    }
    @Test fun targetTierRequiresRequestedTier() {
        assertThrows(IllegalStateException::class.java) { QualitySelector.select(listOf(fmt("hd", 1920, 1080)), QualitySelection(QualityMode.TARGET_TIER, 2160)) }
    }
}
