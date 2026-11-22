package com.omni.downloader.engine

/** Identity evidence and fallback decisions shared by the native X response path. */
internal object XPublicPolicy {
    fun verifyRoot(target: String, stringId: String?, fallbackId: String? = null) {
        val actual = sequenceOf(stringId, fallbackId).mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() && value != "null" } }.firstOrNull()
        check(actual != null && actual.matches(Regex("[0-9]+"))) {
            "[MEDIA_UNCONFIRMED] 公开接口未返回可确认的帖子 ID，请重试或验证会话"
        }
        check(actual == target) { "[MEDIA_ID_MISMATCH] 公开接口明确返回了其他帖子，已拒绝使用" }
    }
    fun <T> confirmed(fetch: (Int) -> T, verify: (T) -> Unit): T {
        repeat(2) { attempt ->
            try { val value = fetch(attempt); verify(value); return value }
            catch (error: Exception) {
                if (attempt == 1 || (MediaErrors.codeOf(error) != MediaErrorCode.MEDIA_UNCONFIRMED && !transientTransport(error))) throw error
            }
        }
        error("Unreachable")
    }

    private fun transientTransport(error: Throwable): Boolean {
        if (error is InterruptedException || error is java.util.concurrent.CancellationException) return false
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        if (chain.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException }) return false
        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (listOf("certificate", "certpath", "trust anchor", "[auth_required]", "[rate_limited]", "[content_unavailable]").any { it in message }) return false
        return chain.any { it is java.io.EOFException || it is java.net.SocketTimeoutException } ||
            listOf("unexpected_eof", "unexpected eof", "eof occurred", "connection reset", "connection aborted", "remote end closed", "server disconnected").any { it in message }
    }

    fun shouldTryPublic(message: String): Boolean {
        val code = MediaErrors.classify(message)
        if (code in setOf(MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.ACCESS_DENIED,
                MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrorCode.RATE_LIMITED)) return false
        return code in setOf(MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrorCode.EXTRACTOR_CHANGED,
            MediaErrorCode.NETWORK_ERROR) || listOf("502", "503", "remote end closed",
            "unexpected_eof", "server disconnected", "syndication endpoint returned empty json response").any { it in message.lowercase() }
    }

    fun canTrySecondary(message: String): Boolean = MediaErrors.classify(message) !in setOf(
        MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.ACCESS_DENIED,
        MediaErrorCode.RATE_LIMITED, MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.MEDIA_ID_MISMATCH)
}
