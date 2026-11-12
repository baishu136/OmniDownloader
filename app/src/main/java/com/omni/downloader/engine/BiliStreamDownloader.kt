package com.omni.downloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.TimeUnit

internal object BiliHttp {
    suspend fun <T> read(client: OkHttpClient, request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { read(it) } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}

internal object BiliStreamUrls {
    fun ordered(primary: String, backups: List<String>): List<String> = (listOf(primary) + backups).distinct().filter {
        runCatching { URI(it).let { uri -> uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null } }.getOrDefault(false)
    }.sortedBy { value ->
        val uri = URI(value); val host = uri.host.lowercase()
        when {
            uri.scheme != "https" -> 5
            uri.port !in setOf(-1, 443) -> 4
            host.contains(".mcdn.") -> 3
            host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn") -> 0
            else -> 1
        }
    }.take(12)
}

/** Backups are for the same advertised track. A new CDN starts from zero; ranges
 * within one CDN must agree on length and entity before bytes may be appended. */
internal class BiliStreamDownloader(client: OkHttpClient, private val chunkBytes: Long = 4L * 1024 * 1024) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private data class Chunk(val size: Long, val total: Long, val validator: String?)
    suspend fun download(primary: String, backups: List<String>, destination: File,
        headers: Map<String, String>, onProgress: (Long, Long, Long) -> Unit) {
        var lastError: Exception? = null
        for (url in BiliStreamUrls.ordered(primary, backups)) {
            destination.delete()
            val working = File.createTempFile("bili-stream-", ".part", destination.parentFile)
            try {
                var offset = 0L; var total = -1L; var validator: String? = null
                var lastAt = System.nanoTime(); var lastBytes = 0L
                do {
                    val result = chunk(url, offset, total, validator, working, headers) { bytes, length ->
                        val now = System.nanoTime()
                        if (now - lastAt >= 150_000_000L) {
                            onProgress(bytes, length, ((bytes - lastBytes).coerceAtLeast(0) * 1_000_000_000L / (now - lastAt)))
                            lastAt = now; lastBytes = bytes
                        }
                    }
                    require(result.size > offset) { "B站视频分段未返回新数据" }
                    offset = result.size; total = result.total; validator = result.validator
                } while (total > 0 && offset < total)
                if (total > 0 && offset != total) throw IOException("B站视频流长度不完整")
                currentCoroutineContext().ensureActive()
                if (!working.renameTo(destination)) working.copyTo(destination, overwrite = true)
                onProgress(offset, total, 0)
                return
            } catch (cancelled: CancellationException) { destination.delete(); throw cancelled }
            catch (error: Exception) { destination.delete(); lastError = error }
            finally { working.delete() }
        }
        throw IOException("B站视频流下载失败，请稍后重试或检查网络代理", lastError)
    }
    private suspend fun chunk(url: String, offset: Long, expectedTotal: Long, validator: String?,
        destination: File, headers: Map<String, String>, progress: (Long, Long) -> Unit): Chunk = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + chunkBytes - 1}")
            .header("Accept-Encoding", "identity")
        headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }.forEach { (key,value) -> request.header(key,value) }
        validator?.let { request.header("If-Range", it) }
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                val result = runCatching { response.use {
                    val body = it.body ?: throw IOException("B站视频流为空")
                    val range = HttpRangePolicy.evaluate(it.code, it.header("Content-Range"), offset, body.contentLength())
                    val entity = it.header("ETag")?.takeUnless { tag -> tag.startsWith("W/") } ?: it.header("Last-Modified")
                    if (validator != null && entity != validator) throw IOException("B站视频流已改变，请重新下载")
                    if (expectedTotal > 0 && range.total != expectedTotal) throw IOException("B站视频流总长度发生变化")
                    if (it.code == 206 && range.total <= 0) throw IOException("B站视频分段缺少总长度")
                    if (range.complete) return@use Chunk(offset, range.total, entity)
                    val start = if (range.append) offset else 0L
                    val expectedChunk = if (it.code == 206) it.header("Content-Range")!!.substringAfter('-').substringBefore('/').toLong() - start + 1 else body.contentLength()
                    var bytes = 0L
                    RandomAccessFile(destination, "rw").use { output ->
                        output.setLength(start); output.seek(start)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw IOException("已取消")
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count); bytes += count
                                progress(start + bytes, range.total)
                            }
                        }
                    }
                    if (bytes <= 0 || (expectedChunk >= 0 && bytes != expectedChunk)) throw IOException("B站视频分段被截断")
                    Chunk(start + bytes, range.total, entity)
                } }
                if (continuation.isActive) continuation.resumeWith(result)
                else destination.delete()
            }
        })
    }
}
