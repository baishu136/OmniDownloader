package com.omni.downloader.engine

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit user-supplied live samples only. Large originals are metadata-only. */
class SocialVideoAcceptanceTest {
    @Test fun inspectOrDownloadAuthorizedSample(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("socialUrl")
        assumeTrue("Opt-in live sample required", url != null)
        val download = args.getString("socialDownload") == "true"
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val oldProxy = NetworkProxy.configuredUrl
        val proxy = args.getString("socialProxy").orEmpty()
        val id = "social-qa-${UUID.randomUUID()}"
        val copy = File(context.cacheDir, "$id.mp4")
        var saved: Uri? = null
        val result = JSONObject().put("downloadRequested", download).put("success", false)
        try {
            NetworkProxy.configuredUrl = proxy
            val capturedHtml = args.getString("socialCapturedHtml")
            val capturedItem = args.getString("socialCapturedItem")
            val metadata = if (capturedItem != null) {
                val file = File(context.cacheDir, capturedItem).canonicalFile
                require(file.path.startsWith(context.cacheDir.canonicalPath + File.separator))
                result.put("metadataMode", "captured-first-party-browser-item")
                DomesticDirectExtractor.confirmDouyinMetadata(DouyinVideoParser.parseItem(file.readText(), url!!))
            } else if (capturedHtml == null) {
                result.put("metadataMode", "live-app-metadata")
                DownloadEngine.fetchVideoMetadata(context, url!!, proxy).getOrThrow()
            } else {
                val file = File(context.cacheDir, capturedHtml).canonicalFile
                require(file.path.startsWith(context.cacheDir.canonicalPath + File.separator))
                result.put("metadataMode", "replayed-first-party-response")
                DomesticDirectExtractor.confirmDouyinMetadata(
                    DouyinVideoParser.parseHtml(file.readText(), url!!) ?: error("Captured response has no target video"))
            }
            val selected = metadata.availableVideoFormats.first()
            assertEquals(LinkNormalizer.contentId(metadata.url), metadata.mediaId)
            args.getString("socialWidth")?.toInt()?.let { assertEquals(it, selected.width) }
            args.getString("socialHeight")?.toInt()?.let { assertEquals(it, selected.height) }
            val source = DomesticMediaSource.url(metadata, selected)
            assertEquals(selected.sourceUrl, source)
            result.put("mediaId", metadata.mediaId).put("width", selected.width).put("height", selected.height)
                .put("formats", metadata.availableVideoFormats.size).put("sizeLabel", selected.approximateSize)
            if (download) {
                check(metadata.expectedDurationMs in 1..60_000) { "Only short fixtures may be downloaded by this test" }
                DownloadEngine.ensureInitialized(context).getOrThrow()
                settings.setDownloadPath("")
                val simulateExpired = args.getString("socialExpiredFirst") == "true"
                val firstSource = if (simulateExpired) Uri.parse(source).buildUpon()
                    .encodedPath("/omni-expired-source-$id.mp4").clearQuery().build().toString() else source
                result.put("expiredFirstSourceSimulated", simulateExpired)
                val task = DownloadTask(id = id, url = metadata.url, title = "Omni-social-download-QA", schemaVersion = 3,
                    mediaId = metadata.mediaId,
                    downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedVideoFormatId = selected.formatId,
                    selectedResolution = selected.resolutionLabel, directMediaUrl = firstSource,
                    directMediaHeaders = DomesticMediaSource.headers(metadata.siteName) + selected.sourceHeaders,
                    selectedWidth = selected.width, selectedHeight = selected.height,
                    expectedDurationMs = selected.sourceDurationMs.takeIf { it > 0 } ?: metadata.expectedDurationMs, requireAudio = true)
                result.put("recoveryCandidates", DomesticSourceRecovery.candidates(task, metadata, setOf(firstSource)).size)
                    .put("cachedMetadataAvailable", DomesticDirectExtractor.cachedMetadata(task.url) != null)
                    .put("qualityPolicy", task.qualitySelection.toString())
                val output = DownloadEngine.executeDownload(context, task) { _, _, message, status ->
                    assertNotEquals("Recoverable attempt must not publish a terminal failure", com.omni.downloader.data.model.TaskStatus.FAILED, status)
                    if (message.contains("切换同画质") || message.contains("刷新目标")) println("SOCIAL_RECOVERY_PHASE=$message")
                }.getOrThrow()
                saved = Uri.parse(output)
                if (saved!!.scheme == "content") context.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                else File(output).copyTo(copy, overwrite = true)
                assertNull(MediaVerifier.verifyTask(copy, task))
                val actual = MediaVerifier.probeWithFallback(context, copy)!!
                result.put("actualWidth", actual.width).put("actualHeight", actual.height)
                    .put("durationMs", actual.durationMs).put("hasAudio", actual.hasAudio).put("bytes", copy.length())
            }
            result.put("success", true)
            Unit
        } catch (error: Exception) {
            result.put("errorCode", MediaErrors.codeOf(error).code).put("error", LogSanitizer.sanitize(error.message.orEmpty()))
            File(context.filesDir, "media_tasks/$id").walkTopDown().filter { it.isFile && MediaVerifier.isVideoFile(it) }
                .firstOrNull()?.let { file ->
                    MediaVerifier.probeWithFallback(context, file)?.let { actual ->
                        result.put("actualWidth", actual.width).put("actualHeight", actual.height)
                            .put("durationMs", actual.durationMs).put("hasAudio", actual.hasAudio).put("bytes", file.length())
                    }
                }
            throw error
        } finally {
            File(context.cacheDir, "social-live-result.json").writeText(result.toString())
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_SOCIAL_RESULT=$result\n") })
            saved?.takeIf { it.scheme == "content" }?.let { context.contentResolver.delete(it, null, null) }
            copy.delete()
            File(context.filesDir, "media_tasks/$id").deleteRecursively()
            settings.setDownloadPath(oldPath)
            NetworkProxy.configuredUrl = oldProxy
        }
    }
}
