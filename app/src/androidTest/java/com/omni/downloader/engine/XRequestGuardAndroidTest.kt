package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XRequestGuardAndroidTest {
    private class Fixture(private val status:(Int)->Int):AutoCloseable {
        val server=ServerSocket(0).apply{soTimeout=300};val calls=AtomicInteger()
        val thread=Thread{while(!server.isClosed)try{server.accept().use{socket->
            socket.soTimeout=2000
            val reader=socket.getInputStream().bufferedReader();val first=reader.readLine()
            while(!reader.readLine().isNullOrEmpty()){}
            if(first!=null) {val code=status(calls.incrementAndGet());socket.getOutputStream().apply{
                write("HTTP/1.1 $code Fixture\r\nContent-Length: 2\r\nContent-Type: application/json\r\nConnection: close\r\nRetry-After: 3600\r\n\r\n{}".toByteArray());flush()
            }}
        }}catch(_:SocketTimeoutException){}catch(_:Exception){} }.apply{isDaemon=true;start()}
        override fun close(){server.close();thread.join(2000)}
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun client(guard:PixivRequestGuard)=XNetwork.configure(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object:Dns{
        override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))
    }),guard).build()
    @Test fun rateLimitBlocksApiImagesAndVideoBeforeAnotherConnection():Unit=runBlocking {
        val root=File(context.cacheDir,"x-http-${UUID.randomUUID()}")
        try{Fixture{429}.use{fixture->
            val client=client(PixivRequestGuard(root,policy=XNetwork.policy))
            for(host in listOf("api.x.com","pbs.twimg.com","video.twimg.com")) {
                val error=runCatching{BiliHttp.read(client,Request.Builder().url("http://$host:${fixture.server.localPort}/fixture").build()){it.code}}.exceptionOrNull()!!
                assertEquals(MediaErrorCode.RATE_LIMITED,MediaErrors.codeOf(error))
            }
            assertEquals(1,fixture.calls.get())
        }}finally{root.deleteRecursively()}
    }
    @Test fun expiredCdnSourceDoesNotBlockAccountButApiDenialDoes():Unit=runBlocking {
        val root=File(context.cacheDir,"x-cdn-${UUID.randomUUID()}")
        try{Fixture{403}.use{fixture->
            val guard=PixivRequestGuard(root,policy=XNetwork.policy);val client=client(guard)
            assertEquals(403,BiliHttp.read(client,Request.Builder().url("http://video.twimg.com:${fixture.server.localPort}/expired").build()){it.code})
            val error=runCatching{BiliHttp.read(client,Request.Builder().url("http://api.x.com:${fixture.server.localPort}/fixture").build()){it.code}}.exceptionOrNull()!!
            assertEquals(MediaErrorCode.AUTH_REQUIRED,MediaErrors.codeOf(error))
            assertEquals(2,fixture.calls.get())
        }}finally{root.deleteRecursively()}
    }
    @Test fun actualYtdlpCliLoadsGuardOfflineAndHonorsNativeCooldownWithoutConnecting():Unit=runBlocking {
        DownloadEngine.ensureInitialized(context).getOrThrow()
        XGuardRuntime.ensureReady(context) // Diagnostic URI cannot access the network.
        val root=File(context.cacheDir,"x-cli-${UUID.randomUUID()}").apply{mkdirs()}
        val config=File(context.noBackupFilesDir,"youtubedl-android/packages/python/usr/lib/python3.12/site-packages/.omni-x-guard.json")
        val original=config.readText()
        try{Fixture{429}.use{proxy->
            val guard=PixivRequestGuard(root,policy=XNetwork.policy);guard.block(429,"3600")
            config.writeText(org.json.JSONObject().put("directory",root.path).toString())
            val request=YtDlpOptionsFactory.metadataRequest("https://x.com/i/status/123456789",LinkNormalizer.PLATFORM_X,"http://127.0.0.1:${proxy.server.localPort}",true)
            val error=runCatching{YoutubeDL.getInstance().execute(request)}.exceptionOrNull()!!
            assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("[RATE_LIMITED]"))
            assertEquals("Cooldown must stop before a proxy CONNECT",0,proxy.calls.get())
        }}finally{config.writeText(original);root.deleteRecursively()}
    }

    @Test fun bundledGalleryAndRequestsShareNativeCooldownBeforeProxyConnect():Unit=runBlocking {
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val root=File(context.cacheDir,"x-gallery-${UUID.randomUUID()}").apply{mkdirs()}
        val home=File(context.noBackupFilesDir,"youtubedl-android/packages/python/usr")
        val script="""
            import omni_media, requests
            from gallery_dl import extractor
            from omni_pixiv_guard import GuardError
            item=extractor.find('https://x.com/fixture/status/123456789')
            item._init_session()
            assert getattr(requests.Session.send,'_omni_x_guard',False)
            for session,url in [(requests.Session(),'https://x.com/i/api/fixture'),(item.session,'https://pbs.twimg.com/media/fixture')]:
                try: session.get(url,proxies={'https':__import__('sys').argv[1]},timeout=3)
                except GuardError as error: assert '[RATE_LIMITED]' in str(error)
                else: raise AssertionError('native cooldown bypassed')
            print('shared-gallery-guard-ok')
        """.trimIndent()
        try{Fixture{429}.use{proxy->
            PixivRequestGuard(root,policy=XNetwork.policy).block(429,"3600")
            val builder=ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libpython.so","-c",script,"http://127.0.0.1:${proxy.server.localPort}").redirectErrorStream(true)
            builder.environment().apply{
                put("PYTHONHOME",home.path);put("PYTHONPATH",File(context.noBackupFilesDir,"youtubedl-android/yt-dlp/yt-dlp").path)
                put("LD_LIBRARY_PATH","${home.path}/lib");put("OMNI_X_GUARD_DIR",root.path)
            }
            val process=builder.start()
            try{
                assertTrue(process.waitFor(25,TimeUnit.SECONDS));val output=process.inputStream.bufferedReader().readText()
                assertEquals(output,0,process.exitValue());assertTrue(output,output.contains("shared-gallery-guard-ok"))
                assertEquals(0,proxy.calls.get())
            }finally{process.destroyForcibly()}
        }}finally{root.deleteRecursively()}
    }
}
