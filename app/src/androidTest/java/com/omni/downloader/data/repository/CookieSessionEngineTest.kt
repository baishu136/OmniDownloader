package com.omni.downloader.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.engine.YtDlpOptionsFactory
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic sessions only; never read or replace real account profiles. */
class CookieSessionEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolatedContext(root: File): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("cookie-format-qa-$name", mode)
    }

    private fun repository(scope: Context): SessionRepository =
        SessionRepository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(scope)

    private fun seedLegacy(scope: Context, platform: String, text: String) {
        val payload = JSONObject().put("netscape", text).put("header", "session=fixture-only")
            .put("importedAt", 12345L).put("status", "IMPORTED").put("generation", 7)
        assertTrue(scope.getSharedPreferences("omni_sessions_v1", Context.MODE_PRIVATE).edit()
            .putString("session_${platform.hashCode()}", SessionCrypto.encrypt(payload.toString())).commit())
    }

    private fun cleanup(scope: Context, root: File) {
        scope.getSharedPreferences("omni_sessions_v1", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun encryptedLegacyProfilesForAllPlatformsLoadInBundledPython() = runBlocking {
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val root = File(context.cacheDir, "cookie-legacy-qa").apply { mkdirs() }
        val scope = isolatedContext(root)
        val files = mutableListOf<File>()
        try {
            SessionPlatforms.all.forEach { platform ->
                val host = SessionPlatforms.domainsFor(platform).first()
                seedLegacy(scope, platform, "# Netscape HTTP Cookie File\n" +
                    "#HttpOnly_$host\tTRUE\t/account\tTRUE\t4102444800\tsession\tfixture-only\n" +
                    "www.$host\tFALSE\t/\tTRUE\t4102444800\thost_only\tfixture-host\n")
            }
            val sessions = repository(scope)
            SessionPlatforms.all.forEach { platform ->
                val file = sessions.tempCookieFile(platform)!!
                files += file
                val host = SessionPlatforms.domainsFor(platform).first()
                assertTrue(file.readText().contains("#HttpOnly_.$host\tTRUE\t"))
                assertEquals(7, sessions.generation(platform))
                assertEquals(12345L, sessions.info(platform)!!.importedAt)
                assertEquals(SessionStatus.IMPORTED, sessions.info(platform)!!.status)
            }
            val base = File(context.noBackupFilesDir, "youtubedl-android")
            val home = File(base, "packages/python/usr")
            val script = """
                import http.cookiejar, sys, urllib.request
                import omni_media
                from yt_dlp.cookies import YoutubeDLCookieJar
                for path in sys.argv[1:]:
                    for cls in (http.cookiejar.MozillaCookieJar, YoutubeDLCookieJar):
                        jar = cls(path)
                        jar.load(ignore_discard=True, ignore_expires=True)
                        cookies = list(jar)
                        assert len(cookies) == 2
                        domain = next(c for c in cookies if c.name == 'session')
                        host = next(c for c in cookies if c.name == 'host_only')
                        assert domain.domain.startswith('.') and domain.domain_specified and domain.secure
                        assert domain.path == '/account'
                        # yt-dlp deliberately strips HttpOnly, which has no HTTP-client meaning.
                        if cls is http.cookiejar.MozillaCookieJar:
                            assert domain.has_nonstandard_attr('HTTPOnly')
                        assert not host.domain.startswith('.') and not host.domain_specified
                        request = urllib.request.Request('https://sub' + domain.domain + '/account/video')
                        jar.add_cookie_header(request)
                        assert 'session=fixture-only' in request.get_header('Cookie', '')
                        outside = urllib.request.Request('https://other.invalid/account/video')
                        jar.add_cookie_header(outside)
                        assert outside.get_header('Cookie') is None
                    client = omni_media.session(path)
                    assert len(client.cookies) == 2
                    client.close()
                print('COOKIE_JARS_OK', len(sys.argv)-1)
            """.trimIndent()
            val process = ProcessBuilder(listOf("${context.applicationInfo.nativeLibraryDir}/libpython.so", "-c", script) + files.map { it.path }).apply {
                environment().apply {
                    put("PYTHONHOME", home.path)
                    put("PYTHONPATH", File(base, "yt-dlp/yt-dlp").path)
                    put("LD_LIBRARY_PATH", "${home.path}/lib:${base.path}/packages/ffmpeg/usr/lib")
                    put("TMPDIR", root.path)
                }
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            assertTrue(output.contains("COOKIE_JARS_OK ${SessionPlatforms.all.size}"))
        } finally { files.forEach { it.delete() }; cleanup(scope, root) }
    }

    @Test fun browserHeaderSessionSupportsActualYtDlpMetadataAndDownload() = runBlocking {
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val root = File(context.cacheDir, "cookie-engine-qa").apply { mkdirs() }
        val scope = isolatedContext(root)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("verification.mp4").use { it.readBytes() }
        val server = ServerSocket(0).apply { soTimeout = 500 }
        val closed = AtomicBoolean(false)
        val worker = Thread {
            while (!closed.get()) try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    val head = input.readLine().startsWith("HEAD ")
                    while (!input.readLine().isNullOrBlank()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (!head) write(bytes)
                        flush()
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: Exception) { if (!closed.get()) throw IllegalStateException("Fixture server failed") }
        }.apply { isDaemon = true; start() }
        var cookieFile: File? = null
        try {
            val sessions = repository(scope)
            sessions.importCookieHeader(SessionPlatforms.YOUTUBE, "SID=fixture-only; PREF=fake").getOrThrow()
            cookieFile = sessions.tempCookieFile(SessionPlatforms.YOUTUBE)!!
            val url = "http://127.0.0.1:${server.localPort}/fixture.mp4"
            val metadata = YoutubeDL.getInstance().execute(YtDlpOptionsFactory.metadataRequest(url, SessionPlatforms.YOUTUBE, "", false, cookieFile))
            assertEquals("fixture", JSONObject(metadata.out.trim()).getString("id"))
            val destination = File(root, "download.mp4")
            YoutubeDL.getInstance().execute(YtDlpOptionsFactory.downloadRequest(url, SessionPlatforms.YOUTUBE, "",
                YtDlpOptionsFactory.DownloadFormatPlan(DownloadType.VIDEO_WITH_AUDIO), destination.path, cookieFile = cookieFile))
            assertArrayEquals(bytes, destination.readBytes())
        } finally { cookieFile?.delete(); closed.set(true); server.close(); worker.join(3000); cleanup(scope, root) }
    }

    @Test fun expiredStoredSessionDoesNotSilentlyFallBackToAnonymous() {
        val root = File(context.cacheDir, "cookie-expired-qa").apply { mkdirs() }
        val scope = isolatedContext(root)
        try {
            seedLegacy(scope, SessionPlatforms.X, "# Netscape HTTP Cookie File\nx.com\tTRUE\t/\tTRUE\t1\tsession\tfixture-only\n")
            val error = runCatching { repository(scope).tempCookieFile(SessionPlatforms.X) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("[AUTH_REQUIRED]"))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { cleanup(scope, root) }
    }
}
