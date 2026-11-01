package com.omni.downloader

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.ui.MainActivity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Same interaction script can be run against the previous APK for comparison.
 * Fixtures are synthetic and local: no accounts, public downloads, or network dependencies.
 */
class AppInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun repeatedTabsAndScrollingWithLargeHistoryAndLiveProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("key_has_prompted_bilibili_login", true)
            .putBoolean("key_has_shown_relay_compat_tip", true)
            .putInt("key_app_config_version", 40).putString("key_app_language", "en").commit()
        val cover = File(context.filesDir, "perf_cover.jpg")
        val bitmap = Bitmap.createBitmap(184, 116, Bitmap.Config.RGB_565)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        val tasks = (0 until 700).map { index ->
            DownloadTask("perf-" + index, "https://example.org/" + index, "Performance task " + index,
                thumbnailUrl = cover.path, downloadType = DownloadType.VIDEO_WITH_AUDIO,
                status = TaskStatus.COMPLETED, progress = 100f,
                collectionId = if (index < 300) null else "collection-" + index / 40,
                episodeIndex = index % 40 + 1)
        }
        File(context.filesDir, "download_tasks_v1.json").writeText(Json.encodeToString(tasks))
        val arguments = InstrumentationRegistry.getArguments()
        val siteCount = arguments.getString("siteCount")?.toInt() ?: 80
        val pauseMs = arguments.getString("pauseMs")?.toLong() ?: 0L
        val sites = (0 until siteCount).map { RelaySite(id = "perf-site-" + it, name = "Local relay " + it,
            url = "https://example.org/" + it, iconUrl = cover.path) }
        prefs.edit().putString("key_relay_sites", Json.encodeToString(sites)).commit()
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val uiSamples = Collections.synchronizedList(mutableListOf<Long>())
        val measureSamples = Collections.synchronizedList(mutableListOf<Long>())
        val handler = HandlerThread("PerfFrames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            measureSamples.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
            uiSamples.add(metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + metrics.getMetric(FrameMetrics.DRAW_DURATION))
        }
        val running = AtomicBoolean(true)
        var progressThread: Thread? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitForIdle()
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(handler.looper)) }
            val repo = TaskRepository.getInstance(context)
            repo.addTask(DownloadTask("live", "https://example.org/live", "Local live task",
                downloadType = DownloadType.VIDEO_WITH_AUDIO, status = TaskStatus.DOWNLOADING))
            progressThread = Thread {
                var n = 0
                while (running.get()) {
                    repo.updateTaskProgress("live", (n++ % 100).toFloat(), "2 MiB/s", "10s", TaskStatus.DOWNLOADING)
                    try { Thread.sleep(80) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
            // Prime each page before collecting the steady-state interaction sample.
            listOf("Tasks", "Settings", "Home").forEach { label ->
                compose.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
                compose.waitForIdle()
            }
            samples.clear()
            uiSamples.clear()
            measureSamples.clear()
            repeat(4) {
                listOf("Tasks", "Settings", "Home").forEach { label ->
                    compose.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
                    compose.waitForIdle()
                    if (pauseMs > 0) Thread.sleep(pauseMs)
                    repeat(2) {
                        compose.onRoot().performTouchInput { swipeUp(durationMillis = 250) }
                        compose.waitForIdle()
                        if (pauseMs > 0) Thread.sleep(pauseMs)
                    }
                }
            }
            assertTrue("App produced no frames", samples.isNotEmpty())
            val sorted = synchronized(samples) { samples.sorted() }
            fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
            val uiSorted = synchronized(uiSamples) { uiSamples.sorted() }
            val measureSorted = synchronized(measureSamples) { measureSamples.sorted() }
            val uiP95 = uiSorted[((uiSorted.size - 1) * 0.95).toInt()] / 1_000_000.0
            val measureP95 = measureSorted[((measureSorted.size - 1) * 0.95).toInt()] / 1_000_000.0
            val result = "frames=" + sorted.size + " p50Ms=" + percentile(0.5) + " p95Ms=" + percentile(0.95) +
                " uiP95Ms=" + uiP95 + " measureP95Ms=" + measureP95 + " over32ms=" + sorted.count { it > 32_000_000L } + " over100ms=" + sorted.count { it > 100_000_000L }
            Log.i("OmniPerf", result)
            println("OMNI_PERF " + result)
        } finally {
            running.set(false)
            progressThread?.interrupt()
            progressThread?.join(2000)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            scenario.close()
            handler.quitSafely()
        }
    }
}
