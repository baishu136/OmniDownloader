package com.omni.downloader.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PixivJsonClientTest {
    private val url = "https://www.pixiv.net/ajax/illust/123/ugoira_meta?lang=zh"
    private fun response(request: Request, status: Int, body: String = "{\"error\":false,\"body\":{}}") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun onlyKnownRestricted404MeansLoginAndAccessErrorsNeverRetryAnonymously() {
        val cases = listOf(Triple(404, true, MediaErrorCode.AUTH_REQUIRED), Triple(404, false, MediaErrorCode.CONTENT_UNAVAILABLE),
            Triple(410, true, MediaErrorCode.CONTENT_UNAVAILABLE), Triple(401, false, MediaErrorCode.AUTH_REQUIRED),
            Triple(403, true, MediaErrorCode.AUTH_REQUIRED), Triple(302, false, MediaErrorCode.AUTH_REQUIRED),
            Triple(429, true, MediaErrorCode.RATE_LIMITED))
        for ((status, restricted, expected) in cases) {
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain -> calls++; response(chain.request(), status) }.build()
            val error = PixivJsonClient(http) { "PHPSESSID=fixture-only" }.get(url, restricted).exceptionOrNull()!!
            assertEquals("HTTP $status / restricted=$restricted", expected, MediaErrors.codeOf(error))
            assertEquals(1, calls)
            if (status == 404 && restricted) assertFalse(error.message.orEmpty().contains("已删除"))
        }
    }

    @Test fun networkRetryIsBoundedAndPreservesRestrictedContextAndCookie() {
        val cookies = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            cookies += chain.request().header("Cookie")
            response(chain.request(), if (cookies.size == 1) 503 else 404)
        }.build()
        val error = PixivJsonClient(http) { "PHPSESSID=fixture-only" }.get(url, true).exceptionOrNull()!!
        assertEquals(MediaErrorCode.AUTH_REQUIRED, MediaErrors.codeOf(error))
        assertEquals(listOf("PHPSESSID=fixture-only", "PHPSESSID=fixture-only"), cookies)
    }

}
