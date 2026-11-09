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

/** Opt-in live acceptance; a user fixture URL is passed by adb, never bundled into an APK. */
class XiaohongshuDownloadTest {
    @Test fun parseDownloadVerifyAndExportVideo() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("url")
        assumeTrue("Pass a small authorized video fixture with -e url", url != null)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val originalPath = settings.downloadPath.value
        val originalProxy = NetworkProxy.configuredUrl
        val id = "xhs-live-qa-${UUID.randomUUID()}"
        var saved: Uri? = null
        val copy = File(context.cacheDir, "$id.mp4")
        try {
            NetworkProxy.configuredUrl = ""
            settings.setDownloadPath("")
            val metadata = DownloadEngine.fetchVideoMetadata(context, url!!).getOrThrow()
            assertEquals(LinkNormalizer.contentId(metadata.url), metadata.mediaId)
            val selected = metadata.availableVideoFormats.firstOrNull { it.vcodec == "h264" }
                ?: metadata.availableVideoFormats.first()
            val task = DownloadTask(id = id, url = metadata.url, title = "Omni小红书下载回归",
                downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedVideoFormatId = selected.formatId,
                selectedResolution = selected.resolutionLabel, directMediaUrl = selected.formatId,
                directMediaHeaders = DomesticMediaSource.headers(LinkNormalizer.PLATFORM_XHS),
                selectedWidth = selected.width, selectedHeight = selected.height,
                expectedDurationMs = metadata.expectedDurationMs, requireAudio = true)
            saved = Uri.parse(DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow())
            assertEquals("content", saved!!.scheme)
            context.contentResolver.openInputStream(saved!!)!!.use { source -> copy.outputStream().use { source.copyTo(it) } }
            assertNull(MediaVerifier.verifyTask(copy, task))
            val actual = MediaVerifier.probe(copy)!!
            val result = JSONObject().put("success", true).put("mediaId", metadata.mediaId)
                .put("bytes", copy.length()).put("width", actual.width).put("height", actual.height)
                .put("durationMs", actual.durationMs).put("hasAudio", actual.hasAudio).put("mediaStoreExport", true)
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_XHS_DOWNLOAD_RESULT=$result\n") })
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            copy.delete()
            File(context.filesDir, "media_tasks/$id").deleteRecursively()
            settings.setDownloadPath(originalPath)
            NetworkProxy.configuredUrl = originalProxy
        }
    }
}
