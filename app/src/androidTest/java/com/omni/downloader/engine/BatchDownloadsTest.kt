package com.omni.downloader.engine

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class BatchDownloadsTest {
    @Test fun cancellingBlockedMetadataDoesNotStartTheNextLink() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        SettingsRepository.getInstance(context).setProxyUrl("")
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true);val requested = AtomicBoolean(false)
        val thread = Thread {
            try { server.accept().use { connection ->
                connection.soTimeout = 5000
                connection.getInputStream().bufferedReader().readLine()
                requested.set(true)
                while (running.get()) Thread.sleep(50)
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = BatchDownloads(context, scope)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val base = "http://127.0.0.1:${server.localPort}"
            instrument.runOnMainSync { controller.start("$base/blocked.m3u8\n$base/next.m3u8", BatchOptions()) }
            withTimeout(45000) { while (!requested.get()) delay(50) }
            instrument.runOnMainSync { controller.cancel() }
            withTimeout(10000) { while (controller.queue.running.value) delay(50) }
            assertEquals(listOf(BatchStatus.CANCELLED, BatchStatus.CANCELLED), controller.queue.plan.value.items.map { it.status })
            assertTrue(controller.queue.plan.value.items.all { it.taskIds.isEmpty() })
        } finally {
            controller.cancel();scope.cancel();running.set(false);server.close();thread.join(1000);scenario.close()
        }
    }

    @Test fun mixedLinksSubmitOnceDownloadInBackgroundAndRestoreEncryptedPlan() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context);val oldPath = settings.downloadPath.value
        settings.setProxyUrl("");settings.setDownloadPath("")
        val fixture = instrument.context.assets.list("batch-source")!!.associateWith { name ->
            instrument.context.assets.open("batch-source/$name").use { it.readBytes() }
        }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));val running = AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) try { server.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader();val line = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                val path = line.split(' ').getOrNull(1).orEmpty().substringAfterLast('/')
                val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"\nmedia.m3u8\n".toByteArray()
                val body = when (path) { "a.m3u8", "b.m3u8" -> master; else -> fixture[path] }
                val status = if (body == null) "404 Not Found" else "200 OK"
                val mime = if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!line.startsWith("HEAD") && body != null) write(body)
                    flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = BatchDownloads(context, scope)
        val repository = TaskRepository.getInstance(context);repository.awaitLoaded()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val base = "http://127.0.0.1:${server.localPort}"
            instrument.runOnMainSync { controller.start("$base/a.m3u8\n$base/missing.m3u8\n$base/b.m3u8\n$base/a.m3u8", BatchOptions()) }
            instrument.uiAutomation.executeShellCommand("input keyevent 3").use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }
            withTimeout(90000) {
                while (controller.queue.plan.value.items.size != 3 || controller.queue.running.value || controller.queue.plan.value.items.any { it.status in setOf(BatchStatus.PENDING, BatchStatus.RESOLVING) }) delay(100)
            }
            val ids = controller.queue.plan.value.items.flatMap { it.taskIds }
            assertTrue("Successful links did not enter the task queue: ${controller.queue.plan.value.items.map { it.note }}", ids.size >= 2)
            withTimeout(90000) { while (ids.any { repository.getTask(it)?.status in setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PROCESSING) }) delay(100) }
            assertEquals(ids.map { repository.getTask(it)?.errorMessage }.toString(), 2, ids.count { repository.getTask(it)?.status == TaskStatus.COMPLETED })
            val stored = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE).getString("plan", "")!!
            assertFalse(stored.contains("127.0.0.1"))
            val recovered = BatchDownloads(context, scope)
            withTimeout(5000) { while (recovered.queue.plan.value.items.isEmpty()) delay(50) }
            assertEquals(controller.queue.plan.value.items.map { it.id }, recovered.queue.plan.value.items.map { it.id })
            // A process can die between saving a batch row and the task repository.
            val removed = ids.first()
            repository.removeTask(removed, deleteLocalFile = true)
            val reconciled = BatchDownloads(context, scope)
            withTimeout(5000) { while (reconciled.queue.plan.value.items.isEmpty()) delay(50) }
            assertEquals(BatchStatus.CANCELLED, reconciled.queue.plan.value.items.first { removed in it.taskIds }.status)
            assertTrue(reconciled.queue.plan.value.items.any { it.status == BatchStatus.QUEUED })
        } finally {
            controller.cancel();scope.cancel()
            controller.queue.plan.value.items.flatMap { it.taskIds }.forEach { repository.removeTask(it, deleteLocalFile = true) }
            settings.setDownloadPath(oldPath);scenario.close();running.set(false);server.close();thread.join(1000)
        }
    }
}
