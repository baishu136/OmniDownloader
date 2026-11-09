package com.omni.downloader.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.engine.PixivJsonClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses isolated encrypted fixture credentials, never the user's session. */
class PixivSessionRequestTest {
    @Test fun hostOnlyPixivSessionReachesActualApiAndDisableStillRemovesIt() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "pixiv-session-qa-${UUID.randomUUID()}"
        val root = File(target.cacheDir, prefix).apply { mkdirs() }
        val scope = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val sessions = SessionRepository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(scope)
        try {
            val cookieFile = File(root, "host-only.txt").apply {
                writeText("# Netscape HTTP Cookie File\nwww.pixiv.net\tFALSE\t/\tTRUE\t4102444800\tPHPSESSID\tfixture-only\n")
            }
            sessions.importNetscapeFile(SessionPlatforms.PIXIV, Uri.fromFile(cookieFile)).getOrThrow()
            val imported = sessions.info(SessionPlatforms.PIXIV)!!
            assertNull(sessions.cookieHeader(SessionPlatforms.PIXIV)) // The old root-domain lookup lost this cookie.
            val captured = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured += chain.request().header("Cookie")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("{\"error\":false,\"body\":{}}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = PixivJsonClient(client) { url -> sessions.cookieHeader(SessionPlatforms.PIXIV, url) }
            api.get("https://www.pixiv.net/ajax/illust/123").getOrThrow()
            api.get("https://www.pixiv.net/ajax/illust/123/ugoira_meta").getOrThrow()
            assertEquals(listOf("PHPSESSID=fixture-only", "PHPSESSID=fixture-only"), captured)
            assertNull(sessions.cookieHeader(SessionPlatforms.PIXIV, "https://accounts.pixiv.net/"))
            assertNull(sessions.cookieHeader(SessionPlatforms.PIXIV, "https://i.pximg.net/"))
            sessions.setCookieEnabled(SessionPlatforms.PIXIV, false)
            api.get("https://www.pixiv.net/ajax/illust/123").getOrThrow()
            assertNull(captured.last())
            assertEquals(imported.cookieCount, sessions.info(SessionPlatforms.PIXIV)!!.cookieCount)
            assertEquals(imported.importedAt, sessions.info(SessionPlatforms.PIXIV)!!.importedAt)
        } finally {
            scope.getSharedPreferences("omni_sessions_v1", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
