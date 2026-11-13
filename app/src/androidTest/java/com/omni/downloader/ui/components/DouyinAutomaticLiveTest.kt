package com.omni.downloader.ui.components

import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.engine.*
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real supplied target. Site verification is recorded as blocked, never extraction success. */
class DouyinAutomaticLiveTest {
    @Test fun actualTargetWithoutManualBrowserActions(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("autoDouyinUrl")
        assumeTrue("Explicit sample required", url != null)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val proxy = args.getString("autoDouyinProxy").orEmpty()
        val previousProxy = NetworkProxy.configuredUrl
        val settings = SettingsRepository.getInstance(context)
        val previousPath = settings.downloadPath.value
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val row = JSONObject().put("success", false).put("manualBrowserActions", 0)
        val taskId = "auto-live-${UUID.randomUUID()}"
        val copy = File(context.cacheDir, "$taskId.mp4")
        var saved: Uri? = null
        val started = System.nanoTime()
        try {
            NetworkProxy.configuredUrl = proxy
            DouyinAutoResolver.invalidate(url!!)
            var firstPreviewMs: Long? = null
            val metadataResult = DownloadEngine.fetchVideoMetadata(context, url, proxy, refreshSources = true,
                onPreview = { if (firstPreviewMs == null) firstPreviewMs = (System.nanoTime() - started) / 1_000_000 })
            val metadataMs = (System.nanoTime() - started) / 1_000_000
            row.put("metadataElapsedMs", metadataMs).put("firstPreviewMs", firstPreviewMs ?: JSONObject.NULL)
            assertTrue("Automatic parsing exceeded the total deadline and cleanup allowance: $metadataMs ms", metadataMs < 18_000)
            val metadata = metadataResult.getOrNull()
            if (metadata == null) {
                val error = metadataResult.exceptionOrNull()!!
                val code = MediaErrors.codeOf(error)
                row.put("status", if (code == MediaErrorCode.NO_MEDIA) "TARGET_IS_IMAGE" else "SITE_OR_NETWORK_REQUIRES_ASSISTANCE").put("errorCode", code.code)
                    .put("error", LogSanitizer.sanitize(error.message.orEmpty()))
                assertTrue("Unexpected implementation error: $error", code in setOf(MediaErrorCode.AUTH_REQUIRED,
                    MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.BROWSER_ASSIST_REQUIRED, MediaErrorCode.NETWORK_ERROR, MediaErrorCode.NO_MEDIA))
                return@runBlocking
            }
            val selected = metadata.availableVideoFormats.first()
            row.put("mediaId", metadata.mediaId).put("width", selected.width).put("height", selected.height)
                .put("dimensionsVerified", selected.dimensionsVerified).put("formats", metadata.availableVideoFormats.size)
                .put("evidence", org.json.JSONArray(metadata.accessEvidence)).put("warning", metadata.qualityWarning)
                .put("metadataDurationMs", metadata.expectedDurationMs).put("sourceDurationMs", selected.sourceDurationMs)
            if (args.getString("autoDouyinDownload") == "true") {
                check(metadata.expectedDurationMs in 1..60_000)
                settings.setDownloadPath("")
                val task = DownloadTask(taskId, metadata.url, "Automatic live QA", downloadType = DownloadType.VIDEO_WITH_AUDIO,
                    mediaId = metadata.mediaId, directMediaUrl = selected.sourceUrl, directMediaHeaders = selected.sourceHeaders,
                    selectedWidth = selected.width, selectedHeight = selected.height,
                    expectedDurationMs = selected.sourceDurationMs.takeIf { it > 0 } ?: metadata.expectedDurationMs)
                saved = Uri.parse(DownloadEngine.executeDownload(context, task, proxy) { _, _, _, _ -> }.getOrThrow())
                context.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                assertNull(MediaVerifier.verifyTask(copy, task, context))
                val actual = MediaVerifier.probeWithFallback(context, copy)!!
                row.put("actualWidth", actual.width).put("actualHeight", actual.height).put("durationMs", actual.durationMs)
                    .put("hasAudio", actual.hasAudio).put("bytes", copy.length())
            }
            row.put("success", true).put("status", "TARGET_EXTRACTED")
        } catch (failure: Exception) {
            row.put("status", "DOWNLOAD_OR_EXTRACTION_FAILED").put("error", LogSanitizer.sanitize(failure.message.orEmpty()))
            File(context.filesDir, "media_tasks/$taskId").walkTopDown().filter { it.isFile && it.extension == "mp4" }
                .firstOrNull()?.let { file -> MediaVerifier.probeWithFallback(context, file)?.let { actual ->
                    row.put("actualWidth", actual.width).put("actualHeight", actual.height).put("durationMs", actual.durationMs)
                } }
            throw failure
        } finally {
            row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            File(context.cacheDir, "douyin-auto-live-result.json").writeText(row.toString())
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_AUTO_LIVE=$row\n") })
            saved?.let { context.contentResolver.delete(it, null, null) }
            copy.delete(); File(context.filesDir, "media_tasks/$taskId").deleteRecursively()
            settings.setDownloadPath(previousPath); NetworkProxy.configuredUrl = previousProxy
            scenario.close()
        }
    }
}
