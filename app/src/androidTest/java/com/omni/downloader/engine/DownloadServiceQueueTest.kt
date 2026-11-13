package com.omni.downloader.engine

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.service.DownloadService
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadServiceQueueTest {
    @Test fun nextBlockingDownloadMustNotHoldTheServiceCommandLock(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context);val oldPath = settings.downloadPath.value;val oldProxy = settings.proxyUrl.value
        settings.setProxyUrl("");settings.setHasPromptedBilibiliLogin(true)
        settings.setDownloadPath(File(context.getExternalFilesDir(null), "service-lock-test").absolutePath)
        val bytes = instrument.context.assets.open("quality-1080.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true);val release = CountDownLatch(1);val blocked = CountDownLatch(1)
        val workers = mutableListOf<Thread>()
        val accept = Thread {
            while (running.get()) try {
                val connection = server.accept()
                val worker = Thread {
                    connection.use {
                        val reader = it.getInputStream().bufferedReader();val line = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        if (line.contains("/blocked.mp4")) { blocked.countDown();release.await(30, TimeUnit.SECONDS) }
                        runCatching { it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (!line.startsWith("HEAD")) write(bytes);flush()
                        } }
                    }
                }.apply { isDaemon = true;start() }
                synchronized(workers) { workers += worker }
            } catch (_: Exception) { }
        }.apply { isDaemon = true;start() }
        val repository = TaskRepository.getInstance(context);repository.awaitLoaded()
        val prefix = "service-lock-${UUID.randomUUID()}"
        fun task(name: String) = DownloadTask("$prefix-$name", "http://127.0.0.1:${server.localPort}/$name.mp4", name,
            downloadType = DownloadType.VIDEO_ONLY, schemaVersion = 3, directMediaUrl = "http://127.0.0.1:${server.localPort}/$name.mp4")
        val first = task("first");val second = task("blocked")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow();repository.addTasks(listOf(first, second))
            instrument.runOnMainSync { DownloadService.startDownload(context, first.id);DownloadService.startDownload(context, second.id) }
            assertTrue("Second download never started", blocked.await(30, TimeUnit.SECONDS))
            assertEquals(TaskStatus.COMPLETED, repository.getTask(first.id)?.status)
            DownloadService.cancelDownload(context, second.id)
            val heartbeat = CountDownLatch(1)
            // Give Android time to deliver the cancel command before posting a UI heartbeat.
            delay(300);Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            val uiResponsive = heartbeat.await(3000, TimeUnit.MILLISECONDS)
            val cancelled = withTimeoutOrNull(3000) {
                while (repository.getTask(second.id)?.status != TaskStatus.CANCELLED) delay(30)
                true
            } ?: false
            if (!uiResponsive || !cancelled) {
                val stacks = Thread.getAllStackTraces().filter { (thread, frames) ->
                    thread.name == "main" || frames.any { it.className.contains("DownloadService") || it.className.contains("DownloadEngine") }
                }.entries.joinToString("\n\n") { (thread, frames) -> "${thread.name} ${thread.state}\n" + frames.joinToString("\n") }
                File(context.getExternalFilesDir(null), "service-lock-threads.txt").writeText(stacks)
            }
            assertTrue("Download worker blocked service commands and the UI thread", uiResponsive)
            assertTrue("Cancellation could not reach the active blocking request", cancelled)
        } finally {
            release.countDown();running.set(false);server.close();accept.join(1000)
            synchronized(workers) { workers.forEach { it.join(1000) } }
            DownloadService.cancelDownload(context, second.id)
            delay(500)
            repository.removeTask(first.id, deleteLocalFile = true);repository.removeTask(second.id, deleteLocalFile = true)
            settings.setDownloadPath(oldPath);settings.setProxyUrl(oldProxy);scenario.close()
        }
    }
}
