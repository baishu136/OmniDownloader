package com.omni.downloader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookiesTest {

    private val now = 1_900_000_000_000L

    private val sample = """
        # Netscape HTTP Cookie File
        .youtube.com	TRUE	/	FALSE	4102444800	SID	abc
        .youtube.com	TRUE	/	TRUE	4102444800	HSID	secret
        .xiaohongshu.com	TRUE	/	TRUE	4102444800	xsec_token	xyz
        .example.com	FALSE	/	FALSE	1	old	gone
        #HttpOnly_.youtube.com	TRUE	/	TRUE	0	SSID	httpOnlyValue
    """.trimIndent()

    @Test
    fun filtersByPlatformDomainAndExpiry() {
        val rows = NetscapeCookies.parse(sample, setOf("youtube.com", "google.com"), now)
        assertEquals(3, rows.size)
        assertEquals(listOf("SID", "HSID", "SSID"), rows.map { it.name })
        assertFalse(rows.any { it.domain.contains("xiaohongshu") })
        assertFalse(rows.any { it.name == "old" })
    }

    @Test
    fun buildsCookieHeaderWithoutExpiredItems() {
        val rows = NetscapeCookies.parse(sample, setOf("youtube.com"), now)
        assertEquals("SID=abc; HSID=secret; SSID=httpOnlyValue", NetscapeCookies.header(rows))
    }

    @Test
    fun serializationRoundTrips() {
        val rows = NetscapeCookies.parse(sample, setOf("youtube.com"), now)
        val serialized = NetscapeCookies.serialize(rows)
        val reparsed = NetscapeCookies.parse(serialized, setOf("youtube.com"), now)
        assertEquals(rows.size, reparsed.size)
        assertEquals(NetscapeCookies.header(rows), NetscapeCookies.header(reparsed))
        assertTrue(serialized.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(serialized.contains(".youtube.com\tTRUE\t"))
        assertTrue(serialized.contains("#HttpOnly_.youtube.com\tTRUE\t"))
    }

    @Test
    fun legacyFilesFromEveryPlatformAreNormalizedWithoutLosingCookieAttributes() {
        SessionPlatforms.all.forEach { platform ->
            val domains = SessionPlatforms.domainsFor(platform)
            val host = domains.first()
            // This is the exact invalid shape produced by <= 1.7.1, with fake values.
            val legacy = "# Netscape HTTP Cookie File\n#HttpOnly_$host\tTRUE\t/account\tTRUE\t4102444800\tsession\tfixture-only\n"
            val rows = NetscapeCookies.parse(legacy, domains, now)
            val fixed = NetscapeCookies.serialize(rows)
            assertTrue(platform, fixed.contains("#HttpOnly_.$host\tTRUE\t/account\tTRUE\t4102444800\tsession\tfixture-only"))
            assertEquals(rows, NetscapeCookies.parse(fixed, domains, now))
        }
    }

    @Test
    fun hostOnlyCookiesKeepTheirScopeAndDomainCookiesGetExactlyOneDot() {
        val row = NetscapeCookies.CookieRow(".www.youtube.com", false, "/", true, 0, "host", "fixture-only")
        assertEquals("www.youtube.com\tFALSE\t/\tTRUE\t0\thost\tfixture-only", row.toNetscapeLine())
        assertTrue(row.copy(includeSubdomains = true).toNetscapeLine().startsWith(".www.youtube.com\tTRUE\t"))
    }

    @Test
    fun invalidExtraColumnsAndControlCharactersAreNotExported() {
        val base = "youtube.com\tTRUE\t/\tTRUE\t0\tsession\tfixture"
        assertTrue(NetscapeCookies.parse("$base\textra", setOf("youtube.com"), now).isEmpty())
        assertTrue(NetscapeCookies.parse("$base\u0000", setOf("youtube.com"), now).isEmpty())
    }

    @Test
    fun unrelatedCookiesYieldEmptyResult() {
        assertTrue(NetscapeCookies.parse(sample, setOf("pixiv.net"), now).isEmpty())
    }

    @Test
    fun manualHeaderPairsCanStillBeBlockedWhenNoDomainMatches() {
        // 纯 k=v 文本没有域信息，不能当作 Netscape 文件导入
        val rows = NetscapeCookies.parse("SID=abc; HSID=def", setOf("youtube.com"), now)
        assertTrue(rows.isEmpty())
    }
}
