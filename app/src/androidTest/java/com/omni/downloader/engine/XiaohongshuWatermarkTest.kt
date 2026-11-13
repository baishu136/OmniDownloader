package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in actual note asset; saved old H5 snapshots must not reintroduce platform overlays. */
class XiaohongshuWatermarkTest {
    @Test fun oldImagePlanSingleAndZipKeepTheVerifiedSourceBytes() = verify(false)
    @Test fun coverSavedFromAnOldH5UrlKeepsTheVerifiedSourceBytes() = verify(true)

    private fun verify(cover: Boolean): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val filename = args.getString("xhsWatermarkManifest")
        assumeTrue("Explicit authorized note manifest required", filename != null)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val manifest = File(context.cacheDir, filename!!).canonicalFile
        require(manifest.path.startsWith(context.cacheDir.canonicalPath + File.separator))
        val data = JSONObject(manifest.readText())
        val post = data.getJSONObject("post")
        val expected = data.getString("cleanSha256")
        val image = post.getJSONArray("resources").getJSONObject(0)
        val settings = SettingsRepository.getInstance(context)
        val oldPath = settings.downloadPath.value
        val oldProxy = NetworkProxy.configuredUrl
        val saved = mutableListOf<Uri>()
        val tasks = mutableListOf<String>()
        val copy = File(context.cacheDir, "xhs-watermark-${UUID.randomUUID()}")
        fun verifyBytes(bytes: ByteArray) {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("Returned bytes must equal the visually checked platform source", expected, digest)
            assertNotEquals(data.getString("previewSha256"), digest)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            assertEquals(data.getInt("width"), dimensions.outWidth)
            assertEquals(data.getInt("height"), dimensions.outHeight)
        }
        try {
            NetworkProxy.configuredUrl = args.getString("xhsProxy").orEmpty()
            settings.setDownloadPath("")
            ImagePostSnapshots.put(context, post)
            val types = if (cover) listOf(DownloadType.COVER) else listOf(DownloadType.IMAGE, DownloadType.IMAGE_ZIP)
            for (type in types) {
                val id = "xhs-watermark-${UUID.randomUUID()}"; tasks += id
                val task = DownloadTask(id, post.getString("url"), "XHS watermark regression", downloadType = type,
                    thumbnailUrl = image.getString("url"), imagePlatform = LinkNormalizer.PLATFORM_XHS,
                    selectedImageIds = listOf(image.getString("id")), mediaId = post.getString("contentId"), schemaVersion = 4)
                val uri = Uri.parse(DownloadEngine.executeDownload(context, task, NetworkProxy.configuredUrl) { _, _, _, _ -> }.getOrThrow())
                saved += uri
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (type == DownloadType.IMAGE_ZIP) {
                    copy.writeBytes(bytes)
                    ZipFile(copy).use { zip ->
                        val entry = zip.entries().asSequence().single { !it.isDirectory && it.name != "manifest.json" }
                        verifyBytes(zip.getInputStream(entry).use { it.readBytes() })
                        val receipt = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                        assertEquals("PLATFORM_SOURCE", receipt.getJSONArray("images").getJSONObject(0).getString("source"))
                    }
                } else verifyBytes(bytes)
            }
            instrument.sendStatus(0, Bundle().apply { putString("stream",
                "OMNI_XHS_WATERMARK=" + JSONObject().put("success", true).put("cover", cover)
                    .put("width", data.getInt("width")).put("height", data.getInt("height"))
                    .put("sha256", expected).put("savedFiles", types.size) + "\n") })
        } finally {
            saved.forEach { context.contentResolver.delete(it, null, null) }
            tasks.forEach { File(context.filesDir, "media_tasks/$it").deleteRecursively() }
            copy.delete(); settings.setDownloadPath(oldPath); NetworkProxy.configuredUrl = oldProxy
        }
    }

}
