package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in user link regression; account credentials and resource URLs are never exported. */
class XiaohongshuImagesDownloadTest {
    @Test fun actualImagesAndZipAreSavedThroughProductionPipeline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url")
        assumeTrue("Pass an authorized image-note fixture with -e url", url != null)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val previousPath = settings.downloadPath.value
        val previousProxy = NetworkProxy.configuredUrl
        val id = "xhs-images-qa-${UUID.randomUUID()}"
        val saved = mutableListOf<Uri>()
        val copies = File(context.cacheDir, id).apply { mkdirs() }
        val tasks = mutableListOf<String>()
        try {
            NetworkProxy.configuredUrl = ""
            settings.setDownloadPath("")
            val post = SharedPython.run(context, "images", url!!, LinkNormalizer.PLATFORM_XHS, "")
            val images = post.getJSONArray("resources")
            assertEquals(args.getString("expectedCount")?.toInt() ?: 3, images.length())
            val all = (0 until images.length()).map { images.getJSONObject(it).getString("id") }
            val dimensions = JSONArray()
            var archiveBytes = 0L
            var singleBytes = 0L
            listOf(DownloadType.IMAGE_ZIP, DownloadType.IMAGE).forEach { type ->
                val taskId = "$id-${type.name}"
                tasks += taskId
                val task = DownloadTask(id = taskId, url = post.getString("url"), title = "小红书图文回归",
                    downloadType = type, imagePlatform = LinkNormalizer.PLATFORM_XHS,
                    selectedImageIds = if (type == DownloadType.IMAGE_ZIP) all else all.take(1))
                val uri = Uri.parse(DownloadEngine.executeDownload(context, task) { _, _, _, _ -> }.getOrThrow())
                assertEquals("content", uri.scheme)
                saved += uri
                val file = File(copies, if (type == DownloadType.IMAGE_ZIP) "saved.zip" else "saved.image")
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (type == DownloadType.IMAGE_ZIP) {
                    archiveBytes = file.length()
                    ZipFile(file).use { archive ->
                        val entries = archive.entries().asSequence().filter { !it.isDirectory && it.name != "manifest.json" }.toList()
                        assertEquals(images.length(), entries.size)
                        entries.forEach { entry ->
                            val bytes = archive.getInputStream(entry).use { it.readBytes() }
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            assertTrue(options.outWidth > 0 && options.outHeight > 0)
                            dimensions.put(JSONArray(listOf(options.outWidth, options.outHeight)))
                        }
                        val manifest = archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                        assertEquals(images.length(), JSONObject(manifest).getJSONArray("images").length())
                        assertFalse(manifest.contains("https://"))
                    }
                } else {
                    singleBytes = file.length()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    assertTrue(options.outWidth > 0 && options.outHeight > 0)
                }
            }
            val result = JSONObject().put("success", true).put("contentId", post.getString("contentId"))
                .put("imageCount", images.length()).put("dimensions", dimensions)
                .put("archiveBytes", archiveBytes).put("singleImageBytes", singleBytes).put("mediaStoreExport", true)
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_XHS_IMAGES_RESULT=$result\n") })
        } finally {
            saved.forEach { context.contentResolver.delete(it, null, null) }
            copies.deleteRecursively()
            tasks.forEach { File(context.filesDir, "media_tasks/$it").deleteRecursively() }
            settings.setDownloadPath(previousPath)
            NetworkProxy.configuredUrl = previousProxy
        }
    }
}
