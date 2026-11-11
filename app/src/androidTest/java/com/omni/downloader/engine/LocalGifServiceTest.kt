package com.omni.downloader.engine

import android.content.ContentValues
import android.provider.MediaStore
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalGifServiceTest {
    @Test fun localVideoConvertsOnceInBackgroundAndDecodes() = runCase(false)
    @Test fun cancellingOneConversionDoesNotBlockTheNext() = runCase(true)
    private fun runCase(cancelFirst: Boolean): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val repository = TaskRepository.getInstance(context)
        repository.awaitLoaded()
        val settings = SettingsRepository.getInstance(context)
        val previous = settings.downloadPath.value
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "omni-local-gif-test.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        })!!
        val id = UUID.randomUUID().toString()
        val firstId = if (cancelFirst) UUID.randomUUID().toString() else null
        val copy = File(context.cacheDir, "$id.gif")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out -> instrument.context.assets.open("quality-1080.mp4").use { it.copyTo(out) } }
            settings.setDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            repository.addTask(DownloadTask(id, uri.toString(), "Local GIF service test", downloadType = DownloadType.GIF))
            firstId?.let { repository.addTask(DownloadTask(it, uri.toString(), "Cancelled local GIF", downloadType = DownloadType.GIF)) }
            instrument.runOnMainSync {
                firstId?.let { DownloadService.startDownload(context, it) }
                DownloadService.startDownload(context, id)
                DownloadService.startDownload(context, id)
                firstId?.let { DownloadService.cancelDownload(context, it) }
            }
            instrument.uiAutomation.executeShellCommand("input keyevent 3").use { it.fileDescriptor.let { fd -> java.io.FileInputStream(fd).bufferedReader().readText() } }
            val deadline = SystemClock.elapsedRealtime() + 90000
            while (repository.getTask(id)?.status !in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val task = repository.getTask(id)!!
            assertEquals(task.errorMessage, TaskStatus.COMPLETED, task.status)
            context.contentResolver.openInputStream(android.net.Uri.parse(task.localFilePath))!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            firstId?.let { assertEquals(TaskStatus.CANCELLED, repository.getTask(it)!!.status) }
            val decoded = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(copy))
            assertTrue(decoded is android.graphics.drawable.AnimatedImageDrawable)
            assertNotNull(GifInspector.inspect(copy))
            assertTrue(GifInspector.inspect(copy)!!.frames > 1)
            assertTrue("Original input must survive conversion", context.contentResolver.openInputStream(uri)!!.use { it.read() } >= 0)
        } finally {
            repository.removeTask(id, deleteLocalFile = true)
            firstId?.let { repository.removeTask(it, deleteLocalFile = true) }
            context.contentResolver.delete(uri, null, null)
            copy.delete()
            settings.setDownloadPath(previous)
            scenario.close()
        }
    }
}
