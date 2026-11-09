package com.omni.downloader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieHeaderParserTest {

    @Test
    fun parsesNameValuePairsAndKeepsEqualsInsideValue() {
        val pairs = CookieHeaderParser.parse("SID=abc; token=a=b=c; empty=; bad; =x; name=value")
        assertEquals(
            listOf(
                "SID" to "abc",
                "token" to "a=b=c",
                "name" to "value"
            ),
            pairs
        )
    }

    @Test
    fun blankHeaderYieldsEmptyList() {
        assertTrue(CookieHeaderParser.parse("").isEmpty())
        assertTrue(CookieHeaderParser.parse("   ;  ; ").isEmpty())
    }
}
