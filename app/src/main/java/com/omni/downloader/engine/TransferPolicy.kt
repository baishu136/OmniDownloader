package com.omni.downloader.engine

import java.io.IOException

internal class SerialTransferFallback : IOException("切回兼容传输")

internal class SlowMediaSource : IOException("[NETWORK_ERROR] 媒体源持续低速，正在恢复同一作品")

internal object TransferPolicy {
    const val FRAGMENTS = 4
    const val MIN_PARALLEL_BYTES = 8L * 1024 * 1024
    data class Part(val start: Long, val end: Long) { val length get() = end - start + 1 }
    fun parts(total: Long, count: Int = 4): List<Part> {
        require(total > 0 && count in 1..4)
        val chunk = (total - 1) / count + 1
        return (0 until count).mapNotNull { i -> (i * chunk).takeIf { it < total }?.let { Part(it, minOf(total - 1, it + chunk - 1)) } }
    }
    fun strongTag(value: String?) = value?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
    fun validateResume(existing: Long, expected: String?, actual: String?, code: Int) {
        if (existing > 0 && code in setOf(206, 416) && (expected.isNullOrBlank() || actual != expected))
            throw IOException("媒体实体已改变或无法确认，需从头重新下载")
    }
    fun isSlow(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(8).any { it is SlowMediaSource }
    fun canContinueOriginal(error: Throwable?): Boolean = error == null || MediaErrors.codeOf(error) !in setOf(
        MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.ACCESS_DENIED,
        MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.QUALITY_UNAVAILABLE,
        MediaErrorCode.RATE_LIMITED)
}

/** One observer per attempt, using a monotonic clock and aggregate transfer bytes. */
internal class SlowTransferWatch(private val start: Long, private val warmupMs: Long = 10_000,
    private val windowMs: Long = 20_000, private val minimumRate: Long = 64 * 1024) {
    private var windowAt = start
    private var windowBytes = 0L
    private var warmed = false
    @Synchronized fun sample(now: Long, bytes: Long, total: Long): Boolean {
        if (!warmed) {
            if (now - start < warmupMs) return false
            warmed = true; windowAt = now; windowBytes = bytes; return false
        }
        if (now - windowAt < windowMs) return false
        val slow = total > 0 && total - bytes > 4L * 1024 * 1024 &&
            (bytes - windowBytes).coerceAtLeast(0) * 1000 / (now - windowAt) < minimumRate
        windowAt = now; windowBytes = bytes
        return slow
    }
}
