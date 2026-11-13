package com.omni.downloader

import android.os.SystemClock
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Retry failures from the four real UI runs through the production retry action, retaining task identities. */
class BatchDownloadRetest {
    @Test fun retryRecordedFailuresWithTheFixedTemporaryDirectory(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val repository = TaskRepository.getInstance(context);repository.awaitLoaded()
        val settings = SettingsRepository.getInstance(context);val oldPath = settings.downloadPath.value;val oldProxy = settings.proxyUrl.value
        settings.setHasPromptedBilibiliLogin(true);settings.setProxyUrl("http://10.0.2.2:7890")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var viewModel: MainViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it)[MainViewModel::class.java] }
        val folder = File(context.getExternalFilesDir(null), "batch-1729")
        try {
            for (case in listOf("smart-single", "smart-all", "manual-single", "manual-all")) {
                val file = File(folder, "$case.json");val report = JSONObject(file.readText())
                assertEquals("recorded", report.getString("status"))
                report.put("postFixRetestCompleted", false).put("retestRunning", true)
                val entries = report.getJSONArray("tasks");val started = SystemClock.elapsedRealtime();var retries = 0
                settings.setDownloadPath(File(folder, "downloads-$case").absolutePath)
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    if (entry.getString("state") == "COMPLETED") continue
                    val id = entry.getString("id");val original = repository.getTask(id) ?: error("Missing original task")
                    if (!entry.has("initialState")) entry.put("initialState", entry.getString("state")).put("initialError", entry.optString("error"))
                    if (entry.optBoolean("retestDeadlineExceeded")) entry.put("earlierRetestDeadlineExceeded", true)
                    entry.remove("retestDeadlineExceeded")
                    entry.put("retested", true);retries++
                    val active = setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PROCESSING)
                    instrument.runOnMainSync { viewModel.retryTask(context, id) }
                    var signature = "";var lastProgress = SystemClock.elapsedRealtime()
                    val deadline = SystemClock.elapsedRealtime() + 7200000
                    var lastSave = 0L
                    while (repository.getTask(id)?.status in active) {
                        val task = repository.getTask(id)!!;val now = SystemClock.elapsedRealtime()
                        val next = "${task.status}:${task.progress}:${task.speedText}:${task.etaText}"
                        if (next != signature) { signature = next;lastProgress = now }
                        if (now > deadline || now - lastProgress > 180000) {
                            entry.put("retestDeadlineExceeded", true);DownloadService.cancelDownload(context, id)
                            withTimeout(10000) { while (repository.getTask(id)?.status in active) delay(100) }
                            break
                        }
                        if (now - lastSave > 5000) {
                            report.put("retestProgress", JSONObject().put("inputIndex", entry.getInt("inputIndex"))
                                .put("state", task.status.name).put("progress", task.progress).put("speed", task.speedText)
                                .put("eta", task.etaText).put("elapsedMs", now - started))
                            file.writeText(report.toString(2));lastSave = now
                        }
                        delay(250)
                    }
                    val task = repository.getTask(id)!!
                    assertEquals(original.mediaId, task.mediaId);assertEquals(original.url, task.url)
                    if (task.status == TaskStatus.FAILED) assertFalse("Private temporary-directory regression", task.errorMessage.contains("Read-only file system"))
                    entry.put("state", task.status.name).put("error", LogSanitizer.sanitize(task.errorMessage).takeLast(1800))
                    if (task.status == TaskStatus.COMPLETED) {
                        val output = File(task.localFilePath);assertTrue(output.isFile && output.length() > 0)
                        entry.put("bytes", output.length())
                        if (task.downloadType == DownloadType.IMAGE_ZIP) {
                            ZipFile(output).use { zip -> assertTrue(zip.size() > 0);entry.put("zipEntries", zip.size()) }
                        } else if (MediaVerifier.isVideoFile(output)) {
                            val probe = MediaVerifier.probe(output);assertNotNull(probe)
                            entry.put("width", probe!!.width).put("height", probe.height).put("durationMs", probe.durationMs).put("hasAudio", probe.hasAudio)
                        }
                        entry.put("fileVerified", true)
                    }
                    report.put("retestedTasks", retries).put("retestElapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("retestVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    file.writeText(report.toString(2))
                }
                assertEquals(14, report.getJSONArray("rows").length())
                report.remove("liveTasks");report.remove("retestProgress")
                report.put("retestRunning", false)
                report.put("downloadCompleted", (0 until entries.length()).count { entries.getJSONObject(it).getString("state") == "COMPLETED" })
                    .put("downloadActive", 0).put("postFixRetestCompleted", true)
                file.writeText(report.toString(2))
            }
        } finally { settings.setDownloadPath(oldPath);settings.setProxyUrl(oldProxy);scenario.close() }
    }
}
