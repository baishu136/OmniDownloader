package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.repository.SettingsRepository
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaPipelineTest {
    @Test fun failedExportRetriesWithoutDownloadingAgain() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val bytes = instrument.context.assets.open("verification.mp4").use { it.readBytes() }
        val socket = java.net.ServerSocket(0)
        val server = Thread {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrBlank()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val task = DownloadTask(id = "export-retry-qa", url = "http://127.0.0.1:${socket.localPort}/fixture.mp4", title = "retry-qa",
            downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedWidth = 320, selectedHeight = 180, requireAudio = true, expectedDurationMs = 1000)
        val settings = SettingsRepository.getInstance(context)
        val original = settings.downloadPath.value
        var destination: android.net.Uri? = null
        try {
            settings.setDownloadPath("content://omni.invalid/tree/revoked")
            val failed = DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }
            assertTrue(failed.isFailure)
            assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("EXPORT_FAILED"))
            socket.close(); server.join(3000)
            settings.setDownloadPath("")
            val result = DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow()
            destination = android.net.Uri.parse(result)
            val output = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
            assertArrayEquals(bytes, output)
        } finally {
            destination?.let { context.contentResolver.delete(it, null, null) }
            settings.setDownloadPath(original); socket.close()
            File(context.filesDir, "media_tasks/${task.id}").deleteRecursively()
        }
    }

    @Test fun actualUgoiraEncodingPreservesUnequalFrameTiming() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val bytes = instrument.context.assets.open("ugoira-source.zip").use { it.readBytes() }
        val socket = java.net.ServerSocket(0)
        val server = Thread {
            socket.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrBlank()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val work = File(context.filesDir, "qa-ugoira").apply { mkdirs() }
        try {
            val task = DownloadTask(id = "ugoira-qa", url = "https://www.pixiv.net/artworks/1", title = "ugoira",
                downloadType = DownloadType.VIDEO_ONLY, selectedVideoFormatId = "ugoira_mp4")
            val file = UgoiraProcessor.process(context, task, "http://127.0.0.1:${socket.localPort}/source.zip", emptyMap(),
                listOf(UgoiraProcessor.Frame("0.png", 80), UgoiraProcessor.Frame("1.png", 230), UgoiraProcessor.Frame("2.png", 390)),
                "1", File(work, "stage").apply { mkdirs() }, File(work, "output").apply { mkdirs() }) { _, _, _, _ -> }.getOrThrow()
            val actual = MediaVerifier.probe(File(file))!!
            assertEquals(102, actual.width); assertEquals(100, actual.height)
            assertTrue("duration=${actual.durationMs}", kotlin.math.abs(actual.durationMs - 700) < 50)
        } finally { socket.close(); server.join(3000); work.deleteRecursively() }
    }

    @Test fun bundledRuntimeAndMediaStoreExport() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val version = YoutubeDL.getInstance().execute(YoutubeDLRequest("https://example.com/").apply { addOption("--version") })
        assertEquals(BundledEngineInstaller.VERSION, version.out.trim())
        val diagnostics = EngineDiagnosticsProbe.probe(context)
        assertEquals("3.12", diagnostics.pythonRuntime)
        assertTrue(diagnostics.ejsPresent)
        android.util.Log.i("OmniRuntimeQA", "yt-dlp=" + version.out.trim())
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val quickJs = ProcessBuilder(File(nativeDir, "libqjs.so").absolutePath, "-e", "console.log('QJS_OK')").apply {
            environment()["LD_LIBRARY_PATH"] = listOf(nativeDir.absolutePath,
                File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib").absolutePath,
                File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib/quickjs").absolutePath).joinToString(":")
            redirectErrorStream(true)
        }.start()
        val jsOutput = quickJs.inputStream.bufferedReader().use { it.readText() }
        assertEquals(jsOutput, 0, quickJs.waitFor())
        assertTrue(jsOutput.contains("QJS_OK"))
        val source = File(context.filesDir, "qa-source.mp4")
        instrument.context.assets.open("verification.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val task = DownloadTask(id = "qa", url = "https://example.com/fixture", title = "fixture",
            downloadType = DownloadType.VIDEO_WITH_AUDIO, selectedWidth = 320, selectedHeight = 180,
            requireAudio = true, expectedDurationMs = 1000)
        assertNull(MediaVerifier.verifyTask(source, task))
        assertNotNull(MediaVerifier.verifyTask(source, task.copy(selectedWidth = 3840, selectedHeight = 2160)))
        val settings = SettingsRepository.getInstance(context)
        val original = settings.downloadPath.value
        var exported: android.net.Uri? = null
        try {
            settings.setDownloadPath("")
            val result = StorageExporter.export(context, source)
            exported = android.net.Uri.parse(result)
            assertEquals("content", exported.scheme)
            val bytes = context.contentResolver.openInputStream(exported)!!.use { it.readBytes() }
            assertArrayEquals(source.readBytes(), bytes)
            assertTrue("export must preserve source until caller commits", source.isFile)
        } finally {
            exported?.let { context.contentResolver.delete(it, null, null) }
            settings.setDownloadPath(original)
            source.delete()
        }
    }
}
