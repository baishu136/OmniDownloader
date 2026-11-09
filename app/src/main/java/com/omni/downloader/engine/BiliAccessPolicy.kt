package com.omni.downloader.engine

/** Permission evidence is separate from cookie presence and from stream quality. */
object BiliAccessPolicy {
    fun state(exclusive: Boolean?, accountVerified: Boolean, hasStream: Boolean,
              preview: Boolean, expectedMs: Long, returnedMs: Long): String {
        if (preview || (expectedMs > 0 && returnedMs > 0 && returnedMs + 2000 < expectedMs)) return "PREVIEW"
        if (exclusive == true && !accountVerified) return "DENIED"
        if (!hasStream || expectedMs <= 0 || returnedMs <= 0) return "UNKNOWN"
        return if (kotlin.math.abs(expectedMs - returnedMs) <= 2000 && (exclusive == false || accountVerified)) "FULL" else "UNKNOWN"
    }
}
