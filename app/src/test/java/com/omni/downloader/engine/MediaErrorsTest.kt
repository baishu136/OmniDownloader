package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaErrorsTest {

    @Test
    fun cookieLoadFailureIsNotAnUnavailablePythonRuntime() {
        val traceback = "/packages/python/usr/lib/python3.12/http/cookiejar.py:2078: UserWarning: http.cookiejar bug!\n" +
            "AssertionError\nERROR: invalid Netscape format cookies file 'session.txt': " +
            "'youtube.com\\tTRUE\\t/\\tTRUE\\t4102444800\\t__Secure-1PSIDTS\\tFIXTURE_SECRET'\n" +
            "yt_dlp.cookies.CookieLoadError: failed to load cookies"
        assertEquals(MediaErrorCode.COOKIE_INVALID, MediaErrors.classify(traceback))
        assertFalse(MediaErrors.describe(IllegalStateException(traceback)).contains("FIXTURE_SECRET"))
        assertEquals(MediaErrorCode.NETWORK_ERROR, MediaErrors.classify("File /packages/python/http.py\nConnection reset"))
        assertEquals(MediaErrorCode.ENGINE_UNAVAILABLE, MediaErrors.classify("ModuleNotFoundError: No module named yt_dlp"))
    }

    @Test
    fun netscapeValuesAreRedactedWithBothActualAndEscapedTabs() {
        listOf("\t", "\\t").forEach { separator ->
            listOf("youtube.com", "#HttpOnly_.x.com").forEach { domain ->
                val row = listOf(domain, "TRUE", "/", "TRUE", "4102444800", "arbitrary_cookie", "FIXTURE_SECRET").joinToString(separator)
                val input = "invalid Netscape format cookies file 'fixture.txt': '$row'\nnext diagnostic"
                val safe = LogSanitizer.sanitize(input)
                assertFalse(safe.contains("FIXTURE_SECRET"))
                assertTrue(safe.contains("arbitrary_cookie"))
                assertTrue(safe.contains("next diagnostic"))
                assertEquals(safe, LogSanitizer.sanitize(safe))
            }
        }
    }

    @Test
    fun classifiesCommonFailures() {
        assertEquals(
            MediaErrorCode.CHALLENGE_REQUIRED,
            MediaErrors.classify("Sign in to confirm you're not a bot")
        )
        assertEquals(MediaErrorCode.AUTH_REQUIRED, MediaErrors.classify("请先登录后查看"))
        assertEquals(MediaErrorCode.RATE_LIMITED, MediaErrors.classify("HTTP Error 429: Too Many Requests"))
        assertEquals(
            MediaErrorCode.QUALITY_UNAVAILABLE,
            MediaErrors.classify("Requested format is not available")
        )
        assertEquals(MediaErrorCode.NETWORK_ERROR, MediaErrors.classify("Failed to connect to example.com"))
        assertEquals(
            MediaErrorCode.CONTENT_UNAVAILABLE,
            MediaErrors.classify("Video unavailable: removed by uploader")
        )
    }

    @Test
    fun httpStatusTakesPriority() {
        assertEquals(MediaErrorCode.AUTH_REQUIRED, MediaErrors.classify("anything", 401))
        assertEquals(MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrors.classify("anything", 404))
        assertEquals(MediaErrorCode.RATE_LIMITED, MediaErrors.classify("anything", 429))
    }

    @Test
    fun describeIncludesStableCodeAndRedactsSecrets() {
        val message = MediaErrors.describe(
            MediaErrorCode.NETWORK_ERROR,
            "proxy http://user:pass@127.0.0.1:7890?token=SECRET"
        )
        assertTrue(message.startsWith("[NETWORK_ERROR]"))
        assertFalse(message.contains("user:pass"))
        assertFalse(message.contains("SECRET"))
    }

    @Test
    fun sanitizerRemovesCookiesAndSignedParameters() {
        val input = "Cookie: SESSDATA=abc; bili_jct=def\n" +
            "https://cdn.example.com/v.mp4?token=xyz&expires=123&name=ok"
        val output = LogSanitizer.sanitize(input)
        assertFalse(output.contains("SESSDATA=abc"))
        assertFalse(output.contains("token=xyz"))
        assertFalse(output.contains("expires=123"))
        assertTrue(output.contains("name=ok"))
    }
}
