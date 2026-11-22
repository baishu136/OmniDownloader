package com.omni.downloader.engine

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class ParallelRangeDownloaderTest {
    private class Server(val mode: String = "ok", val delay: Long = 0) : AutoCloseable {
        val payload = ByteArray(1024*1024+13) { (it % 251).toByte() }
        private val socket = ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon=true } }
        val url = "http://127.0.0.1:${socket.localPort}/media"
        val peak = AtomicInteger(); val active = AtomicInteger(); val requests = AtomicInteger()
        init { pool.execute {
            while (!socket.isClosed) try { val connection=socket.accept();pool.execute { connection.use { c ->
                c.soTimeout=5000
                try {
                    val input=c.getInputStream().bufferedReader();input.readLine()
                    val headers=generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    requests.incrementAndGet()
                    val range=headers["range"]?.removePrefix("bytes=")?.split('-')
                    val from=range?.get(0)?.toInt()?:0;val to=range?.get(1)?.toInt()?:payload.lastIndex
                    val probe=range!=null && from==0 && to==0
                    val ignore=mode=="ignore" || (mode=="ignore-part" && !probe)
                    val code=when { mode=="denied" && !probe -> "403 Forbidden";mode=="limited" && !probe -> "429 Too Many Requests";ignore || range==null -> "200 OK";else -> "206 Partial Content" }
                    val start=if(ignore)0 else from;val end=if(ignore)payload.lastIndex else to
                    val tag=if(mode=="changed" && !probe)"\"b\"" else if(mode=="weak")"W/\"a\"" else "\"a\""
                    val output=c.getOutputStream()
                    output.write("HTTP/1.1 $code\r\nContent-Length: ${end-start+1}\r\nContent-Range: bytes ${if(mode=="wrong" && !probe)start+1 else start}-$end/${payload.size}\r\n${if(mode=="no-tag")"" else "ETag: $tag\r\n"}Content-Type: video/mp4\r\nConnection: close\r\n\r\n".toByteArray());output.flush()
                    if(code.startsWith("403") || code.startsWith("429")) return@use
                    if(!probe) { val a=active.incrementAndGet();peak.updateAndGet { maxOf(it,a) } }
                    try {
                        if(mode=="blocked" && !probe) { Thread.sleep(5000);return@use }
                        var offset=start
                        while(offset<=end) {
                            val count=minOf(8192,end-offset+1)
                            if(!probe && delay>0)Thread.sleep(delay)
                            output.write(payload,offset,count);output.flush();offset+=count
                            if(mode=="truncated" && !probe)break
                        }
                    } finally { if(!probe)active.decrementAndGet() }
                } catch (_:Exception) { }
            } } } catch (_:Exception) { }
        } }
        override fun close() {socket.close();pool.shutdownNow();pool.awaitTermination(2,TimeUnit.SECONDS)}
    }
    private val client=OkHttpClient.Builder().readTimeout(2,TimeUnit.SECONDS).build()
    private fun output()=File.createTempFile("parallel-test-",".media")
    @Test fun probeRedirectDoesNotForwardCapturedHeadersToAnotherEndpoint(): Unit = runBlocking {
        Server().use { target -> ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { redirect ->
            val worker = Thread { redirect.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: ${target.url}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } }.apply { isDaemon = true; start() }
            val file = output()
            try {
                val request = Request.Builder().url("http://127.0.0.1:${redirect.localPort}/source").header("Cookie","session=fixture").build()
                assertFalse(ParallelRangeDownloader(client,1).download(request,file) {_,_->})
                assertEquals(0,target.requests.get()); assertEquals(0,file.length())
            } finally { file.delete(); worker.join(1000) }
        } }
    }
    @Test fun fourRangesMatchEveryByteAndDemonstratePerConnectionLimitedSpeedup(): Unit = runBlocking {
        Server(delay=4).use { server ->
            val request=Request.Builder().url(server.url).build()
            val start=System.nanoTime()
            val serial=BiliHttp.read(client,request) { it.body!!.bytes() };val serialMs=(System.nanoTime()-start)/1_000_000
            server.peak.set(0)
            val file=output();val values=mutableListOf<Long>();val began=System.nanoTime()
            try {
                assertTrue(ParallelRangeDownloader(client,1).download(request,file) { bytes,total ->assertTrue(bytes<=total);values+=bytes })
                val parallelMs=(System.nanoTime()-began)/1_000_000
                assertArrayEquals(serial,file.readBytes());assertTrue(server.peak.get() in 2..4)
                assertTrue(values.zipWithNext().all { it.first<=it.second })
                System.getenv("OMNI_TRANSFER_REPORT")?.let { File(it).writeText("""{"bytes":${serial.size},"serialMs":$serialMs,"parallelMs":$parallelMs,"peakConnections":${server.peak.get()},"perConnectionChunkBytes":8192,"chunkDelayMs":4,"bytesEqual":true}""") }
            } finally {file.delete()}
        }
    }
    @Test fun unsupportedWeakAndMissingValidatorsUseSerialCompatibilityWithoutOutput(): Unit = runBlocking {
        for(mode in listOf("ignore","weak","no-tag"))Server(mode).use { server ->
            val file=output()
            try { assertFalse(mode,ParallelRangeDownloader(client,1).download(Request.Builder().url(server.url).build(),file) {_,_->});assertEquals(0,file.length());assertEquals(1,server.requests.get()) }
            finally {file.delete()}
        }
    }
    @Test fun changingEntityWrongRangesIgnoredRangesAndTruncationNeverPublishPartialData(): Unit = runBlocking {
        for(mode in listOf("changed","wrong","ignore-part","truncated"))Server(mode).use { server ->
            val file=output()
            try { assertFalse(mode,ParallelRangeDownloader(client,1).download(Request.Builder().url(server.url).build(),file) {_,_->});assertFalse(mode,file.exists()) }
            finally {file.delete()}
        }
    }
    @Test fun explicitDenialAndRateLimitingAreNotSilentlyRetried(): Unit = runBlocking {
        for(mode in listOf("denied","limited"))Server(mode).use { server ->
            val file=output()
            try { val error=runCatching { ParallelRangeDownloader(client,1).download(Request.Builder().url(server.url).build(),file) {_,_->} }.exceptionOrNull()
                assertTrue(mode,error is MediaHttpException);assertFalse(file.exists()) }
            finally {file.delete()}
        }
    }
    @Test fun cancellationInterruptsBlockedWorkersWithoutWaitingForReadTimeout(): Unit = runBlocking {
        Server("blocked").use { server ->
            val file=output();val begin=System.nanoTime()
            try { assertNull(withTimeoutOrNull(300) { ParallelRangeDownloader(client,1).download(Request.Builder().url(server.url).build(),file) {_,_->} })
                assertTrue((System.nanoTime()-begin)<1_500_000_000);assertFalse(file.exists()) }
            finally {file.delete()}
        }
    }
    @Test fun slowRecoverySignalIsPropagatedRatherThanHiddenBySerialFallback(): Unit = runBlocking {
        Server().use { server ->
            val file=output()
            try { assertTrue(TransferPolicy.isSlow(runCatching { ParallelRangeDownloader(client,1).download(Request.Builder().url(server.url).build(),file) {_,_->throw SlowMediaSource()} }.exceptionOrNull())) }
            finally {file.delete()}
        }
    }
}
