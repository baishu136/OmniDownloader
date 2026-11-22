package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadType
import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    @Test fun normalFragmentsUseFourSlotsAndCompatibilityUsesOneWithoutChangingQuality() {
        val plan = YtDlpOptionsFactory.DownloadFormatPlan(DownloadType.VIDEO_WITH_AUDIO, selectedFormatId = "exact-video", maxHeight = 1080)
        val normal = YtDlpOptionsFactory.downloadRequest("https://example.org/a", "YouTube", "", plan, "a.%(ext)s")
        val safe = YtDlpOptionsFactory.downloadRequest("https://example.org/a", "YouTube", "", plan, "a.%(ext)s", safeTransport = true)
        assertEquals("4", normal.getOption("--concurrent-fragments")); assertEquals("1", safe.getOption("--concurrent-fragments"))
        assertEquals(normal.getOption("-f"), safe.getOption("-f"))
        assertTrue(normal.hasOption("--abort-on-unavailable-fragments"))
    }
    @Test fun byteIntervalsAreContiguousCoverTheEntityAndNeverExceedFour() {
        for (size in listOf(1L, 2L, 7L, 1024L, 8L*1024*1024+1, 10_000_000_000L)) {
            val parts = TransferPolicy.parts(size)
            assertEquals(0, parts.first().start); assertEquals(size-1, parts.last().end)
            assertEquals(size, parts.sumOf { it.length }); assertTrue(parts.size <= 4)
            parts.zipWithNext().forEach { (a,b) -> assertEquals(a.end+1,b.start) }
        }
    }
    @Test fun onlyStrongEntityTagsEnableParallel() {
        assertEquals("\"version\"", TransferPolicy.strongTag("\"version\""))
        for (value in listOf(null, "", "W/\"version\"", "last-modified")) assertNull(TransferPolicy.strongTag(value))
    }
    @Test fun slowDetectorRequiresWarmupAWholeWindowAndSubstantialRemainingData() {
        val watch = SlowTransferWatch(0)
        val total = 50L*1024*1024
        assertFalse(watch.sample(9999, 100, total)); assertFalse(watch.sample(10000, 100, total))
        assertFalse(watch.sample(29999, 200, total)); assertTrue(watch.sample(30000, 200,total))
        assertFalse(watch.sample(50000, total-1000,total))
    }
    @Test fun healthyTransferAndShortPausesDoNotTriggerRecovery() {
        val watch = SlowTransferWatch(0)
        assertFalse(watch.sample(10000, 0, 50L*1024*1024))
        assertFalse(watch.sample(30000, 2L*1024*1024, 50L*1024*1024))
        assertFalse(watch.sample(31000, 2L*1024*1024, 50L*1024*1024))
    }
    @Test fun refreshCannotContinueOnDenialMismatchOrQualityLoss() {
        for (code in listOf("AUTH_REQUIRED","CHALLENGE_REQUIRED","ACCESS_DENIED","MEDIA_ID_MISMATCH","CONTENT_UNAVAILABLE","QUALITY_UNAVAILABLE","RATE_LIMITED"))
            assertFalse(code, TransferPolicy.canContinueOriginal(Exception("[$code] stop")))
        assertTrue(TransferPolicy.canContinueOriginal(Exception("[NETWORK_ERROR] interrupted")))
    }
    @Test fun resumedPartialAndAlreadyCompleteResponsesMustKeepTheSameEntity() {
        for (code in listOf(206,416)) {
            TransferPolicy.validateResume(100,"\"a\"","\"a\"",code)
            assertTrue(runCatching { TransferPolicy.validateResume(100,"\"a\"","\"b\"",code) }.isFailure)
            assertTrue(runCatching { TransferPolicy.validateResume(100,"\"a\"",null,code) }.isFailure)
        }
        TransferPolicy.validateResume(100,"\"a\"","\"b\"",200)
    }
}
