package com.omni.downloader.ui.components

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.engine.DouyinVideoParser
import com.omni.downloader.ui.MainActivity
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import com.omni.downloader.engine.LinkNormalizer
import com.omni.downloader.engine.UrlSniffer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual WebView video decoding, not mocked videoWidth/currentSrc properties. */
class SocialQualityBrowserTest {
    private val id = "7699370307513319141"
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun evaluate(web: WebView, code: String): String {
        var result = ""; val done = CountDownLatch(1)
        instrument.runOnMainSync { web.evaluateJavascript(code) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS)); return result
    }

    private fun withPlayback(pageId: String, bindMedia: Boolean = true, test: (WebView) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var web: WebView
        try {
            scenario.onActivity { activity ->
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                            return if (request?.url?.path == "/quality.mp4") {
                                WebResourceResponse("video/mp4", null, instrument.context.assets.open("quality-1080.mp4"))
                            } else WebResourceResponse("application/json", "UTF-8", "{}".byteInputStream())
                        }
                    }
                }
                activity.setContentView(web)
                web.loadDataWithBaseURL("https://www.douyin.com/video/$pageId", """<h1>fixture</h1>
                    <video autoplay muted loop style="width:240px;height:426px" src="https://www.douyin.com/quality.mp4${if (bindMedia) "?__vid=$pageId" else ""}"></video>""",
                    "text/html", "UTF-8", null)
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (evaluate(web, "!!(document.querySelector('video')?.videoWidth && document.querySelector('video').getBoundingClientRect().width)") != "true" && System.nanoTime() < until) Thread.sleep(100)
            assertEquals("1080", evaluate(web, "document.querySelector('video').videoWidth"))
            test(web)
        } finally {
            instrument.runOnMainSync { runCatching { web.destroy() } }
            scenario.close()
        }
    }

    @Test fun real1080PlaybackIsMergedWithCached720DetailWithoutWaitingForUnsignedApi() = withPlayback(id) { web ->
        evaluate(web, """window.__omniCapturedVideo=[{aweme_id:'$id',video:{play_addr:{width:720,height:1280,url_list:['https://cdn.test/low.mp4']}}}];""")
        evaluate(web, DouyinVideoBrowserScript.extract(id))
        val value = evaluate(web, "window.__omniVideoResult")
        val output = JSONObject(JSONTokener(value).nextValue() as String)
        println("QUALITY_FIXTURE=" + evaluate(web, "JSON.stringify({url:location.href,ready:document.querySelector('video').readyState,rect:document.querySelector('video').getBoundingClientRect().width,src:document.querySelector('video').currentSrc,result:window.__omniVideoResult})"))
        assertTrue(output.getBoolean("ok"))
        val metadata = DouyinVideoParser.parseItem(output.getJSONObject("item").toString(), "https://www.douyin.com/video/$id")
        assertEquals(1080, metadata.availableVideoFormats.first().width)
        assertEquals(1920, metadata.availableVideoFormats.first().height)
        assertTrue(metadata.availableVideoFormats.any { it.width == 720 })
    }

    @Test fun visibleDifferentWorkCannotBeCapturedAsTheRequestedVideo() = withPlayback("7699370307513319000") { web ->
        evaluate(web, DouyinVideoBrowserScript.extract(id))
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (evaluate(web, "window.__omniVideoResult !== null") != "true" && System.nanoTime() < until) Thread.sleep(50)
        val output = JSONObject(JSONTokener(evaluate(web, "window.__omniVideoResult")).nextValue() as String)
        assertFalse(output.getBoolean("ok"))
    }

    @Test fun unbound1080PlaceholderDoesNotReplaceTheTargets720Stream() = withPlayback(id, false) { web ->
        evaluate(web, """window.__omniCapturedVideo={aweme_id:'$id',video:{play_addr:{width:720,height:1280,url_list:['https://cdn.test/low.mp4']}}};""")
        evaluate(web, DouyinVideoBrowserScript.extract(id))
        val output = JSONObject(JSONTokener(evaluate(web, "window.__omniVideoResult")).nextValue() as String)
        val metadata = DouyinVideoParser.parseItem(output.getJSONObject("item").toString(), "https://www.douyin.com/video/$id")
        assertEquals(720, metadata.availableVideoFormats.first().width)
        assertFalse(metadata.availableVideoFormats.any { it.width == 1080 })
    }

    @Test fun optInLiveTargetThroughTheActualBrowserController(): Unit = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("socialBrowserUrl")
        assumeTrue("Only explicitly supplied live links", input != null)
        val target = UrlSniffer.sanitizeAndResolveUrl(input!!)
        val targetId = LinkNormalizer.contentId(target, "抖音")!!
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var controller: LoginBrowserController? = null
        val result = JSONObject().put("success", false).put("mediaId", targetId)
        try {
            scenario.onActivity { activity ->
                controller = LoginBrowserController(activity, "抖音", "https://www.douyin.com/video/$targetId", true,
                    onProgress = {}, onPageFinished = {}, onNotice = {},
                    documentStartScript = "window.__omniCaptureId='$targetId';\n" +
                        activity.assets.open("browser/douyin-video-capture.js").bufferedReader().use { it.readText() })
                activity.setContentView(controller!!.container)
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var captured: JSONObject? = null
            while (System.nanoTime() < until && captured == null) {
                val web = controller!!.current
                evaluate(web, DouyinVideoBrowserScript.extract(targetId))
                Thread.sleep(1500)
                val value = evaluate(web, "window.__omniVideoResult || null")
                val output = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
                if (output?.optBoolean("ok") == true) captured = output.getJSONObject("item")
            }
            assertNotNull("Live target did not expose playback/detail; may require user verification", captured)
            val metadata = DouyinVideoParser.parseItem(captured!!.toString(), "https://www.douyin.com/video/$targetId")
            val selected = metadata.availableVideoFormats.first()
            result.put("width", selected.width).put("height", selected.height).put("formats", metadata.availableVideoFormats.size)
            assertEquals(targetId, metadata.mediaId)
            assertTrue("The known 1080p sample must not remain at 720p", minOf(selected.width, selected.height) >= 1080)
            File(instrument.targetContext.cacheDir, "browser-quality-item.json").writeText(captured.toString())
            result.put("success", true)
            Unit
        } finally {
            File(instrument.targetContext.cacheDir, "social-browser-live-result.json").writeText(result.toString())
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "OMNI_BROWSER_QUALITY=$result\n") })
            instrument.runOnMainSync { controller?.close() }; scenario.close()
        }
    }
}
