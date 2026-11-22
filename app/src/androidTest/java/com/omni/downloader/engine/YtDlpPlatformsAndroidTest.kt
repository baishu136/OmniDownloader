package com.omni.downloader.engine

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.screens.HomePlatformChips
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class YtDlpPlatformsAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogSearchUsesInstalledEngine() {
        compose.setContent { MaterialTheme { HomePlatformChips() } }
        compose.onNodeWithTag("ytdlp_platforms_open").assertDoesNotExist()
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.onNodeWithTag("ytdlp_platforms_open").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("ytdlp_platforms_search")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("ytdlp_platforms_search").performTextInput("西瓜视频")
        compose.onAllNodesWithText("Ixigua")[0].assertIsDisplayed()
    }

    @Test fun anamorphicVideoMatchesTheReportedDisplaySize(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val clip = File.createTempFile("anamorphic-", ".mp4", context.cacheDir)
        try {
            val ffmpeg = checkNotNull(FFmpegExecutor.getFFmpegBinary(context))
            kotlinx.coroutines.withTimeout(20_000) {
                FFmpegExecutor.executeCommand(context, listOf(ffmpeg.path, "-y", "-f", "lavfi",
                    "-i", "color=c=black:s=416x240:r=24:d=1", "-vf", "setsar=40/39",
                    "-an", "-c:v", "libx264", clip.path)).getOrThrow()
            }
            val coded = checkNotNull(MediaVerifier.probe(clip))
            assertEquals(416, coded.width)
            val task = DownloadTask(id = "anamorphic-fixture", url = "https://example.test/video", title = "Anamorphic",
                downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedWidth = 427, selectedHeight = 240)
            assertNull(MediaVerifier.verifyTask(clip, task, context))
            assertTrue(MediaVerifier.verifyTask(clip, task.copy(selectedWidth = 1280, selectedHeight = 720), context)
                .orEmpty().startsWith("[QUALITY_UNAVAILABLE]"))
        } finally {
            clip.delete()
        }
    }

    @Test fun genericPageDownloadsOnlyTheSelectedVideo(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val first = instrument.context.assets.open("verification.mp4").use { it.readBytes() }
        val second = instrument.context.assets.open("quality-720.mp4").use { it.readBytes() }
        val html = """
            <html><head><title>Local collection</title></head><body>
            <video src="/first.mp4" controls></video>
            <video src="/second.mp4" controls></video>
            </body></html>
        """.trimIndent().toByteArray()
        val id = "ytdlp-catalog-${UUID.randomUUID()}"
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val export = File(context.cacheDir, id)
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                while (!server.isClosed) {
                    val connection = try { server.accept() } catch (_: java.net.SocketException) { break }
                    connection.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty().split(' ')
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val (type, body) = when (request.getOrNull(1)) {
                            "/first.mp4" -> "video/mp4" to first
                            "/second.mp4" -> "video/mp4" to second
                            else -> "text/html" to html
                        }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (request.firstOrNull() != "HEAD") write(body)
                        }
                    }
                }
            }
            try {
                settings.setDownloadPath(export.absolutePath)
                val url = "http://127.0.0.1:${server.localPort}/playlist"
                val metadata = DownloadEngine.fetchVideoMetadata(context, url).getOrThrow()
                assertEquals(2, metadata.multiMediaList.size)
                val selected = metadata.multiMediaList.single { entry ->
                    entry.availableVideoFormats.any { it.sourceUrl.endsWith("/second.mp4") }
                }
                val format = selected.availableVideoFormats.single()
                val task = DownloadTask(id = id, url = selected.url, title = selected.title,
                    downloadType = DownloadType.VIDEO_WITH_AUDIO, mediaId = selected.mediaId,
                    selectedVideoFormatId = format.formatId, selectedVideoIsMuxed = !format.isVideoOnly)
                val result = DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow()
                val file = File(result)
                assertTrue(file.isFile)
                assertArrayEquals(second, file.readBytes())
                assertEquals(1, export.listFiles()?.size)
            } finally {
                server.close()
                worker.join(1000)
                settings.setDownloadPath(oldPath)
                export.deleteRecursively()
                File(context.filesDir, "media_tasks/$id").deleteRecursively()
            }
        }
    }
}
