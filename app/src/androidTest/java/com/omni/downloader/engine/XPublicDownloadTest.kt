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

/** Opt-in user-supplied short sample; forces the reported native public path and HLS selection. */
class XPublicDownloadTest {
    @Test fun nativeSnapshotDownloadsSelectedHlsWithItsAudio(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("xUrl")
        assumeTrue("Opt-in sample required", url != null)
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val previousPath = settings.downloadPath.value
        val previousProxy = NetworkProxy.configuredUrl
        val proxy = args.getString("xProxy").orEmpty()
        val id = "x-hls-qa-${UUID.randomUUID()}"
        val copy = File(context.cacheDir, "$id.mp4")
        var saved: Uri? = null
        val result = JSONObject().put("success", false).put("route", "live-native-public-snapshot")
        try {
            NetworkProxy.configuredUrl = proxy
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val data = XPublicExtractor.extract(context, url!!, proxy)
            check(!data.has("entries")) { "This acceptance sample must contain one video" }
            val mediaId = data.getString("id")
            args.getString("xMediaId")?.let { assertEquals(it, mediaId) }
            val page = data.getString("webpage_url")
            assertEquals(MediaSelection.identity(url), MediaSelection.identity(page))
            val formats = data.getJSONArray("formats")
            val candidates = (0 until formats.length()).map { formats.getJSONObject(it) }
            val selected = candidates.filter { it.optString("format_id").startsWith("hls-") && it.optInt("height") > 0 }
                .maxBy { it.optLong("width") * it.optLong("height") }
            val duration = (data.optDouble("duration") * 1000).toLong()
            check(duration in 1..60_000) { "Only short authorized samples may be downloaded" }
            result.put("mediaId", mediaId).put("selectedFormatId", selected.getString("format_id"))
                .put("selectedWidth", selected.getInt("width")).put("selectedHeight", selected.getInt("height"))
                .put("audioFormats", candidates.count { it.optString("vcodec") == "none" })
            MetadataSnapshots.put(context, page, LinkNormalizer.PLATFORM_X, proxy, data)
            settings.setDownloadPath("")
            val task = DownloadTask(id = id, url = page, title = "X HLS regression", schemaVersion = 3,
                downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedVideoFormatId = selected.getString("format_id"),
                selectedVideoIsMuxed = false, selectedResolution = QualitySelector.label(selected.getInt("width"), selected.getInt("height")),
                mediaId = mediaId, selectedWidth = selected.getInt("width"), selectedHeight = selected.getInt("height"),
                expectedDurationMs = duration, requireAudio = true)
            val output = DownloadEngine.executeDownload(context, task, proxy) { _, _, _, _ -> }.getOrThrow()
            saved = Uri.parse(output)
            check(saved!!.scheme == "content") { "Expected MediaStore export" }
            context.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            assertNull(MediaVerifier.verifyTask(copy, task, context))
            val actual = MediaVerifier.probeWithFallback(context, copy)!!
            result.put("actualWidth", actual.width).put("actualHeight", actual.height).put("durationMs", actual.durationMs)
                .put("hasAudio", actual.hasAudio).put("bytes", copy.length()).put("success", true)
        } catch (error: Exception) {
            result.put("errorCode", MediaErrors.codeOf(error).code).put("error", LogSanitizer.sanitize(error.message.orEmpty()))
            throw error
        } finally {
            File(context.cacheDir, "x-live-result.json").writeText(result.toString())
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_X_RESULT=$result\n") })
            saved?.let { context.contentResolver.delete(it, null, null) }
            copy.delete()
            File(context.filesDir, "media_tasks/$id").deleteRecursively()
            settings.setDownloadPath(previousPath)
            NetworkProxy.configuredUrl = previousProxy
        }
    }
}
