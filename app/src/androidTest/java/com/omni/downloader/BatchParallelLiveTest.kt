package com.omni.downloader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.engine.*
import com.omni.downloader.service.DownloadService
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Real user fixtures, real UI entry and downloads. Platform failures are recorded, never treated as success. */
class BatchParallelLiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun runProvidedBatch(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val args = InstrumentationRegistry.getArguments()
        val manual = args.getString("manual") == "true";val all = args.getString("allEpisodes") == "true"
        val name = (if (manual) "manual" else "smart") + (if (all) "-all" else "-single")
        val reportDir = File(context.getExternalFilesDir(null), "batch-1729").apply { mkdirs() }
        val reportFile = File(reportDir, "$name.json")
        val report = JSONObject().put("case", name).put("status", "starting").put("device", android.os.Build.MODEL)
        fun save() { reportFile.writeText(report.toString(2)) }
        val source = instrument.context.assets.open("batch-user-links.txt").bufferedReader().use { it.readText() }.trim().lines()
        val expected = BatchInput.links(source.joinToString(""))
        assertEquals(15, source.size);assertEquals(14, expected.size)
        report.put("supplied", source.size).put("unique", expected.size);save()
        val drafts = InputDraftRepository.getInstance(context);drafts.awaitLoaded()
        val repository = TaskRepository.getInstance(context);repository.awaitLoaded()
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value;val oldProxy = settings.proxyUrl.value
        settings.setHasPromptedBilibiliLogin(true);settings.setAppLanguage("zh-CN");settings.setThemeMode("light")
        args.getString("proxy")?.let { settings.setProxyUrl(it) }
        settings.setDownloadPath(File(reportDir, "downloads-$name").absolutePath)
        drafts.update { InputDraft(batchManual = manual, batchOptions = BatchOptions(allEpisodes = all)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: BatchDownloads
        val started = SystemClock.elapsedRealtime()
        var finished = false
        try {
            scenario.onActivity { controller = ViewModelProvider(it)[MainViewModel::class.java].batchDownloads }
            instrument.runOnMainSync { controller.clearHistory() }
            withTimeout(10000) { while (controller.queue.plan.value.items.isNotEmpty()) delay(100) }
            delay(300)
            compose.onNodeWithContentDescription("批量", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("batch_all_episodes").assertIsToggleable().assert(if (all) isOn() else isOff())
            if (manual) {
                source.forEach { url ->
                    compose.onNodeWithTag("batch_input").performTextReplacement(url)
                    compose.onNodeWithTag("batch_add").performClick()
                }
            } else {
                compose.onNodeWithTag("batch_input").performTextReplacement(source.joinToString(""))
                compose.onNodeWithTag("batch_add").performClick()
            }
            compose.runOnIdle { assertEquals(expected, drafts.draft.value.batchLinks) }
            drafts.awaitSaved()
            report.put("inputVerified", true).put("status", "parsing");save()
            compose.onNodeWithTag("batch_start").performScrollTo().performClick()
            val parseDeadline = SystemClock.elapsedRealtime() + args.getString("parseTimeoutSec", "900")!!.toLong() * 1000
            withTimeout(10000) { while (controller.queue.plan.value.items.size != 14) delay(100) }
            var peak = 0
            do {
                val plan = controller.queue.plan.value
                peak = maxOf(peak, plan.items.count { it.status == BatchStatus.RESOLVING })
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("peakResolvers", peak)
                report.put("rows", JSONArray().apply { plan.items.forEachIndexed { i, row ->
                    put(JSONObject().put("index", i + 1).put("platform", LinkNormalizer.platformOf(row.url))
                        .put("state", row.status.name).put("note", LogSanitizer.sanitize(row.note)).put("tasks", row.taskIds.size))
                } });save()
                if (SystemClock.elapsedRealtime() > parseDeadline) {
                    report.put("parseDeadlineExceeded", true)
                    instrument.runOnMainSync { controller.cancel() }
                    withTimeout(30000) { while (controller.queue.running.value) delay(100) }
                    break
                }
                delay(500)
            } while (controller.queue.running.value)
            val plan = controller.queue.plan.value
            assertEquals(expected, plan.items.map { it.url })
            assertTrue(plan.items.none { it.status in setOf(BatchStatus.PENDING, BatchStatus.RESOLVING) })
            report.put("rows", JSONArray().apply { plan.items.forEachIndexed { i, row ->
                put(JSONObject().put("index", i + 1).put("platform", LinkNormalizer.platformOf(row.url))
                    .put("state", row.status.name).put("note", LogSanitizer.sanitize(row.note)).put("tasks", row.taskIds.size))
            } })
            report.put("parseMs", SystemClock.elapsedRealtime() - started).put("status", "downloading");save()
            val ids = plan.items.flatMap { it.taskIds }.distinct()
            assertEquals(plan.items.sumOf { it.taskIds.size }, ids.size)
            val deadline = SystemClock.elapsedRealtime() + args.getString("downloadTimeoutSec", "900")!!.toLong() * 1000
            val active = setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PROCESSING)
            val observed = mutableMapOf<String, Pair<String, Long>>()
            val taskStarted = mutableMapOf<String, Long>()
            val cancelledByDeadline = mutableMapOf<String, String>()
            report.put("stallTimeoutMs", 180000).put("perTaskTimeoutMs", 600000)
            while (ids.any { repository.getTask(it)?.status in active } && SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                for (id in ids) {
                    val task = repository.getTask(id) ?: continue
                    if (task.status !in setOf(TaskStatus.DOWNLOADING, TaskStatus.PROCESSING)) continue
                    val began = taskStarted.getOrPut(id) { now }
                    val signature = "${task.status}:${task.progress}:${task.speedText}:${task.etaText}"
                    val previous = observed[id]
                    if (previous == null || previous.first != signature) observed[id] = signature to now
                    if (id !in cancelledByDeadline && (now - observed.getValue(id).second > 180000 || now - began > 600000)) {
                        cancelledByDeadline[id] = if (now - began > 600000) "per-task test deadline" else "no-progress test deadline"
                        DownloadService.cancelDownload(context, id)
                    }
                }
                report.put("elapsedMs", now - started)
                    .put("downloadCompleted", ids.count { repository.getTask(it)?.status == TaskStatus.COMPLETED })
                    .put("downloadActive", ids.count { repository.getTask(it)?.status in active })
                    .put("liveTasks", JSONArray().apply { ids.forEach { id -> repository.getTask(id)?.let { task ->
                        put(JSONObject().put("inputIndex", plan.items.indexOfFirst { id in it.taskIds } + 1)
                            .put("state", task.status.name).put("progress", task.progress).put("eta", task.etaText))
                    } } });save();delay(1000)
            }
            val taskReports = JSONArray()
            for (id in ids) {
                val task = repository.getTask(id) ?: error("Missing submitted task")
                val result = JSONObject().put("id", id).put("inputIndex", plan.items.indexOfFirst { id in it.taskIds } + 1)
                    .put("state", task.status.name).put("type", task.downloadType.name)
                    .put("resolution", task.selectedResolution).put("error", LogSanitizer.sanitize(task.errorMessage).takeLast(1800))
                cancelledByDeadline[id]?.let { result.put("testDeadlineExceeded", it) }
                if (task.status == TaskStatus.COMPLETED) {
                    val path = task.localFilePath
                    val bytes = if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))!!.use { stream ->
                        var total = 0L;val buffer = ByteArray(65536)
                        while (true) { val n = stream.read(buffer);if (n < 0) break;total += n };total
                    } else File(path).length()
                    result.put("bytes", bytes);assertTrue("Empty completed output", bytes > 0)
                    if (!path.startsWith("content://")) {
                        val file = File(path)
                        if (task.downloadType == DownloadType.IMAGE_ZIP) {
                            ZipFile(file).use { zip ->
                                assertTrue(zip.size() > 0)
                                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry -> zip.getInputStream(entry).use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
                                result.put("zipEntries", zip.size())
                            }
                        } else if (MediaVerifier.isVideoFile(file)) {
                            val probe = MediaVerifier.probe(file);assertNotNull("Completed video must be playable", probe)
                            result.put("width", probe!!.width).put("height", probe.height).put("durationMs", probe.durationMs).put("hasAudio", probe.hasAudio)
                        }
                    }
                    result.put("fileVerified", true)
                }
                if (task.status in active) { result.put("testDeadlineExceeded", true);DownloadService.cancelDownload(context, id) }
                taskReports.put(result)
            }
            report.remove("liveTasks")
            report.put("downloadCompleted", ids.count { repository.getTask(it)?.status == TaskStatus.COMPLETED })
                .put("downloadActive", ids.count { repository.getTask(it)?.status in active })
            report.put("tasks", taskReports).put("elapsedMs", SystemClock.elapsedRealtime() - started).put("status", "recorded")
                .put("queuedRows", plan.items.count { it.status == BatchStatus.QUEUED }).put("failedRows", plan.items.count { it.status == BatchStatus.FAILED })
            save()
            val encrypted = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE).getString("plan", "")!!
            assertFalse(encrypted.contains("https://"))
            // Exercise the real clear button/dialog; clearing history must not delete drafts or download tasks.
            compose.onNodeWithTag("batch_screen").performScrollToNode(hasTestTag("batch_clear_history"))
            compose.onNodeWithTag("batch_clear_history").performClick()
            compose.onNodeWithTag("batch_confirm_clear_history").performClick()
            withTimeout(10000) { while (controller.queue.plan.value.items.isNotEmpty()) delay(100) }
            assertEquals(expected, drafts.draft.value.batchLinks)
            assertTrue(ids.all { repository.getTask(it) != null })
            report.put("clearHistoryPreservesDraftsAndTasks", true);save()
            finished = true
        } finally {
            controller.cancel()
            if (!finished) { report.put("status", "test_failed");save() }
            settings.setDownloadPath(oldPath);settings.setProxyUrl(oldProxy);scenario.close()
        }
    }
}
