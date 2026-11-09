package com.omni.downloader.engine

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class XMultiLiveTest {
    @Test fun reportedPostKeepsThreeMediaIdentitiesAndDownloadsEachSelection(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("xMultiUrl")
        assumeTrue("Explicitly supplied sample required", url != null)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val oldProxy = NetworkProxy.configuredUrl
        val proxy = args.getString("xMultiProxy").orEmpty()
        val download = args.getString("xMultiDownload") == "true"
        val result = JSONObject().put("success", false).put("downloadRequested", download)
        val rows = JSONArray()
        result.put("media", rows)
        val expected = listOf("2119045986353926144", "2119045986412531712", "2119045986395791360")
        try {
            NetworkProxy.configuredUrl = proxy
            settings.setDownloadPath("")
            val meta = DownloadEngine.fetchVideoMetadata(context, url!!, proxy).getOrThrow()
            assertEquals(expected, meta.multiMediaList.map { it.mediaId })
            for ((index, child) in meta.multiMediaList.withIndex()) {
                val snapshot = MetadataSnapshots.get(context, child.url, child.siteName, proxy, child.mediaId)
                assertNotNull("Each child must retain its own downloadable snapshot", snapshot)
                assertEquals(child.mediaId, JSONObject(snapshot!!).getString("id"))
                val choices = if (index == 1) child.availableVideoFormats.filter { it.isVideoOnly }
                    .ifEmpty { child.availableVideoFormats } else child.availableVideoFormats
                val selected = QualitySelector.sorted(choices).first()
                val row = JSONObject().put("mediaId", child.mediaId).put("width", selected.width).put("height", selected.height)
                    .put("formatId", selected.formatId).put("snapshotMatched", true)
                rows.put(row)
                if (!download) continue
                val id = "x-multi-qa-${UUID.randomUUID()}"
                val task = DownloadTask(id, child.url, "X multi QA ${index + 1}", downloadType = DownloadType.VIDEO_WITH_AUDIO,
                    schemaVersion = 3, mediaId = child.mediaId, selectedVideoFormatId = selected.formatId,
                    selectedVideoIsMuxed = !selected.isVideoOnly, qualitySelection = QualitySelection(QualityMode.EXACT_FORMAT),
                    selectedWidth = selected.width, selectedHeight = selected.height, expectedDurationMs = child.expectedDurationMs,
                    requireAudio = child.availableAudioFormats.isNotEmpty(),
                    directMediaUrl = if (!selected.isVideoOnly) selected.sourceUrl else "", directMediaHeaders = selected.sourceHeaders)
                val copy = File(context.cacheDir, "$id.mp4")
                var saved: Uri? = null
                try {
                    val path = DownloadEngine.executeDownload(context, task, proxy) { _, _, _, _ -> }.getOrThrow()
                    saved = Uri.parse(path)
                    context.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    assertNull(MediaVerifier.verifyTask(copy, task, context))
                    val actual = MediaVerifier.probeWithFallback(context, copy)!!
                    val digest = MessageDigest.getInstance("SHA-256")
                    copy.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    row.put("actualWidth", actual.width).put("actualHeight", actual.height).put("durationMs", actual.durationMs)
                        .put("hasAudio", actual.hasAudio).put("bytes", copy.length())
                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_X_MULTI_ITEM=$row\n") })
                } finally {
                    saved?.let { context.contentResolver.delete(it, null, null) }
                    copy.delete(); File(context.filesDir, "media_tasks/$id").deleteRecursively()
                }
            }
            if (download) assertEquals(3, (0 until rows.length()).map { rows.getJSONObject(it).getString("sha256") }.toSet().size)
            result.put("success", true)
            Unit
        } catch (error: Exception) {
            result.put("error", LogSanitizer.sanitize(error.message.orEmpty()))
            throw error
        } finally {
            File(context.cacheDir, "x-multi-live-result.json").writeText(result.toString())
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_X_MULTI_RESULT=$result\n") })
            settings.setDownloadPath(oldPath); NetworkProxy.configuredUrl = oldProxy
        }
    }
}
