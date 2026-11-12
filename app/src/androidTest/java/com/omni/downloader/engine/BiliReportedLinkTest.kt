package com.omni.downloader.engine

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit live regression for the user's public share; no account cookie is imported. */
class BiliReportedLinkTest {
    @Test fun reportedShareParsesAndDownloadsTheCompleteVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var output: String? = null
        try {
            settings.setProxyUrl("");settings.setDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val metadata = DownloadEngine.fetchVideoMetadata(context, "https://b23.tv/3jv20GC").getOrThrow()
            assertEquals("BV1xyWT6fEiM:43480778226", metadata.mediaId)
            val task = BatchTaskFactory.videos(BatchItem(url = metadata.url), metadata, BatchOptions()).single()
            output = DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow()
            assertNotNull(output)
            File(context.getExternalFilesDir(null), "bili-reported-download.txt").writeText(
                "mediaId=${metadata.mediaId}\nexpectedMs=${task.expectedDurationMs}\nresolution=${task.selectedWidth}x${task.selectedHeight}\ncompleted=true\n")
        } finally {
            output?.let { if (it.startsWith("content://")) context.contentResolver.delete(android.net.Uri.parse(it), null, null) else File(it).delete() }
            settings.setDownloadPath(oldPath);scenario.close()
        }
    }
}
