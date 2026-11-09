package com.omni.downloader.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Exercise the Android JSON implementation rather than android.jar's JVM stub. */
class PixivJsonClientAndroidTest {
    private val url = "https://www.pixiv.net/ajax/illust/123/ugoira_meta?lang=zh"
    private fun response(request: Request, body: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun requestUsesActualHostAndPathForSavedCookieAndKeepsValidJson() {
        val scopes = mutableListOf<String>(); val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(chain.request(), "{\"error\":false,\"body\":{\"frames\":[{\"file\":\"0.jpg\",\"delay\":80}]}}")
        }.build()
        val api = PixivJsonClient(http) { scopes += it; "PHPSESSID=fixture-only" }
        val root = api.get(url).getOrThrow()
        assertEquals(listOf(url), scopes)
        assertEquals("PHPSESSID=fixture-only", requests.single().header("Cookie"))
        assertEquals("application/json", requests.single().header("Accept"))
        assertEquals("https://www.pixiv.net/", requests.single().header("Referer"))
        assertEquals(80, root.getJSONObject("body").getJSONArray("frames").getJSONObject(0).getInt("delay"))
    }

    @Test fun missingCookieAndApiErrorNeverPretendSuccessOrRepeatRequest() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++; assertNull(chain.request().header("Cookie"))
            response(chain.request(), "{\"error\":true,\"message\":\"content unavailable\"}")
        }.build()
        assertEquals(MediaErrorCode.CONTENT_UNAVAILABLE,
            MediaErrors.codeOf(PixivJsonClient(http) { null }.get(url).exceptionOrNull()!!))
        assertEquals(1, calls)
    }
}
