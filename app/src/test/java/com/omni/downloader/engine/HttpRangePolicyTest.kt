package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpRangePolicyTest {
    @Test fun validPartialAppendsAndUsesTheActualWholeFileSize() {
        val range = HttpRangePolicy.evaluate(206, "bytes 100-199/1000", 100, 100)
        assertTrue(range.append); assertEquals(1000L, range.total)
    }
    @Test fun fullResponseToResumeReplacesTheOldBytes() {
        assertFalse(HttpRangePolicy.evaluate(200, null, 100, 200).append)
    }
    @Test fun wrongResumeOffsetsAndMalformedLengthsAreRejected() {
        for (header in listOf("bytes 0-99/1000", "bytes 100-199/199", "bytes 100-300/1000", "invalid"))
            assertThrows(IOException::class.java) { HttpRangePolicy.evaluate(206, header, 100, 100) }
    }
    @Test fun onlyMatchingUnsatisfiedRangeCanConfirmCompletion() {
        assertTrue(HttpRangePolicy.evaluate(416, "bytes */100", 100, -1).complete)
        for (header in listOf(null, "bytes */200", "bytes */50"))
            assertThrows(IOException::class.java) { HttpRangePolicy.evaluate(416, header, 100, -1) }
    }
}
