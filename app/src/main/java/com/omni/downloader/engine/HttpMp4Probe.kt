package com.omni.downloader.engine

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.IOException

/** Metadata-only sparse ranges. Never reads through a large mdat or downloads
 * the complete moov; a failed probe cannot invent a resolution. */
internal object HttpMp4Probe {
    data class Info(val width: Int, val height: Int, val fileBytes: Long?, val durationMs: Long?)
    private const val BLOCK = 65536
    fun probe(url: String, baseClient: OkHttpClient, headers: Map<String, String>, budgetMillis: Long = 12_000): Info? = runCatching {
        val client = baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(12, TimeUnit.SECONDS).build()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis.coerceIn(1, 12_000))
        val blocks = linkedMapOf<Long, ByteArray>()
        var requests = 0
        var total: Long? = null
        fun block(start: Long): ByteArray? {
            if (blocks.size >= 8 || System.nanoTime() >= deadline || start < 0 || start > Long.MAX_VALUE - BLOCK || total?.let { start >= it } == true) return null
            repeat(2) { attempt ->
              if (requests++ >= 12 || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) return null
              val builder = Request.Builder().url(url)
              headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }.forEach { (key, value) -> builder.header(key, value) }
              builder.header("Range", "bytes=$start-${start + BLOCK - 1}").header("Accept-Encoding", "identity")
              val call = client.newCall(builder.build())
              call.timeout().deadlineNanoTime(deadline)
              try { return call.execute().use { response ->
                if (response.code in setOf(408, 500, 502, 503, 504)) throw IOException("Transient media probe HTTP ${response.code}")
                if (response.code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(response.header("Content-Range").orEmpty()) ?: return null
                    val from = range.groupValues[1].toLongOrNull() ?: return null
                    val end = range.groupValues[2].toLongOrNull() ?: return null
                    val length = range.groupValues[3].toLongOrNull()
                    if (from != start || end < from || end - from >= BLOCK || (length != null && (end >= length || total?.let { it != length } == true))) return null
                    if (length != null) total = length
                } else if (response.code == 200 && start == 0L) {
                    response.body?.contentLength()?.takeIf { it >= 0 }?.let { total = it }
                } else return null
                val body = response.body ?: return null
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream(BLOCK)
                    val buffer = ByteArray(8192)
                    while (output.size() < BLOCK) {
                        val count = input.read(buffer, 0, minOf(buffer.size, BLOCK - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.isEmpty()) return null
                blocks[start] = bytes
                bytes
              } } catch (error: IOException) {
                if (attempt == 1 || Thread.currentThread().isInterrupted) return null
              }
            }
            return null
        }
        val readRange: (Long, Int) -> ByteArray? = reader@ { offset, count ->
            if (offset < 0 || count !in 1..16 || offset > Long.MAX_VALUE - count) null else {
                val bytes = ByteArray(count)
                var written = 0
                while (written < count) {
                    val at = offset + written
                    // CDNs may clamp a requested 64KiB range to a smaller one.
                    // Cache actual returned spans, not the whole aligned block.
                    val cached = blocks.entries.firstOrNull { at >= it.key && at - it.key < it.value.size }
                    val start = cached?.key ?: at
                    val data = cached?.value ?: block(start) ?: return@reader null
                    val within = (at - start).toInt()
                    val size = minOf(count - written, data.size - within)
                    if (size <= 0) return@reader null
                    data.copyInto(bytes, written, within, within + size)
                    written += size
                }
                bytes
            }
        }
        val dimensions = Mp4Dimensions.readSparse(readRange) ?: return null
        // Duration is optional; a timed-out extra range must not discard already
        // confirmed dimensions (especially for very large original files).
        val duration = runCatching { Mp4Dimensions.readDurationSparse(readRange) }.getOrNull()
        Info(dimensions.first, dimensions.second, total, duration)
    }.getOrNull()
}
