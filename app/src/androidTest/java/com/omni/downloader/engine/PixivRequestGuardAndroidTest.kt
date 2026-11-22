package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PixivRequestGuardAndroidTest {
    @Test fun nativeAndPythonShareRecordLocksPacingAndDurableCooldown(): Unit = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val directory=File(context.cacheDir,"guard-cross-${UUID.randomUUID()}").apply{mkdirs()}
        val native=PixivRequestGuard(directory)
        val base=File(context.noBackupFilesDir,"youtubedl-android");val home=File(base,"packages/python/usr")
        val script="""
            import sys,json,time
            from omni_pixiv_guard import PixivGuard
            from omni_media import session
            from requests.adapters import HTTPAdapter
            import requests
            guard=PixivGuard(sys.argv[1])
            print('READY',flush=True)
            with guard.locked(): print('LOCKED',flush=True)
            guard.acquire('api')
            admitted=int(time.time()*1000)
            calls=[]
            def transport(self,request,**kwargs):
                calls.append(request.url)
                response=requests.Response();response.status_code=429
                response.headers['Retry-After']='3600';response._content=b'{}';response._content_consumed=True
                return response
            HTTPAdapter.send=transport
            try: session().get('https://i.pximg.net/fixture.png')
            except Exception as error:
                assert 'RATE_LIMITED' in str(error),str(error)
            else: raise AssertionError('429 did not stop')
            assert len(calls)==1,calls
            print(json.dumps({'admitted':admitted,'pythonRequests':len(calls),'state':json.load(open(sys.argv[1]+'/pixiv.json'))}),flush=True)
        """.trimIndent()
        var process:Process?=null
        try {
            native.acquire("api");val first=JSONObject(File(directory,"pixiv.json").readText()).getLong("api")-2000
            RandomAccessFile(File(directory,"pixiv.lock"),"rw").use { lockFile ->
                lockFile.channel.lock().use {
                    process=ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libpython.so","-c",script,directory.path)
                        .apply {environment().apply {
                            put("PYTHONHOME",home.path);put("OMNI_PIXIV_GUARD_DIR",directory.path)
                            put("PYTHONPATH",File(base,"yt-dlp/yt-dlp").path)
                            put("LD_LIBRARY_PATH","${home.path}/lib:${base.path}/packages/ffmpeg/usr/lib:${base.path}/packages/aria2c/usr/lib")
                        }}.redirectErrorStream(true).start()
                    val reader=process!!.inputStream.bufferedReader()
                    assertEquals("READY",reader.readLine())
                    Thread.sleep(250)
                    assertFalse("Python must not enter while Java owns the record lock",reader.ready())
                    assertFalse("Python lockf must wait for Java's record lock",process!!.waitFor(1,TimeUnit.MILLISECONDS))
                    it.release()
                    assertEquals("LOCKED",reader.readLine())
                    val output=reader.readText()
                    assertTrue(output,process!!.waitFor(10,TimeUnit.SECONDS));assertEquals(output,0,process!!.exitValue())
                    val result=JSONObject(output.trim())
                    assertTrue(result.getLong("admitted")-first>=1900)
                    assertEquals(1,result.getInt("pythonRequests"))
                    assertTrue(result.getJSONObject("state").getLong("until")>=result.getLong("admitted")+3_600_000)
                    assertEquals(MediaErrorCode.RATE_LIMITED,MediaErrors.codeOf(runCatching {PixivRequestGuard(directory).acquire("api")}.exceptionOrNull()!!))
                    instrument.sendStatus(0,Bundle().apply {putString("stream","OMNI_PIXIV_GUARD_CROSS=$result\n")})
                }
            }
        } finally { process?.destroyForcibly();directory.deleteRecursively() }
    }
    @Test fun actualNetworkInterceptorStops429AndBlocksOtherClientsWithoutSending(): Unit = runBlocking {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"guard-http-${UUID.randomUUID()}")
        val guard=PixivRequestGuard(directory);val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));val calls=AtomicInteger()
        val thread=Thread {while(!server.isClosed) try {server.accept().use {socket ->
            val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            calls.incrementAndGet();socket.getOutputStream().apply {write("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3600\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray());flush()}
        }}catch(_:Exception){} }.apply{isDaemon=true;start()}
        try {
            val client=PixivNetwork.configure(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object:Dns {
                override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))
            }),guard).build()
            for(host in listOf("www.pixiv.net","i.pximg.net")) {
                val error=runCatching {BiliHttp.read(client,Request.Builder().url("http://$host:${server.localPort}/fixture").build()){it.code}}.exceptionOrNull()!!
                assertEquals(MediaErrorCode.RATE_LIMITED,MediaErrors.codeOf(error))
            }
            assertEquals(1,calls.get())
            // Other platforms keep their existing network behavior during Pixiv's cooldown.
            BiliHttp.read(client,Request.Builder().url("http://other.example.invalid:${server.localPort}/fixture").build()){assertEquals(429,it.code)}
            assertEquals(2,calls.get())
        } finally {server.close();thread.join(2000);directory.deleteRecursively()}
    }
}
