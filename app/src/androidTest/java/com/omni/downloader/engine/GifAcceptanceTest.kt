package com.omni.downloader.engine

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real bundled FFmpeg -> MediaStore -> Android and app GIF decoders. */
class GifAcceptanceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    private fun roundTrip(asset: String, suffix: String, preserveBytes: Boolean): Unit = runBlocking {
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val bytes = instrument.context.assets.open(asset).use { it.readBytes() }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val worker = Thread {
            while (running.get()) try {
                server.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = connection.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    if (!request.startsWith("HEAD")) output.write(bytes)
                    output.flush()
                }
            } catch (_: Exception) { if (!running.get()) break }
        }.apply { isDaemon = true; start() }
        val id = "gif-qa-${UUID.randomUUID()}"
        val task = DownloadTask(id, "http://127.0.0.1:${server.localPort}/source.$suffix", "GIF QA $id",
            downloadType = DownloadType.GIF, directMediaUrl = "http://127.0.0.1:${server.localPort}/source.$suffix")
        val copy = File(context.cacheDir, "$id.gif")
        var saved: Uri? = null
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            settings.setDownloadPath("")
            val result = DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow()
            saved = Uri.parse(result)
            assertEquals("image/gif", context.contentResolver.getType(saved!!))
            context.contentResolver.openInputStream(saved!!)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            assertNull(MediaVerifier.verifyTask(copy, task, context))
            val info = GifInspector.inspect(copy)!!
            assertTrue("Animation frames were lost", info.frames >= 2)
            assertTrue("Animation duration was lost", info.durationMs >= if (preserveBytes) 200 else 800)
            if (preserveBytes) assertArrayEquals(bytes, copy.readBytes())
            if (preserveBytes) {
                @Suppress("DEPRECATION") val movie = android.graphics.Movie.decodeFile(copy.path)!!
                val bitmap = android.graphics.Bitmap.createBitmap(info.width, info.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                try {
                    movie.setTime(0); movie.draw(canvas, 0f, 0f)
                    val first = bitmap.getPixel(8, 8)
                    movie.setTime(150); movie.draw(canvas, 0f, 0f)
                    assertNotEquals("Decoded animation must advance between its red/blue frames", first, bitmap.getPixel(8, 8))
                } finally { bitmap.recycle() }
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, saved!!))
                assertTrue("Export must open as an animated Android image", drawable is AnimatedImageDrawable)
            }
            val preview = context.imageLoader.execute(ImageRequest.Builder(context).data(saved).allowHardware(false).build())
            assertTrue("The app's own preview decoder must open the export: $preview", preview is SuccessResult)
            println("OMNI_GIF_RESULT asset=$asset servedAs=$suffix frames=${info.frames} width=${info.width} height=${info.height} durationMs=${info.durationMs} bytes=${copy.length()} androidDecoder=PASS appPreview=PASS mime=image/gif")
        } finally {
            running.set(false); server.close(); worker.join(2000)
            saved?.let { context.contentResolver.delete(it, null, null) }
            copy.delete()
            File(context.filesDir, "media_tasks/$id").deleteRecursively()
            settings.setDownloadPath(oldPath)
        }
    }

    @Test fun nativeSmallAnimatedGifDownloadsWithoutLosingFrames() = roundTrip("animated.gif", "gif", true)
    @Test fun downloadedVideoBecomesARealPlayableGif() = roundTrip("quality-1080.mp4", "mp4", false)
    @Test fun videoWithGifExtensionIsConvertedInsteadOfBlindlyCopied() = roundTrip("quality-1080.mp4", "gif", false)

    @Test fun truncatedGifAndRenamedVideoAreRejectedBeforeExport(): Unit = runBlocking {
        val context = instrument.targetContext
        val task = DownloadTask("bad-gif", "https://example.test/fixture", "fixture", downloadType = DownloadType.GIF)
        val file = File.createTempFile("bad-gif-", ".mp4", context.cacheDir)
        try {
            file.writeBytes(instrument.context.assets.open("animated.gif").use { it.readBytes() }.dropLast(1).toByteArray())
            assertNotNull(MediaVerifier.verifyTask(file, task, context))
            file.writeBytes(instrument.context.assets.open("quality-1080.mp4").use { it.readBytes() })
            assertNotNull(MediaVerifier.verifyTask(file, task, context))
        } finally { file.delete() }
    }

    @Test fun damagedFfmpegLibraryIsRepairedFromTheBundledArchive(): Unit = runBlocking {
        val context = instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val lib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").listFiles()!!
            .first { it.name.startsWith("libavfilter.so.") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        val expected = lib.readBytes()
        try {
            lib.writeBytes(byteArrayOf())
            FFmpegRuntime.ensure(context)
            assertArrayEquals(expected, lib.readBytes())
        } finally { FFmpegRuntime.ensure(context) }
    }
}
