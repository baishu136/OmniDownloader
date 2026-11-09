package com.omni.downloader.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewFeature
import com.omni.downloader.data.repository.BrowserCookieRestorer
import com.omni.downloader.data.repository.NetscapeCookies
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local fixtures only: no real account cookies are read, cleared or sent to a platform. */
class DesktopLoginBrowserTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0).apply { soTimeout = 500 }
        private val closed = AtomicBoolean(false)
        val headers = ConcurrentHashMap<String, Map<String, String>>()
        val url = "http://127.0.0.1:${server.localPort}"
        private val thread = Thread {
            while (!closed.get()) try {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: "/"
                    val request = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val pair = line.split(':', limit = 2)
                        if (pair.size == 2) request[pair[0].lowercase()] = pair[1].trim()
                    }
                    headers[path] = request
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                        <body style="margin:0"><button style="position:fixed;left:0;top:0;width:200px;height:100px" onclick="window.open('/child')">Open login</button>
                        <script>document.querySelector('meta[name=viewport]').content='width=device-width';</script></body></html>""".trimIndent().toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\n" +
                            "Accept-CH: Sec-CH-UA-Mobile, Sec-CH-UA-Platform\r\nConnection: close\r\n\r\n").toByteArray())
                        write(html); flush()
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: Exception) { if (!closed.get()) throw IllegalStateException("Fixture server failed") }
        }.apply { isDaemon = true; start() }
        override fun close() { closed.set(true); server.close(); thread.join(2000) }
    }

    private fun inspect(web: WebView): JSONObject {
        var result: String? = null
        compose.runOnIdle { web.evaluateJavascript("JSON.stringify({ua:navigator.userAgent,width:innerWidth,mobile:navigator.userAgentData?navigator.userAgentData.mobile:null,platform:navigator.userAgentData?navigator.userAgentData.platform:null,viewport:document.querySelector('meta[name=viewport]').content,opener:window.opener!==null})") { result = it } }
        compose.waitUntil(5000) { result != null }
        return JSONObject(JSONTokener(result!!).nextValue() as String)
    }

    @Test fun desktopHeadersClientHintsAndViewportThenMobileRestoration() {
        Fixture().use { fixture ->
            lateinit var web: WebView
            lateinit var identity: WebViewDesktopMode
            var loaded = false
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply {
                        web = this; settings.javaScriptEnabled = true
                        identity = WebViewDesktopMode(this); identity.apply(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { identity.onPageFinished(); loaded = true }
                        }
                        loadUrl(fixture.url + "/desktop")
                    }
                })
            }
            try {
                compose.waitUntil(20000) { loaded }
                val desktop = inspect(web)
                assertFalse(desktop.getString("ua").contains("Mobile"))
                assertFalse(desktop.getString("ua").contains("Android"))
                assertEquals(1280, desktop.getInt("width"))
                assertTrue(desktop.getString("viewport").contains("width=1280"))
                assertTrue(fixture.headers["/desktop"]!!["user-agent"]!!.contains("Windows NT 10.0"))
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    assertFalse(desktop.getBoolean("mobile"))
                    assertEquals("Windows", desktop.getString("platform"))
                    assertEquals("?0", fixture.headers["/desktop"]!!["sec-ch-ua-mobile"])
                }
                compose.runOnIdle { loaded = false; identity.apply(false); web.loadUrl(fixture.url + "/mobile") }
                compose.waitUntil(15000) { loaded }
                val mobile = inspect(web)
                assertTrue(mobile.getString("ua").contains("Android"))
                assertTrue(mobile.getInt("width") < 1280)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) assertTrue(mobile.getBoolean("mobile"))
            } finally { compose.runOnIdle { identity.close(); web.stopLoading(); web.destroy() } }
        }
    }

    @Test fun userOpenedPopupKeepsDesktopIdentityAndWindowOpener() {
        Fixture().use { fixture ->
            lateinit var controller: LoginBrowserController
            val pages = AtomicInteger()
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    LoginBrowserController(context, "desktop-local-fixture", fixture.url + "/parent", true, {}, { pages.incrementAndGet() }, {}).also { controller = it }.container
                })
            }
            try {
                compose.waitUntil(20000) { fixture.headers.containsKey("/parent") && pages.get() >= 1 }
                compose.runOnIdle {
                    val web = controller.root
                    val ratio = web.width / 1280f
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 80 * ratio, 40 * ratio, 0).also { web.dispatchTouchEvent(it); it.recycle() }
                    MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, 80 * ratio, 40 * ratio, 0).also { web.dispatchTouchEvent(it); it.recycle() }
                }
                compose.waitUntil(12000) { fixture.headers.containsKey("/child") && pages.get() >= 2 }
                lateinit var child: WebView
                compose.runOnIdle { assertEquals(2, controller.container.childCount); child = controller.container.getChildAt(1) as WebView }
                val state = inspect(child)
                assertTrue(state.getBoolean("opener"))
                assertFalse(state.getString("ua").contains("Mobile"))
                assertEquals(1280, state.getInt("width"))
                compose.runOnIdle { child.evaluateJavascript("window.close()", null) }
                var remaining = 2
                compose.waitUntil(5000) { compose.runOnUiThread { remaining = controller.container.childCount }; remaining == 1 }
            } finally { compose.runOnIdle { controller.close() } }
        }
    }

    @Test fun cookieRestorationCallbackRunsAfterEveryWrite() {
        var ready = false
        var count = 0
        val rows = (1..3).map { NetscapeCookies.CookieRow("omni-login-fixture.invalid", false, "/", true, 0,
            "fixture$it", "value$it", true) }
        compose.runOnIdle {
            BrowserCookieRestorer.restore(rows) {
                val value = CookieManager.getInstance().getCookie("https://omni-login-fixture.invalid/").orEmpty()
                count = rows.count { value.contains("${it.name}=${it.value}") }
                ready = true
            }
        }
        compose.waitUntil(10000) { ready }
        assertEquals(3, count)
    }
}
