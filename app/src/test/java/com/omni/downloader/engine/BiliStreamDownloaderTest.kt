package com.omni.downloader.engine

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class BiliStreamDownloaderTest {
    @Test fun prefersAdvertisedStandardCdnWithoutRewritingSignedUrls() {
        val primary = "https://xy1.mcdn.bilivideo.cn:8082/a?token=exact%2Bvalue"
        val standard = "https://upos-sz.bilivideo.com/a?token=unchanged"
        assertEquals(listOf(standard, primary), BiliStreamUrls.ordered(primary, listOf(standard, primary, "https://name:secret@example.org/a")))
    }
    @Test fun incorrectRangesAndTruncatedBodiesFallBackToTheCompleteTrack() = runBlocking {
        val bytes = ByteArray(41) { it.toByte() }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true);val leakedCookie = AtomicBoolean(false)
        val thread = Thread {
            while (running.get()) try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader();val line = reader.readLine().orEmpty()
                val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                if (headers.any { it.startsWith("Cookie:", true) }) leakedCookie.set(true)
                val offset = headers.firstOrNull { it.startsWith("Range:", true) }!!.substringAfter("bytes=").substringBefore('-').toInt()
                val end = minOf(offset + 7, bytes.lastIndex)
                val wrong = line.contains("wrong");val truncated = line.contains("truncated")
                val from = if (wrong) offset + 1 else offset
                val body = bytes.copyOfRange(offset, end + 1)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$end/${bytes.size}\r\nContent-Length: ${body.size}\r\nETag: \"same-track\"\r\nConnection: close\r\n\r\n".toByteArray())
                    write(if (truncated) body.copyOf(1) else body);flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val output = File.createTempFile("bili-track-", ".bin")
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            BiliStreamDownloader(OkHttpClient(), 8).download("$base/wrong", listOf("$base/truncated", "$base/ok"), output, mapOf("Cookie" to "SESSDATA=must-not-leak")) { _,_,_ -> }
            assertArrayEquals(bytes, output.readBytes());assertFalse(leakedCookie.get())
        } finally { output.delete();running.set(false);server.close();thread.join(1000) }
    }
    @Test fun cancellingBlockedMediaBodyClosesTheCallAndDoesNotTryABackup() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try { server.accept().use { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().apply { write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-999/1000\r\nContent-Length: 1000\r\n\r\n".toByteArray());flush() }
                Thread.sleep(3000)
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val output = File.createTempFile("bili-cancel-", ".bin")
        try {
            val start = System.nanoTime();val url = "http://127.0.0.1:${server.localPort}/slow"
            assertNull(withTimeoutOrNull(200) { BiliStreamDownloader(OkHttpClient()).download(url, listOf("$url/backup"), output, emptyMap()) { _,_,_ -> }; true })
            assertTrue(System.nanoTime() - start < 1_500_000_000L)
            assertFalse(output.exists())
        } finally { output.delete();server.close();thread.interrupt();thread.join(1000) }
    }
    @Test fun cancellingBiliApiDoesNotWaitForTheSocketReadTimeout() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread { try { server.accept().use { Thread.sleep(3000) } } catch (_: Exception) { } }.apply { isDaemon = true;start() }
        try {
            val start = System.nanoTime()
            assertNull(withTimeoutOrNull(200) { BiliHttp.read(OkHttpClient(), Request.Builder().url("http://127.0.0.1:${server.localPort}").build()) { it.code } })
            assertTrue(System.nanoTime() - start < 1_500_000_000L)
        } finally { server.close();thread.interrupt();thread.join(1000) }
    }
}
