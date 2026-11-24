package com.omni.downloader.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Parallel only with positive range support and a strong representation validator.
 * Each part is private; nothing is published until every byte interval is verified. */
internal class ParallelRangeDownloader(client: OkHttpClient,
    private val minimumBytes: Long = TransferPolicy.MIN_PARALLEL_BYTES) {
    // The serial owner handles redirects. Extra range requests must stay on the
    // advertised endpoint rather than forwarding its captured headers elsewhere.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    companion object { private val permits = Semaphore(4) }
    private class UnsupportedRange : IOException("服务器不支持可靠分段")
    private data class Entity(val length: Long, val tag: String, val url: String)

    suspend fun download(request: Request, destination: File, progress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        fun checkHttp(response: Response) {
            if (response.code in setOf(401,403,404,410,429)) throw MediaHttpException(response.code, "媒体源 HTTP ${response.code}")
        }
        val entity = try { BiliHttp.read(client, request.newBuilder().header("Range", "bytes=0-0")
            .removeHeader("If-Range").header("Accept-Encoding", "identity").build()) { response ->
            checkHttp(response)
            if (response.code != 206 || response.request.url != request.url) return@read null
            val body = response.body ?: return@read null
            val parsed = HttpRangePolicy.evaluate(response.code, response.header("Content-Range"), 0, body.contentLength())
            val tag = TransferPolicy.strongTag(response.header("ETag")) ?: return@read null
            if (parsed.total < minimumBytes || response.header("Content-Range") != "bytes 0-0/${parsed.total}" ||
                response.header("Content-Encoding")?.let { it != "identity" } == true) return@read null
            body.byteStream().use { if (it.read() < 0 || it.read() >= 0) throw UnsupportedRange() }
            Entity(parsed.total, tag, response.request.url.toString())
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (error is MediaHttpException) throw error else return@withContext false }
            ?: return@withContext false
        SafeFiles.ensureDir(destination.parentFile)
        if (entity.length > (destination.parentFile!!.usableSpace - 64L * 1024 * 1024) / 2) return@withContext false
        val folder = SafeFiles.createTempFile(destination.parentFile, "parallel-", ".tmp")
        check(folder.delete() && folder.mkdir())
        val bytes = AtomicLong(0)
        val progressLock = Any()
        try {
            val parts = coroutineScope {
                TransferPolicy.parts(entity.length).mapIndexed { index, part -> async {
                    permits.withPermit {
                        val output = File(folder, "$index.part")
                        val partJob = currentCoroutineContext().job
                        BiliHttp.read(client, request.newBuilder().url(entity.url)
                            .header("Range", "bytes=${part.start}-${part.end}").header("If-Range", entity.tag)
                            .header("Accept-Encoding", "identity").build()) { response ->
                            checkHttp(response)
                            val body = response.body ?: throw UnsupportedRange()
                            val expected = "bytes ${part.start}-${part.end}/${entity.length}"
                            if (response.code != 206 || response.header("Content-Range") != expected ||
                                response.header("ETag") != entity.tag || body.contentLength() != part.length ||
                                response.request.url.toString() != entity.url ||
                                response.header("Content-Encoding")?.let { it != "identity" } == true) throw UnsupportedRange()
                            var received = 0L
                            output.outputStream().use { sink -> body.byteStream().use { source ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    partJob.ensureActive(); job.ensureActive()
                                    val n = source.read(buffer); if (n < 0) break
                                    if (received + n > part.length) throw UnsupportedRange()
                                    partJob.ensureActive(); sink.write(buffer, 0, n); received += n
                                    synchronized(progressLock) { progress(bytes.addAndGet(n.toLong()), entity.length) }
                                }
                            } }
                            if (received != part.length) throw UnsupportedRange()
                        }
                        output
                    }
                } }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            destination.outputStream().use { sink -> parts.forEach { part ->
                currentCoroutineContext().ensureActive(); part.inputStream().use { it.copyTo(sink, 64 * 1024) }
            } }
            check(destination.length() == entity.length)
            progress(entity.length, entity.length)
            true
        } catch (cancelled: CancellationException) { destination.delete(); throw cancelled }
        catch (error: Exception) {
            destination.delete()
            if (error is MediaHttpException || TransferPolicy.isSlow(error)) throw error
            false // Restart the complete representation through the existing serial path.
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
}
