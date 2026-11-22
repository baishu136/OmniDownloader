package com.omni.downloader.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.engine.StorageExporter
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.localization.resolveAppStrings
import com.omni.downloader.ui.theme.OmniDownloaderTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CollectionMediaPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private var exportedUri: Uri? = null

    @After fun removeExport() {
        exportedUri?.let { InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.delete(it, null, null) }
    }

    @Test fun firstEpisodeAndExpandedEpisodeOpenMediaStoreUri() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("collection-playback-test-", ".mp4", context.cacheDir)
        val uri = try {
            file.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray())
            Uri.parse(StorageExporter.export(context, file, targetOverride = ""))
        } finally {
            file.delete()
        }
        exportedUri = uri
        val intents = mutableListOf<Intent>()
        val strings = resolveAppStrings("en")
        val task = DownloadTask("episode-one", "https://example.org", "Episode 1",
            downloadType = DownloadType.VIDEO_WITH_AUDIO, status = TaskStatus.COMPLETED,
            localFilePath = uri.toString(), episodeIndex = 1, episodeTotal = 1)
        compose.setContent {
            val baseContext = LocalContext.current
            val launchContext = object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) { intents.add(intent) }
            }
            CompositionLocalProvider(LocalContext provides launchContext, LocalAppStrings provides strings) {
                OmniDownloaderTheme(darkTheme = false) {
                    CollectionTaskCard("playlist", "Playlist", listOf(task), {}, { _, _ -> }, {}, {})
                }
            }
        }
        compose.onNodeWithText(strings.collectionPlayFirst).performClick()
        compose.onNodeWithText(strings.collectionExpandDetails.format(1, 1)).performClick()
        compose.onNodeWithContentDescription(strings.play).performClick()
        compose.runOnIdle {
            assertEquals(2, intents.size)
            for (intent in intents) assertPlaybackIntent(uri, intent)
        }
    }

    private fun assertPlaybackIntent(uri: Uri, actual: Intent) {
        assertEquals(Intent.ACTION_VIEW, actual.action)
        assertEquals(uri, actual.data)
        assertEquals("video/mp4", actual.type)
        assertTrue(actual.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
