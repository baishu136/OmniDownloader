package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class CookieRequestPolicyTest {
    @Test fun capturedCdnKeepsOriginPlatformAndFiltersCaseInsensitiveCookies() {
        assertEquals(LinkNormalizer.PLATFORM_X, CookieRequestPolicy.platformFor("https://x.com/a/status/1", "https://unrelated.invalid/file.mp4"))
        assertEquals(LinkNormalizer.PLATFORM_X, CookieRequestPolicy.platformFor("https://video.twimg.com/fixture.mp4", "https://video.twimg.com/fixture.mp4"))
        val headers = mapOf("cOoKiE" to "fixture-secret", "Cookie2" to "fixture-secret", "Referer" to "https://x.com/")
        assertEquals(mapOf("Referer" to "https://x.com/"), CookieRequestPolicy.filter(headers, false))
        assertEquals(headers, CookieRequestPolicy.filter(headers, true))
    }
}
