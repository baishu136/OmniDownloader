package com.omni.downloader

import android.graphics.drawable.Animatable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import com.omni.downloader.ui.screens.ImageRequestCache

class ImageScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun requestCacheKeepsCookieWorkOffMainBoundsDecodingAndRetainsOriginalSources(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cache = ImageRequestCache(context,"https://x.com/") {
            assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());calls.incrementAndGet();"fixture=yes"
        }
        val image = ImageResource("1:photo","https://pbs.twimg.com/media/fixture?format=png&name=orig",4096,3072,true)
        val preview = withContext(Dispatchers.Main) { cache.request(image,true) }
        assertEquals("https://pbs.twimg.com/media/fixture?format=png&name=small",preview.data)
        assertEquals(coil.size.Size(640,640),preview.sizeResolver.size())
        assertEquals("fixture=yes",preview.headers["Cookie"])
        assertSame(preview,cache.request(image,true));assertEquals(1,calls.get())
        val sameScope=ImageRequestCache(context,"https://x.com/"){"fixture=yes"}
        assertEquals(preview.memoryCacheKey,sameScope.request(image,true).memoryCacheKey)
        assertEquals(preview.diskCacheKey,sameScope.request(image,true).diskCacheKey)
        val original = cache.request(image,false)
        assertEquals(image.url,original.data);assertNull(original.decoderFactory)
        assertNotEquals(preview.memoryCacheKey,original.memoryCacheKey)
        assertEquals(preview.memoryCacheKey,original.placeholderMemoryCacheKey)
        val otherScope = ImageRequestCache(context,"https://x.com/"){null}
        assertNotEquals(preview.memoryCacheKey,otherScope.request(image,true).memoryCacheKey)
        val cdnA=ImageRequestCache(context,"https://www.pixiv.net/",accountScope={"Pixiv|account-a"}){null}
        val cdnB=ImageRequestCache(context,"https://www.pixiv.net/",accountScope={"Pixiv|account-b"}){null}
        assertNotEquals(cdnA.request(image,true).diskCacheKey,cdnB.request(image,true).diskCacheKey)
        for (index in 1..161) cache.request(image.copy(id="$index:photo"),true)
        assertNull(cache.peek(image,true))
        val source = File.createTempFile("scroll-large-", ".png",context.cacheDir)
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(4096,3072,android.graphics.Bitmap.Config.RGB_565)
            source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val request=cache.request(image.copy(id="large",url=source.toURI().toString()),true)
            val decoded=context.imageLoader.execute(request) as SuccessResult
            assertTrue(decoded.drawable.intrinsicWidth <= 640 && decoded.drawable.intrinsicHeight <= 640)
        } finally { source.delete() }
    }
    @Test fun manyImageGridUsesStaticPreviewsAndPreservesOriginalViewer() {
        val instrument = InstrumentationRegistry.getInstrumentation(); val context = instrument.targetContext
        SettingsRepository.getInstance(context).setHasPromptedBilibiliLogin(true)
        val payload = instrument.context.assets.open("animated.gif").use { it.readBytes() }
        val server = ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon=true } }
        pool.execute { while (!server.isClosed) try { val socket=server.accept();pool.execute { socket.use { c -> runCatching {
            val reader=c.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: image/gif\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray());write(payload);flush() }
        } } } } catch (_:Exception){} }
        val results = CopyOnWriteArrayList<Pair<String,Boolean>>()
        val oldLoader=context.imageLoader
        val loader=oldLoader.newBuilder().okHttpClient(okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()).eventListenerFactory { object: EventListener {
            override fun onSuccess(request: ImageRequest,result: SuccessResult) {
                if(request.data.toString().contains("127.0.0.1:${server.localPort}")) results += request.memoryCacheKey?.toString().orEmpty() to (result.drawable is Animatable)
            }
        } }.build()
        Coil.setImageLoader(loader)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> ViewModelProvider(activity)[MainViewModel::class.java].apply {
                updateInputUrl("https://www.pixiv.net/artworks/987654321")
                imagePost.value=ImagePost("Pixiv",inputUrl.value,"987654321","Scrolling fixture",resources=List(160){ ImageResource("p$it","http://127.0.0.1:${server.localPort}/$it.gif",128,128) })
            } }
            compose.onNodeWithTag("home_back_to_top").assertDoesNotExist()
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("image_card_0"))
            compose.waitUntil(10000){results.isNotEmpty()}
            repeat(5){compose.onNodeWithTag("home_list").performTouchInput { swipeUp(durationMillis=250) }}
            compose.waitForIdle()
            val report=JSONObject().put("loaded",results.size).put("animatedGridResults",results.count { it.second })
            File(context.getExternalFilesDir(null),"image-scroll-report.json").writeText(report.toString())
            instrument.sendStatus(0,android.os.Bundle().apply {putString("stream","OMNI_IMAGE_SCROLL=$report\n")})
            assertTrue("List thumbnails are still animated: $report",results.none { it.second })
            compose.onNodeWithTag("home_back_to_top").assertIsDisplayed()
            val screenshot = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null),"image-scroll-floating.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
            compose.onNodeWithTag("home_back_to_top").performClick()
            compose.onNodeWithTag("home_link_input").assertIsDisplayed()
            compose.onNodeWithTag("home_back_to_top").assertDoesNotExist()
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("image_preview_0"))
            compose.onNodeWithTag("image_preview_0",useUnmergedTree=true).performClick()
            compose.waitUntil(10000){results.any { it.second }}
            compose.onNodeWithText("关闭").performClick()
        } finally {
            scenario.close();Coil.setImageLoader(oldLoader);loader.shutdown();server.close();pool.shutdownNow()
        }
    }
}
