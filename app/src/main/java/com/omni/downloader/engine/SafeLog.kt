package com.omni.downloader.engine

/** Never hand raw signed URLs, headers or exception causes to logcat. */
object SafeLog {
    private fun text(message: String, error: Throwable?) =
        LogSanitizer.sanitize(message + (error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    fun d(tag: String, message: String, error: Throwable? = null) = android.util.Log.d(tag, text(message, error))
    fun i(tag: String, message: String, error: Throwable? = null) = android.util.Log.i(tag, text(message, error))
    fun w(tag: String, message: String, error: Throwable? = null) = android.util.Log.w(tag, text(message, error))
    fun e(tag: String, message: String, error: Throwable? = null) = android.util.Log.e(tag, text(message, error))
    fun v(tag: String, message: String, error: Throwable? = null) = android.util.Log.v(tag, text(message, error))
}
