package com.omni.downloader.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DouyinImagesBrowserTest {
    @Test fun capturesOnlyTargetImagesFromNormalFirstPartyResponsesAtDocumentStart() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var web: WebView
        var handler: androidx.webkit.ScriptHandler? = null
        val id = "7625568178885657747"
        try {
            instrument.runOnMainSync {
                web = WebView(instrument.targetContext).apply {
                    settings.javaScriptEnabled = true
                    val script = "window.__omniCaptureId='$id';\n" + context.assets.open("browser/douyin-images-capture.js").bufferedReader().use { it.readText() }
                    handler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, script, setOf("https://www.douyin.com"))
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { ready.countDown() }
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (request?.url?.path?.startsWith("/aweme/v1/") != true) return null
                            val json = """{"aweme_list":[{"aweme_id":"111","images":[{"url_list":["https://cdn.test/wrong.jpg"]}]},{"aweme_id":"$id","images":[{"url_list":["https://cdn.test/right.gif"]}]}]}"""
                            return WebResourceResponse("application/json", "UTF-8", json.byteInputStream())
                        }
                    }
                    loadDataWithBaseURL("https://www.douyin.com/note/$id", "<script>fetch('/aweme/v1/web/aweme/detail/').then(r=>r.json())</script>", "text/html", "UTF-8", null)
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            var captured: JSONObject? = null
            repeat(30) {
                if (captured == null) {
                    val done = CountDownLatch(1)
                    instrument.runOnMainSync { web.evaluateJavascript("JSON.stringify(window.__omniCapturedImages || null)") { result ->
                        captured = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull(); done.countDown()
                    } }
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    if (captured == null) Thread.sleep(100)
                }
            }
            assertNotNull(captured)
            assertEquals(id, captured!!.getString("aweme_id"))
            assertEquals("https://cdn.test/right.gif", captured!!.getJSONArray("images").getJSONObject(0).getJSONArray("url_list").getString(0))
        } finally { instrument.runOnMainSync { handler?.remove(); runCatching { web.destroy() } } }
    }
    @Test fun exportsOnlyTargetImagesFromRenderedStateWithoutJavascriptInterface() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var web: WebView
        val id = "7625568178885657747"
        try {
            instrument.runOnMainSync {
                web = WebView(instrument.targetContext).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { ready.countDown() }
                    }
                    loadDataWithBaseURL("https://www.douyin.com/note/$id", """<script>window._ROUTER_DATA={loaderData:{wrong:{aweme_id:'111',images:[{url_list:['https://cdn.test/wrong.jpg']}]},target:{aweme_id:'$id',images:[{url_list:['https://cdn.test/right.jpg']}],desc:'fixture'}}};</script>""", "text/html", "UTF-8", null)
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            fun evaluate(script: String): String {
                val done = CountDownLatch(1); var result = ""
                instrument.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
                assertTrue(done.await(10, TimeUnit.SECONDS)); return result
            }
            evaluate(DouyinBrowserScript.extract(id))
            val result = JSONObject(JSONTokener(evaluate("window.__omniImageResult")).nextValue() as String)
            assertTrue(result.getBoolean("ok"))
            val item = result.getJSONObject("item")
            assertEquals(id, item.getString("aweme_id"))
            assertEquals("https://cdn.test/right.jpg", item.getJSONArray("images").getJSONObject(0).getJSONArray("url_list").getString(0))
        } finally { instrument.runOnMainSync { runCatching { web.destroy() } } }
    }
}
