package com.omni.downloader.ui.components

import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RelayCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualWebViewSubmitsOnceAndContinuesAfterAnInvalidApiCandidate() {
        val successes = AtomicInteger(); var url = ""; var deliveredOnMain = false
        lateinit var session: RelayCaptureSession; lateinit var web: WebView
        compose.setContent {
            AndroidView(factory = { context ->
                session = RelayCaptureSession("fixture", { captured, _ ->
                    url = captured; deliveredOnMain = Looper.myLooper() == Looper.getMainLooper(); successes.incrementAndGet()
                })
                WebView(context).also {
                    web = it
                    RelayAutomation.bind(it, session, "https://source.example/video/2", true)
                    it.loadDataWithBaseURL("https://relay.example/", """
                        <html><body><input name="url" type="url"><button id="parse">开始</button><script>
                        window.clicks=0;
                        window.fetch=function(){return Promise.resolve(new Response(JSON.stringify({title:'fixture',items:[
                          'https://relay.example/api/extract?video=movie.mp4',
                          'https://media.example/movie.mp4?signature=AbC'
                        ]}),{headers:{'Content-Type':'application/json'}}));};
                        document.getElementById('parse').onclick=function(){window.clicks++;fetch('/api/extract',{method:'POST'});};
                        </script></body></html>
                    """.trimIndent(), "text/html", "utf-8", null)
                }
            })
        }
        try {
            compose.waitUntil(12000) { successes.get() > 0 }
            assertEquals("https://media.example/movie.mp4?signature=AbC", url)
            assertTrue(deliveredOnMain)
            var clicks: String? = null
            compose.runOnIdle { web.evaluateJavascript("window.clicks.toString()") { clicks = it } }
            compose.waitUntil(3000) { clicks != null }
            assertEquals("\"1\"", clicks)
            compose.runOnIdle { web.evaluateJavascript("for(var i=0;i<10;i++)OmniBridge.onMedia('https://media.example/movie.mp4','dup','video/mp4')", null) }
            compose.waitForIdle(); assertEquals(1, successes.get())
        } finally { compose.runOnIdle { session.close(); web.stopLoading(); web.destroy() } }
    }

    @Test fun closingSessionSuppressesLateCallbacksAndManualParsingDoesNotAutoSubmit() {
        val successes = AtomicInteger(); lateinit var session: RelayCaptureSession; lateinit var web: WebView
        compose.setContent {
            AndroidView(factory = { context ->
                session = RelayCaptureSession("fixture", { _, _ -> successes.incrementAndGet() })
                WebView(context).also {
                    web = it; it.settings.javaScriptEnabled = true
                    it.addJavascriptInterface(session, "OmniBridge")
                    it.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { RelayAutomation.install(it, "https://source.example/video/2", false) }
                    }
                    it.loadDataWithBaseURL("https://relay.example/", "<input name=url type=url><button onclick='window.clicks++'>开始</button><script>window.clicks=0</script>", "text/html", "utf-8", null)
                }
            })
        }
        try {
            var ready: String? = null
            compose.waitUntil(8000) {
                compose.runOnUiThread { web.evaluateJavascript("window.__omniCapture ? window.clicks.toString() : null") { ready = it } }
                ready == "\"0\""
            }
            compose.runOnIdle { session.close(); session.onSubmitted() }
            assertFalse(session.capture("https://media.example/video.mp4", explicit = true))
            compose.waitForIdle(); assertEquals(0, successes.get())
        } finally { compose.runOnIdle { session.close(); web.stopLoading(); web.destroy() } }
    }
}
