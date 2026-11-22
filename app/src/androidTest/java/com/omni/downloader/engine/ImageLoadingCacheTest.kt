package com.omni.downloader.engine

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.SuccessResult
import com.omni.downloader.data.model.ImageResource
import com.omni.downloader.ui.screens.ImageRequestCache
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ImageLoadingCacheTest {
    @Test fun reentryUsesMemoryAndDiskWithoutNetworkAndSeparatesAccounts():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val root=File(context.cacheDir,"image-loading-${UUID.randomUUID()}")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val payload=ByteArrayOutputStream().apply{bitmap.compress(Bitmap.CompressFormat.PNG,100,this)}.toByteArray();bitmap.recycle()
        val server=ServerSocket(0);val calls=AtomicInteger()
        val thread=Thread{while(!server.isClosed)try{server.accept().use{socket->
            socket.soTimeout=3000;val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            calls.incrementAndGet();Thread.sleep(400)
            socket.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray());write(payload);flush()}
        }}catch(_:Exception){}}.apply{isDaemon=true;start()}
        val loader=context.imageLoader.newBuilder().okHttpClient(okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            .memoryCache(MemoryCache.Builder(context).maxSizeBytes(8*1024*1024).build())
            .diskCache(DiskCache.Builder().directory(root).maxSizeBytes(4*1024*1024).build()).build()
        val image=ImageResource("cache-test","http://127.0.0.1:${server.localPort}/image.png",64,64)
        fun cache(cookie:String)=ImageRequestCache(context,"https://fixture.invalid/"){cookie}
        try{
            val cold=cache("account=a").request(image,true);val began=android.os.SystemClock.elapsedRealtime()
            assertEquals(DataSource.NETWORK,(loader.execute(cold) as SuccessResult).dataSource)
            val coldMs=android.os.SystemClock.elapsedRealtime()-began
            val reentry=cache("account=a");val warmStart=android.os.SystemClock.elapsedRealtime()
            assertEquals(DataSource.MEMORY_CACHE,(loader.execute(reentry.request(image,true)) as SuccessResult).dataSource)
            val warmMs=android.os.SystemClock.elapsedRealtime()-warmStart
            assertEquals(1,calls.get())
            val full=reentry.request(image,false)
            assertNotNull(loader.memoryCache!![full.placeholderMemoryCacheKey!!])
            loader.memoryCache!!.clear()
            assertEquals(DataSource.DISK,(loader.execute(reentry.request(image,true)) as SuccessResult).dataSource)
            assertEquals(1,calls.get())
            assertEquals(DataSource.DISK,(loader.execute(full) as SuccessResult).dataSource)
            assertEquals(1,calls.get()) // Unchanged original URL shares encoded bytes, not its static bitmap.
            assertEquals(DataSource.NETWORK,(loader.execute(cache("account=b").request(image,true)) as SuccessResult).dataSource)
            assertEquals(2,calls.get())
            val report=JSONObject().put("coldMs",coldMs).put("memoryMs",warmMs).put("fixtureDelayMs",400).put("requestsForSameAccount",1).put("requestsAfterAccountChange",calls.get())
            instrument.sendStatus(0,android.os.Bundle().apply{putString("stream","OMNI_IMAGE_LOADING=$report\n")})
        }finally{loader.shutdown();server.close();thread.join(3000);root.deleteRecursively()}
    }
    @Test fun simultaneousRequestConstructionDoesCookieWorkOnce():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val calls=AtomicInteger()
        val cache=ImageRequestCache(context,"https://x.com/"){assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());calls.incrementAndGet();"fixture=one"}
        val image=ImageResource("one","https://pbs.twimg.com/media/one?format=jpg&name=orig")
        val requests=coroutineScope{List(16){async{cache.request(image,true)}}.awaitAll()}
        assertEquals(1,calls.get());assertTrue(requests.all{it===requests.first()})
    }
}
