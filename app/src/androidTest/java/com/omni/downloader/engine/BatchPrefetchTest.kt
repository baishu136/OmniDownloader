package com.omni.downloader.engine

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BatchPrefetchTest {
    @Test fun addPreparesOnlyExplicitStartDownloadsAndCompletionRemovesOnlyThatLink() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation(); val context = instrument.targetContext
        val repository = TaskRepository.getInstance(context); repository.awaitLoaded()
        val before = repository.tasks.value.map { it.id }.toSet()
        val drafts = InputDraftRepository(context); drafts.awaitLoaded(); val savedDraft = drafts.draft.value
        val prefs = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE); val savedPlan = prefs.getString("plan", null)
        val settings = SettingsRepository.getInstance(context); val savedProxy = settings.proxyUrl.value; val savedPath = settings.downloadPath.value
        settings.setProxyUrl(""); settings.setDownloadPath("")
        prefs.edit().putString("plan", SessionCrypto.encrypt(Json.encodeToString(BatchPlan()))).commit()
        drafts.update { InputDraft(batchEntry = "unadded draft", batchManual = true) }; drafts.awaitSaved()
        val fixture = instrument.context.assets.list("batch-source")!!.associateWith { name -> instrument.context.assets.open("batch-source/$name").use { it.readBytes() } }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); val alive = AtomicBoolean(true); val requests = AtomicInteger()
        val thread = Thread {
            while (alive.get()) try { server.accept().use { connection ->
                connection.soTimeout = 5000
                val reader = connection.getInputStream().bufferedReader(); val line = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                val path = line.split(' ').getOrNull(1).orEmpty().substringAfterLast('/')
                if (path == "a.m3u8") requests.incrementAndGet()
                val body = when (path) {
                    "a.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"\nmedia.m3u8\n".toByteArray()
                    else -> fixture[path]
                }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 ${if (body == null) "404 Not Found" else "200 OK"}\r\nContent-Type: ${if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"}\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!line.startsWith("HEAD") && body != null) write(body)
                    flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = BatchDownloads(context, scope, drafts)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            withTimeout(5000) { while (!controller.ready.value) delay(20) }
            val url = "http://127.0.0.1:${server.localPort}/a.m3u8"
            val missing = "http://127.0.0.1:${server.localPort}/missing.m3u8"
            instrument.runOnMainSync { controller.addLinks(url, true) }
            withTimeout(60000) { while (controller.preparationStates.value[url]?.status !in setOf(BatchPreparationStatus.READY, BatchPreparationStatus.FAILED)) delay(50) }
            assertEquals(controller.preparationStates.value.toString(), BatchPreparationStatus.READY, controller.preparationStates.value[url]?.status)
            assertTrue(requests.get() > 0)
            assertEquals(before, repository.tasks.value.map { it.id }.toSet())
            assertTrue(drafts.draft.value.batchTasks.isEmpty())
            val oldIdentity = drafts.draft.value.batchInputIds[url]
            instrument.runOnMainSync { controller.removeLink(url); controller.addLinks(url, true) }
            assertNotEquals(oldIdentity, drafts.draft.value.batchInputIds[url])
            withTimeout(60000) { while (controller.preparationStates.value[url]?.status !in setOf(BatchPreparationStatus.READY, BatchPreparationStatus.FAILED)) delay(50) }
            assertEquals(BatchPreparationStatus.READY, controller.preparationStates.value[url]?.status)
            assertEquals(before, repository.tasks.value.map { it.id }.toSet())
            instrument.runOnMainSync { controller.addLinks(missing, true) }
            withTimeout(45000) { while (controller.preparationStates.value[missing]?.status != BatchPreparationStatus.FAILED) delay(50) }
            instrument.runOnMainSync { controller.start("$url\n$missing", BatchOptions(), true) }
            withTimeout(90000) { while (url in drafts.draft.value.batchLinks) delay(50) }
            withTimeout(45000) { while (controller.queue.running.value) delay(50) }
            val tasks = repository.tasks.value.filter { it.id !in before }
            assertEquals(1, tasks.size); assertEquals(tasks.single().errorMessage, TaskStatus.COMPLETED, tasks.single().status)
            assertTrue(tasks.single().localFilePath.isNotBlank())
            assertEquals(listOf(missing), drafts.draft.value.batchLinks)
            assertFalse(url in drafts.draft.value.batchTasks)
            assertFalse(url in drafts.draft.value.batchInputIds)
            assertFalse(url in controller.preparationStates.value)
            assertTrue(controller.queue.plan.value.items.first { it.url == url }.inputRetired)
            instrument.runOnMainSync { controller.removeLink(missing) }
            assertTrue(drafts.draft.value.batchLinks.isEmpty()); assertTrue(controller.preparationStates.value.isEmpty())
            assertFalse(controller.queue.plan.value.items.any { it.url == missing })
            assertEquals(TaskStatus.COMPLETED, repository.getTask(tasks.single().id)?.status)
        } finally {
            controller.close(); scope.cancel(); scenario.close(); alive.set(false); server.close(); thread.join(1000)
            repository.tasks.value.filter { it.id !in before }.forEach { repository.removeTask(it.id, deleteLocalFile = true) }
            drafts.update { savedDraft }; drafts.awaitSaved()
            prefs.edit().apply { if (savedPlan == null) remove("plan") else putString("plan", savedPlan) }.commit()
            settings.setProxyUrl(savedProxy); settings.setDownloadPath(savedPath)
        }
    }

    @Test fun collectionCompletionStillPrunesAfterHistoryClearAndDraftReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TaskRepository.getInstance(context); repository.awaitLoaded()
        val drafts = InputDraftRepository(context); drafts.awaitLoaded(); val savedDraft = drafts.draft.value
        val prefs = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE); val savedPlan = prefs.getString("plan", null)
        val prefix = "prefetch-test-${UUID.randomUUID()}"; val url = "https://example.org/$prefix"
        val ids = listOf("$prefix-1", "$prefix-2")
        repository.addTasks(ids.map { DownloadTask(it, url, "fixture", "", "", DownloadType.VIDEO_WITH_AUDIO) })
        repository.markCompleted(ids[0], "fixture-completed-episode-1")
        repository.markFailed(ids[1], "fixture network failure")
        drafts.update { InputDraft(home = "home retained", batchText = "unadded text", batchEntry = "unadded entry",
            batchLinks = listOf(url), batchTasks = mapOf(url to BatchLinkTasks(prefix, ids))) }; drafts.awaitSaved()
        prefs.edit().putString("plan", SessionCrypto.encrypt(Json.encodeToString(BatchPlan(listOf(BatchItem(prefix, url, BatchStatus.QUEUED, taskIds = ids)))))).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var controller = BatchDownloads(context, scope, drafts)
        try {
            withTimeout(5000) { while (controller.queue.plan.value.items.isEmpty()) delay(20) }
            controller.clearHistory()
            withTimeout(5000) { while (controller.queue.plan.value.items.isNotEmpty()) delay(20) }
            assertEquals(listOf(url), drafts.draft.value.batchLinks)
            assertEquals(ids, drafts.draft.value.batchTasks[url]?.taskIds)
            drafts.awaitSaved(); controller.close()
            val reloaded = InputDraftRepository(context); reloaded.awaitLoaded()
            assertEquals(ids, reloaded.draft.value.batchTasks[url]?.taskIds)
            assertEquals(prefix, reloaded.draft.value.batchInputIds[url])
            controller = BatchDownloads(context, scope, reloaded)
            repository.markCompleted(ids[1], "fixture-completed-episode-2")
            withTimeout(5000) { while (reloaded.draft.value.batchLinks.isNotEmpty()) delay(20) }
            assertTrue(reloaded.draft.value.batchTasks.isEmpty())
            assertTrue(reloaded.draft.value.batchInputIds.isEmpty())
            assertEquals("home retained", reloaded.draft.value.home)
            assertEquals("unadded text", reloaded.draft.value.batchText)
            assertEquals("unadded entry", reloaded.draft.value.batchEntry)
            assertTrue(ids.all { repository.getTask(it)?.status == TaskStatus.COMPLETED })
            reloaded.awaitSaved()
        } finally {
            controller.close(); scope.cancel(); ids.forEach { repository.removeTask(it) }
            drafts.update { savedDraft }; drafts.awaitSaved()
            prefs.edit().apply { if (savedPlan == null) remove("plan") else putString("plan", savedPlan) }.commit()
        }
    }
}
