package com.omni.downloader.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated encrypted fixture profiles. No real user profile/cookies are read or replaced. */
class SessionCookiePolicyTest {
    @Test fun galleryCookieSnapshotKeepsHostPathSecureAndDisableRules():Unit=runBlocking {
        GoogleAccountsTest.Fixture().use{fixture->
            val file=File(fixture.root,"cookie-scope.txt").apply{writeText("# Netscape HTTP Cookie File\n.pixiv.net\tTRUE\t/\tTRUE\t4102444800\troot\tfixture\nwww.pixiv.net\tFALSE\t/artworks/\tTRUE\t4102444800\tpage\tfixture\n")}
            fixture.sessions.importNetscapeFile(SessionPlatforms.PIXIV,android.net.Uri.fromFile(file)).getOrThrow()
            val provider=fixture.sessions.imageCookieProvider(SessionPlatforms.PIXIV)
            for(url in listOf("https://www.pixiv.net/artworks/42","https://accounts.pixiv.net/","https://i.pximg.net/a.jpg","http://www.pixiv.net/artworks/42","https://pixiv.net.evil.test/"))
                assertEquals(url,fixture.sessions.cookieHeader(SessionPlatforms.PIXIV,url),provider(url))
            fixture.sessions.setCookieEnabled(SessionPlatforms.PIXIV,false)
            assertNull(fixture.sessions.imageCookieProvider(SessionPlatforms.PIXIV)("https://www.pixiv.net/artworks/42"))
        }
    }
    @Test fun disablingEachPlatformPreservesCredentialsAndInvalidatesRequestGeneration() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "cookie-policy-qa-${UUID.randomUUID()}"
        val root = File(target.cacheDir, prefix).apply { mkdirs() }
        val scope = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        fun repository() = SessionRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(scope)
        val sessions = repository()
        try {
            SessionPlatforms.all.forEach { platform ->
                assertTrue(sessions.isCookieEnabled(platform))
                sessions.importCookieHeader(platform, "session=fixture-only").getOrThrow()
                val imported = sessions.info(platform)!!
                val original = sessions.requestGeneration(platform)
                assertNotNull(sessions.cookieHeader(platform))
                val file = sessions.tempCookieFile(platform)!!
                assertTrue(file.delete())
                sessions.setCookieEnabled(platform, false)
                assertFalse(sessions.info(platform)!!.cookieEnabled)
                assertEquals(imported.importedAt, sessions.info(platform)!!.importedAt)
                assertEquals(imported.cookieCount, sessions.info(platform)!!.cookieCount)
                assertNull(sessions.cookieHeader(platform))
                assertNull(sessions.tempCookieFile(platform))
                assertFalse(repository().isCookieEnabled(platform))
                var restored = false
                sessions.restoreBrowserCookies(platform) { restored = true }
                assertTrue(restored)
                assertNotEquals(original, sessions.requestGeneration(platform))
                sessions.importCookieHeader(platform, "session=updated-fixture").getOrThrow()
                assertFalse(sessions.isCookieEnabled(platform))
                sessions.setCookieEnabled(platform, true)
                assertNotEquals(original, sessions.requestGeneration(platform))
                assertEquals("session=updated-fixture", sessions.cookieHeader(platform))
                assertTrue(root.listFiles().orEmpty().all { !it.isFile })
            }
            val stored = scope.getSharedPreferences("omni_sessions_v1", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains("updated-fixture"))
        } finally {
            scope.getSharedPreferences("omni_sessions_v1", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
