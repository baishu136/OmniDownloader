package com.omni.downloader.ui.components

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DouyinVideoBrowserTest {
    private val id = "7696865347672319930"
    private val item = """{"aweme_id":"$id","desc":"target","video":{"width":1080,"height":1920,"duration":5500,"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.test/target.mp4"]}}}"""
    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1); var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS)); return result
    }

    @Test fun capturesOnlyMediaBearingTargetFromNormalResponsesAndExportsIt() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        lateinit var web: WebView
        var handler: androidx.webkit.ScriptHandler? = null
        val ready = CountDownLatch(1)
        val targetId = id
        try {
            instrument.runOnMainSync {
                web = WebView(instrument.targetContext).apply {
                    settings.javaScriptEnabled = true
                    handler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,
                        "window.__omniCaptureId='$targetId';\n" + context.assets.open("browser/douyin-video-capture.js").bufferedReader().use { it.readText() },
                        setOf("https://www.douyin.com"))
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { ready.countDown() }
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (request?.url?.path?.startsWith("/aweme/v1/") != true) return null
                            val json = """{"shell":{"aweme_id":"$targetId","video":{"play_addr":{}}},"wrong":{"aweme_id":"111","video":{"play_addr":{"url_list":["https://cdn.test/wrong.mp4"]}}},"target":$item}"""
                            return WebResourceResponse("application/json", "UTF-8", json.byteInputStream())
                        }
                    }
                    loadDataWithBaseURL("https://www.douyin.com/video/$targetId", "<script>fetch('/aweme/v1/web/aweme/detail/').then(r=>r.json())</script>", "text/html", "UTF-8", null)
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            var captured: JSONObject? = null
            repeat(30) {
                if (captured == null) {
                    captured = runCatching { JSONObject(JSONTokener(evaluate(web, "JSON.stringify(window.__omniCapturedVideo || null)")).nextValue() as String) }.getOrNull()
                    if (captured == null) Thread.sleep(100)
                }
            }
            assertEquals(id, captured!!.getString("aweme_id"))
            evaluate(web, DouyinVideoBrowserScript.extract(id))
            val result = JSONObject(JSONTokener(evaluate(web, "window.__omniVideoResult")).nextValue() as String)
            assertTrue(result.getBoolean("ok"))
            assertEquals("https://cdn.test/target.mp4", result.getJSONObject("item").getJSONObject("video").getJSONObject("play_addr").getJSONArray("url_list").getString(0))
        } finally { instrument.runOnMainSync { handler?.remove(); runCatching { web.destroy() } } }
    }

    @Test fun capturedTargetReturnsToExistingFormatSelectionWithoutCreatingDownload() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: MainViewModel
        val store = ViewModelStore()
        instrument.runOnMainSync {
            viewModel = MainViewModel(instrument.targetContext.applicationContext as android.app.Application)
            store.put("qa", viewModel)
        }
        try {
            val before = viewModel.tasks.value.size
            instrument.runOnMainSync { viewModel.acceptBrowserVideo(item, "https://www.douyin.com/video/$id") }
            repeat(40) { if (viewModel.metadata.value == null) Thread.sleep(50) }
            assertEquals(id, viewModel.metadata.value!!.mediaId)
            assertEquals(1080, viewModel.metadata.value!!.availableVideoFormats.first().width)
            assertTrue(viewModel.showFormatSheet.value)
            assertEquals(before, viewModel.tasks.value.size)
        } finally { instrument.runOnMainSync { store.clear() } }
    }
}
