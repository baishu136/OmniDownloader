package com.omni.downloader.engine

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallelMediaDownloadTest {
    private fun run(changed: Boolean) = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation(); val context = instrument.targetContext
        val original = instrument.context.assets.open("verification.mp4").use { it.readBytes() }
        val freeSize = 9 * 1024 * 1024
        val payload = original + ByteBuffer.allocate(freeSize).putInt(freeSize).put("free".toByteArray()).array()
        val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        val active = AtomicInteger(); val peak = AtomicInteger(); val ranges = AtomicInteger(); val full = AtomicInteger()
        pool.execute { while (!server.isClosed) try {
            val socket = server.accept()
            pool.execute { socket.use { connection -> try {
                connection.soTimeout = 5000
                val reader = connection.getInputStream().bufferedReader(); reader.readLine()
                val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                val from = range?.get(0)?.toInt() ?: 0
                val to = range?.get(1)?.takeIf { it.isNotBlank() }?.toInt() ?: payload.lastIndex
                val probe = range != null && from == 0 && to == 0
                val part = range != null && !probe
                if (range == null) full.incrementAndGet()
                if (part) { ranges.incrementAndGet(); val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) } }
                val tag = if (changed && part) "\"changed\"" else "\"original\""
                val output = connection.getOutputStream()
                output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\nContent-Length: ${to-from+1}\r\nETag: $tag\r\n"+
                    (if (range == null) "" else "Content-Range: bytes $from-$to/${payload.size}\r\n")+"Connection: close\r\n\r\n").toByteArray()); output.flush()
                try {
                    var offset = from
                    while (offset <= to) {
                        val count = minOf(64*1024, to-offset+1)
                        if (!probe) Thread.sleep(3)
                        output.write(payload, offset, count); output.flush(); offset += count
                    }
                } finally { if (part) active.decrementAndGet() }
            } catch (_: Exception) { } } }
        } catch (_: Exception) { } }
        val settings = SettingsRepository.getInstance(context); val previous = settings.downloadPath.value
        val oldProxy = NetworkProxy.configuredUrl
        val id = "parallel-device-${UUID.randomUUID()}"
        var saved: Uri? = null
        try {
            NetworkProxy.configuredUrl = ""; settings.setDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val task = DownloadTask(id, "http://127.0.0.1:${server.localPort}/fixture.mp4", "parallel fixture", "", "", DownloadType.VIDEO_WITH_AUDIO,
                selectedWidth = 320, selectedHeight = 180, requireAudio = true, expectedDurationMs = 1000)
            val result = DownloadEngine.executeDownload(context, task) { _,_,_,_ -> }.getOrThrow()
            saved = Uri.parse(result)
            val output = context.contentResolver.openInputStream(saved!!)!!.use { it.readBytes() }
            assertArrayEquals(payload, output)
            assertTrue(ranges.get() >= if (changed) 1 else 2)
            if (changed) assertTrue(full.get() >= 2) else assertTrue("peak=${peak.get()}", peak.get() in 2..4)
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            settings.setDownloadPath(previous); NetworkProxy.configuredUrl = oldProxy
            server.close(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS)
            File(context.filesDir, "media_tasks/$id").deleteRecursively()
        }
    }
    @Test fun parallelDownloadExportsTheExactPlayableFile(): Unit = run(false)
    @Test fun entityChangeFallsBackToSerialAndNeverExportsMixedBytes(): Unit = run(true)
}
