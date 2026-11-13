package com.omni.downloader.engine

internal enum class DouyinPageStatus { FOUND, EMPTY, AUTH_REQUIRED, CHALLENGE_REQUIRED, UNAVAILABLE, TIMEOUT, SCOPE_CHANGED }
internal data class DouyinPageResult(val status: DouyinPageStatus, val item: String? = null)

/** Android foreground adapter; absence means no background activity is started. */
internal fun interface DouyinPageLoader {
    suspend fun capture(url: String, proxy: String, scope: String): DouyinPageResult

    suspend fun captureProgressive(url: String, proxy: String, scope: String,
        onCandidate: suspend (String) -> Unit): DouyinPageResult = capture(url, proxy, scope)
}

internal object DouyinPageCapture {
    @Volatile var loader: DouyinPageLoader? = null
        private set
    fun attach(value: DouyinPageLoader) { loader = value }
    fun detach(value: DouyinPageLoader) { if (loader === value) loader = null }
}
