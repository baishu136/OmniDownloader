package com.omni.downloader.ui.components

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.engine.*
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real WebView, parser, sparse probe, download and MediaStore; no browser clicks. */
class DouyinAutomaticCaptureTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val id = "7699370307513319141"
    private val url = "https://www.douyin.com/video/$id"

    private class MediaServer(private val low: ByteArray, private val high: ByteArray) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Thread {
            while (running.get()) try {
                socket.accept().use { peer ->
                    peer.soTimeout = 5000
                    val reader = peer.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; headers += line }
                    val body = if (request.contains("/low.mp4")) low else high
                    val range = headers.firstOrNull { it.startsWith("Range:", true) }
                        ?.substringAfter("bytes=")?.split('-', limit = 2)
                    val from = range?.firstOrNull()?.toIntOrNull() ?: 0
                    val end = minOf(range?.getOrNull(1)?.toIntOrNull() ?: body.lastIndex, body.lastIndex)
                    val out = peer.getOutputStream()
                    val head = "HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\n" +
                        (if (range == null) "" else "Content-Range: bytes $from-$end/${body.size}\r\n") +
                        "Content-Length: ${end - from + 1}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray()); out.write(body, from, end - from + 1); out.flush()
                }
            } catch (_: Exception) { if (!running.get()) break }
        }.apply { isDaemon = true; start() }
        override fun close() { running.set(false); socket.close(); worker.join(2000) }
    }

    private fun item(base: String, high: Boolean, target: String = id): String = """{"aweme_id":"$target","desc":"fixture","video":{"width":1080,"height":1920,"duration":1000,"play_addr":{"width":${if (high) 1080 else 720},"height":${if (high) 1920 else 1280},"url_list":["$base/${if (high) "high" else "low"}.mp4"]}}}"""

    private fun withHost(body: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try { var host: MainActivity? = null; scenario.onActivity { host = it }; body(host!!) }
        finally { scenario.close() }
    }

    private fun adapter(activity: MainActivity, html: String, detail: String = "{}", budget: Long = 10_000,
        calls: AtomicInteger = AtomicInteger()) = AndroidDouyinPageCapture(activity, budget) { request ->
        val isPage = request.url.path.orEmpty().startsWith("/video/")
        if (request.url.path.orEmpty().startsWith("/aweme/v1/")) calls.incrementAndGet()
        WebResourceResponse(if (isPage) "text/html" else "application/json", "UTF-8",
            (if (isPage) html else detail).byteInputStream())
    }

    @Test fun automaticallyWaitsForLate1080AndExportsUnchangedMediaBytes() = withHost { activity -> runBlocking {
        val low = instrument.context.assets.open("quality-720.mp4").use { it.readBytes() }
        val high = instrument.context.assets.open("quality-1080.mp4").use { it.readBytes() }
        MediaServer(low, high).use { server ->
            val html = """<html><body><h1>fixture</h1><script>window._ROUTER_DATA={item:${item(server.base, false)}};
                setTimeout(()=>fetch('/aweme/v1/web/aweme/detail/?aweme_id=$id'),2500);</script></body></html>"""
            val calls = AtomicInteger()
            val page = adapter(activity, html, """{"aweme_detail":${item(server.base, true)}}""", calls = calls)
            val previous = DouyinPageCapture.loader
            val settings = SettingsRepository.getInstance(activity)
            val oldPath = settings.downloadPath.value
            val oldProxy = NetworkProxy.configuredUrl
            val taskId = "auto-qa-${UUID.randomUUID()}"
            var saved: Uri? = null
            val copy = File(activity.cacheDir, "$taskId.mp4")
            try {
                NetworkProxy.configuredUrl = ""
                DouyinAutoResolver.invalidate(url)
                DouyinPageCapture.attach(page)
                val metadata = DouyinAutoResolver.resolve(activity, url, "", forceRefresh = true) {
                    Result.success(DouyinVideoParser.parseItem(item(server.base, false), url))
                }.getOrThrow()
                assertEquals(1080, metadata.availableVideoFormats.first().width)
                assertTrue(metadata.availableVideoFormats.first().dimensionsVerified)
                assertTrue(metadata.availableVideoFormats.any { it.width == 720 && it.dimensionsVerified })
                assertEquals(1, calls.get()) // Inspecting cached results must not repeat unsigned detail requests.
                val selected = metadata.availableVideoFormats.first()
                val task = DownloadTask(taskId, url, "Automatic source QA", downloadType = DownloadType.VIDEO_WITH_AUDIO,
                    mediaId = id, directMediaUrl = selected.sourceUrl, selectedWidth = selected.width, selectedHeight = selected.height,
                    expectedDurationMs = 1000, directMediaHeaders = selected.sourceHeaders)
                settings.setDownloadPath("")
                saved = Uri.parse(DownloadEngine.executeDownload(activity, task) { _, _, _, _ -> }.getOrThrow())
                activity.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                assertArrayEquals(high, copy.readBytes())
                assertNull(MediaVerifier.verifyTask(copy, task, activity))
                println("OMNI_DOUYIN_AUTO late720To1080=PASS sourceDimensionsVerified=PASS unchangedBytes=PASS manualBrowserActions=0 detailRequests=${calls.get()} sha256=" +
                    MessageDigest.getInstance("SHA-256").digest(high).joinToString("") { "%02x".format(it) })
                assertEquals(0, page.activeViews)
            } finally {
                instrument.runOnMainSync { page.close() }
                DouyinPageCapture.detach(page); previous?.let(DouyinPageCapture::attach)
                saved?.let { activity.contentResolver.delete(it, null, null) }
                copy.delete(); File(activity.filesDir, "media_tasks/$taskId").deleteRecursively()
                settings.setDownloadPath(oldPath); NetworkProxy.configuredUrl = oldProxy
                DouyinAutoResolver.invalidate(url)
            }
        }
    } }

    @Test fun plainScriptPageDoesNotRequireOpeningABrowser() = withHost { activity -> runBlocking {
        val page = adapter(activity, """<script>window._ROUTER_DATA={item:${item("https://cdn.test", true)}};</script>""")
        try {
            val result = page.capture(url, "", DouyinAutoResolver.scope(activity, ""))
            assertEquals(DouyinPageStatus.FOUND, result.status)
            assertEquals(id, JSONObject(result.item!!).getString("aweme_id"))
            assertEquals(0, page.activeViews)
        } finally { instrument.runOnMainSync { page.close() } }
    } }

    @Test fun visibleChallengeReturnsVerificationInsteadOfAPlaceholderVideo() = withHost { activity -> runBlocking {
        val page = adapter(activity, """<div id="captcha" style="width:300px;height:200px">请完成下列验证后继续</div>
            <video style="width:300px;height:500px" src="https://cdn.test/placeholder.mp4"></video>""")
        try {
            assertEquals(DouyinPageStatus.CHALLENGE_REQUIRED, page.capture(url, "", DouyinAutoResolver.scope(activity, "")).status)
            assertEquals(0, page.activeViews)
        } finally { instrument.runOnMainSync { page.close() } }
    } }

    @Test fun cookieDisabledCreatesNoAutomaticWebView() = withHost { activity -> runBlocking {
        val session = SessionRepository.getInstance(activity)
        val old = session.isCookieEnabled("抖音")
        val calls = AtomicInteger()
        val page = adapter(activity, "<p>must not load</p>", calls = calls)
        try {
            session.setCookieEnabled("抖音", false)
            assertEquals(DouyinPageStatus.UNAVAILABLE, page.capture(url, "", DouyinAutoResolver.scope(activity, "")).status)
            assertEquals(0, page.activeViews); assertEquals(0, calls.get())
        } finally { session.setCookieEnabled("抖音", old); instrument.runOnMainSync { page.close() } }
    } }

    @Test fun timeoutAndCancellationDisposeThePage() = withHost { activity -> runBlocking {
        val page = adapter(activity, "<p>pending</p>", budget = 1200)
        try {
            assertEquals(DouyinPageStatus.TIMEOUT, page.capture(url, "", DouyinAutoResolver.scope(activity, "")).status)
            assertEquals(0, page.activeViews)
            val job = async { page.capture(url, "", DouyinAutoResolver.scope(activity, "")) }
            withTimeout(3000) { while (page.activeViews == 0) delay(50) }
            job.cancelAndJoin()
            assertEquals(0, page.activeViews)
        } finally { instrument.runOnMainSync { page.close() } }
    } }

    @Test fun accountPolicyChangeInvalidatesAnInFlightCapture() = withHost { activity -> runBlocking {
        val session = SessionRepository.getInstance(activity)
        val old = session.isCookieEnabled("抖音")
        val page = adapter(activity, "<p>pending</p>")
        try {
            session.setCookieEnabled("抖音", true)
            val job = async { page.capture(url, "", DouyinAutoResolver.scope(activity, "")) }
            withTimeout(3000) { while (page.activeViews == 0) delay(50) }
            session.setCookieEnabled("抖音", false)
            assertEquals(DouyinPageStatus.SCOPE_CHANGED, job.await().status)
            assertEquals(0, page.activeViews)
        } finally { session.setCookieEnabled("抖音", old); instrument.runOnMainSync { page.close() } }
    } }

    @Test fun isolatedCookiesAreNotReusedAfterClearingTheAccount() = withHost { activity -> runBlocking {
        val session = SessionRepository.getInstance(activity)
        var first: DouyinBrowserProfile? = null
        instrument.runOnMainSync { first = DouyinBrowserProfile.current(activity) }
        assertNotNull("Test WebView must support isolated profiles", first)
        val done = java.util.concurrent.CountDownLatch(1)
        instrument.runOnMainSync {
            first!!.cookies.setCookie("https://www.douyin.com/", "omni_isolation_test=one; Path=/; Secure") { done.countDown() }
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        session.clear("抖音")
        var second: DouyinBrowserProfile? = null
        instrument.runOnMainSync { second = DouyinBrowserProfile.current(activity) }
        assertNotEquals(first!!.name, second!!.name)
        assertFalse(second!!.cookies.getCookie(url).orEmpty().contains("omni_isolation_test"))
        assertFalse(android.webkit.CookieManager.getInstance().getCookie(url).orEmpty().contains("omni_isolation_test"))
    } }

    @Test fun leavingForegroundStopsTheAutomaticPage() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        val page = adapter(activity!!, "<p>pending</p>")
        try {
            val job = async { page.capture(url, "", DouyinAutoResolver.scope(activity!!, "")) }
            withTimeout(3000) { while (page.activeViews == 0) delay(50) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertEquals(DouyinPageStatus.UNAVAILABLE, job.await().status)
            assertEquals(0, page.activeViews)
        } finally { instrument.runOnMainSync { page.close() }; scenario.close() }
    }
}
