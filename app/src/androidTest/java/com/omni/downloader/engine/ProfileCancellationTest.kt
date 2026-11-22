package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled blocked transport; no request reaches a platform or real account. */
class ProfileCancellationTest {
    @Test fun cancellingSharedResolverCannotEscapeItsReaderThread()=cancelBlockedResolver(true)
    @Test fun cancellingCoroutineAloneStopsBlockedResolverPromptly()=cancelBlockedResolver(false)
    private fun cancelBlockedResolver(explicitKill:Boolean):Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val server=ServerSocket(0);val entered=AtomicBoolean();var socket:java.net.Socket?=null
        val serverThread=Thread{runCatching{socket=server.accept();entered.set(true);while(!server.isClosed)Thread.sleep(25)}}.apply{isDaemon=true;start()}
        val escaped=CopyOnWriteArrayList<Throwable>();val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->
            if(error.stackTrace.any{it.className.contains("SharedPython")})escaped+=error else previous?.uncaughtException(thread,error)
        }
        val id="cancel-fixture-${UUID.randomUUID()}"
        val job=launch(Dispatchers.IO){SharedPython.run(context,"images","https://www.pixiv.net/artworks/123456789","Pixiv","http://127.0.0.1:${server.localPort}",id)}
        try{
            withTimeout(15000){while(!entered.get() && job.isActive)delay(20)}
            assertTrue("Resolver did not enter controlled transport",entered.get())
            withContext(Dispatchers.Main){job.cancel();if(explicitKill)SharedPython.cancel(id)}
            withTimeout(5000){job.join()};delay(300)
            assertTrue("Cancellation escaped worker thread: ${escaped.map{it.toString()}}",escaped.isEmpty())
        }finally{
            job.cancel();runCatching{SharedPython.cancel(id)};server.close();socket?.close();serverThread.join(2000)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
    @Test fun cancellingNativePixivTransportDoesNotLeakCallbackExceptions():Unit=runBlocking {
        val server=ServerSocket(0);var socket:java.net.Socket?=null;val entered=AtomicBoolean()
        val thread=Thread{runCatching{socket=server.accept();entered.set(true);while(!server.isClosed)Thread.sleep(25)}}.apply{isDaemon=true;start()}
        val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val job=launch(Dispatchers.IO){BiliHttp.read(client,okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/ajax/illust/fixture/pages").build()){it.body!!.string()}}
        try{withTimeout(5000){while(!entered.get())delay(10)};job.cancel();withTimeout(2000){job.join()};assertTrue(job.isCancelled)}
        finally{job.cancel();server.close();socket?.close();thread.join(2000);client.dispatcher.executorService.shutdown()}
    }
    @Test fun cancellingPixivResponseBodyReleasesTheCallback():Unit=runBlocking {
        val server=ServerSocket(0);var socket:java.net.Socket?=null;val entered=AtomicBoolean();val released=AtomicBoolean()
        val thread=Thread{runCatching{socket=server.accept();socket!!.getOutputStream().apply{
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 9000\r\n\r\n{\"body\":".toByteArray());flush()
        };while(!server.isClosed)Thread.sleep(25)}}.apply{isDaemon=true;start()}
        val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val job=launch(Dispatchers.IO){BiliHttp.read(client,okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/pages").build()){
            entered.set(true);try{it.body!!.string()}finally{released.set(true)}
        }}
        try{withTimeout(5000){while(!entered.get())delay(10)};job.cancel();withTimeout(2000){job.join();while(!released.get())delay(10)}}
        finally{job.cancel();server.close();socket?.close();thread.join(2000);client.dispatcher.executorService.shutdown()}
    }
}
