package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in live acceptance. Returns diagnostics, never credentials, titles or resource URLs. */
class PlatformAcceptanceTest {
    @Test fun inspectAndSave() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: error("url is required")
        val expected = args.getString("expectedKind") ?: "VIDEO"
        val proxy = args.getString("proxy").orEmpty()
        val platform = LinkNormalizer.platformOf(url)
        val settings = SettingsRepository.getInstance(context)
        val originalPath = settings.downloadPath.value
        val originalProxy = NetworkProxy.configuredUrl
        val requestedTestId = args.getString("reuseTaskId")
        require(requestedTestId == null || Regex("platform-qa-[a-f0-9-]{36}").matches(requestedTestId))
        val testId = requestedTestId ?: "platform-qa-${UUID.randomUUID()}"
        val workspace = File(context.cacheDir, testId).apply { mkdirs() }
        val taskIds = mutableListOf<String>()
        val saved = mutableListOf<Uri>()
        val result = JSONObject().put("caseId", args.getString("caseId"))
            .put("platform", platform).put("expectedKind", expected)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("hasSession", SessionRepository.getInstance(context).info(platform)?.cookieCount?.let { it > 0 } ?: false)
            .put("testNetworkRoute", if (proxy.isBlank()) "direct" else "host_system_proxy_bridge")
            .put("parseSuccess", false).put("downloadStatus", "NOT_ATTEMPTED")
        val started = android.os.SystemClock.elapsedRealtime()
        var stage = "PARSE"
        fun validateImage(bytes: ByteArray): JSONArray {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            check(options.outWidth > 0 && options.outHeight > 0) { "[POSTPROCESS_FAILED] 导出的图片无法解码" }
            return JSONArray(listOf(options.outWidth, options.outHeight))
        }
        suspend fun save(task: DownloadTask): File {
            taskIds += task.id
            val exported = DownloadEngine.executeDownload(context, task, proxy) { _, _, _, _ -> }.getOrThrow()
            val uri = Uri.parse(exported)
            check(uri.scheme == "content") { "[EXPORT_FAILED] 没有通过系统下载目录导出" }
            saved += uri
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "export.bin"
            val copy = File(workspace, "${taskIds.size}-${File(name).name}")
            context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            check(copy.length() > 0) { "[EXPORT_FAILED] 导出成品为空" }
            return copy
        }
        try {
            NetworkProxy.configuredUrl = proxy
            settings.setDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            if (expected == "IMAGE_POST") {
                val post = SharedPython.run(context, "images", url, platform, proxy)
                val images = post.getJSONArray("resources")
                check(images.length() > 0) { "[NO_MEDIA] 未返回图片" }
                result.put("parseSuccess", true).put("mediaId", post.optString("contentId")).put("imageCount", images.length())
                if (args.getString("download") != "true") result.put("downloadStatus", "COVERED_BY_OTHER_SAMPLE")
                else if (images.length() > 30) result.put("downloadStatus", "SKIPPED_IMAGE_COUNT_BUDGET")
                else {
                    stage = "DOWNLOAD"
                    val ids = (0 until images.length()).map { images.getJSONObject(it).getString("id") }
                    val single = save(DownloadTask(id = "$testId-image", url = post.getString("url"), title = "平台回归",
                        downloadType = DownloadType.IMAGE, imagePlatform = platform, selectedImageIds = ids.take(1)))
                    result.put("singleImageBytes", single.length()).put("singleImageSize", validateImage(single.readBytes()))
                    val zip = save(DownloadTask(id = "$testId-zip", url = post.getString("url"), title = "平台回归",
                        downloadType = DownloadType.IMAGE_ZIP, imagePlatform = platform, selectedImageIds = ids))
                    val sizes = JSONArray()
                    ZipFile(zip).use { archive ->
                        val members = archive.entries().asSequence().filter { !it.isDirectory && it.name != "manifest.json" }.toList()
                        check(members.size == images.length()) { "[POSTPROCESS_FAILED] ZIP 图片数不一致" }
                        members.forEach { entry -> sizes.put(validateImage(archive.getInputStream(entry).use { it.readBytes() })) }
                    }
                    result.put("archiveBytes", zip.length()).put("savedImageSizes", sizes)
                        .put("downloadStatus", "PASS").put("mediaStoreExport", true)
                }
            } else {
                val parent = DownloadEngine.fetchVideoMetadata(context, url, proxy).getOrThrow()
                result.put("parseSuccess", true).put("mediaId", parent.mediaId)
                    .put("videoFormats", parent.availableVideoFormats.size).put("children", parent.multiMediaList.size)
                    .put("isGif", parent.isGif).put("durationMs", parent.expectedDurationMs)
                if (expected == "COLLECTION" && parent.multiMediaList.size < 2)
                    error("[MEDIA_UNCONFIRMED] 合集样例未展开为多个分集")
                var meta = parent
                if (parent.multiMediaList.isNotEmpty()) {
                    val child = parent.multiMediaList.minByOrNull { if (it.expectedDurationMs > 0) it.expectedDurationMs else Long.MAX_VALUE }!!
                    meta = DownloadEngine.fetchVideoMetadata(context, child.url, proxy).getOrThrow()
                    result.put("downloadedCollectionChildren", 1)
                }
                val formats = meta.availableVideoFormats.filter { !it.isSynthetic && !it.isAudioOnly }
                check(formats.isNotEmpty()) { "[NO_MEDIA] 未返回可下载视频格式" }
                val largest = formats.maxByOrNull { it.width.toLong() * it.height }!!
                result.put("maxAvailableSize", JSONArray(listOf(largest.width, largest.height)))
                    .put("originalSourceAvailable", formats.any { it.note.contains("原始视频源") })
                val selected = if (expected == "UGOIRA") formats.firstOrNull { it.formatId == "ugoira_mp4" } ?: formats.first()
                    else if (args.getString("bestQuality") == "true") largest
                    else formats.filter { maxOf(it.width, it.height) <= 1280 && (it.width > 0 || it.height > 0) }
                        .minByOrNull { it.width.toLong() * it.height } ?: formats.minByOrNull { maxOf(it.width, it.height) }!!
                result.put("selectedSize", JSONArray(listOf(selected.width, selected.height)))
                if (args.getString("download") != "true") result.put("downloadStatus", "COVERED_BY_OTHER_SAMPLE")
                else if (meta.expectedDurationMs > 600_000) result.put("downloadStatus", "SKIPPED_DURATION_BUDGET")
                else {
                    stage = "DOWNLOAD"
                    val kind = when { expected == "UGOIRA" -> DownloadType.VIDEO_ONLY
                        expected == "ANIMATED_VIDEO" -> DownloadType.GIF
                        else -> DownloadType.VIDEO_WITH_AUDIO }
                    val task = DownloadTask(id = "$testId-video", url = meta.url, title = "平台视频回归",
                        downloadType = kind, selectedResolution = selected.resolutionLabel,
                        selectedVideoFormatId = selected.formatId, selectedVideoIsMuxed = !selected.isVideoOnly,
                        directMediaUrl = DomesticMediaSource.url(meta, selected),
                        directMediaHeaders = DomesticMediaSource.headers(platform),
                        selectedWidth = selected.width, selectedHeight = selected.height, expectedDurationMs = meta.expectedDurationMs,
                        requireAudio = kind == DownloadType.VIDEO_WITH_AUDIO && (selected.acodec.isNotBlank() && selected.acodec != "none" || meta.availableAudioFormats.isNotEmpty()))
                    val file = save(task)
                    if (kind == DownloadType.GIF) {
                        val header = file.inputStream().use { it.readNBytes(6) }.toString(Charsets.US_ASCII)
                        check(header in setOf("GIF87a", "GIF89a")) { "[POSTPROCESS_FAILED] 导出文件不是 GIF" }
                        result.put("savedGif", true)
                    } else {
                        MediaVerifier.verifyTask(file, task, context)?.let { error(it) }
                        val actual = MediaVerifier.probeWithFallback(context, file) ?: error("[POSTPROCESS_FAILED] 导出视频无法识别")
                        result.put("savedSize", JSONArray(listOf(actual.width, actual.height)))
                            .put("savedDurationMs", actual.durationMs).put("hasAudio", actual.hasAudio)
                    }
                    result.put("downloadStatus", "PASS").put("savedBytes", file.length())
                        .put("downloadType", kind.name).put("mediaStoreExport", true)
                }
            }
        } catch (error: Exception) {
            result.put("failureStage", stage).put("errorCode", MediaErrors.codeOf(error).code)
                .put("error", LogSanitizer.sanitize(error.message.orEmpty()).takeLast(1800))
            if (stage == "DOWNLOAD") result.put("downloadStatus", "FAIL")
        } finally {
            saved.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            taskIds.forEach { File(context.filesDir, "media_tasks/$it").deleteRecursively() }
            workspace.deleteRecursively()
            settings.setDownloadPath(originalPath)
            NetworkProxy.configuredUrl = originalProxy
        }
        result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
        instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_ACCEPTANCE=$result\n") })
    }
}
