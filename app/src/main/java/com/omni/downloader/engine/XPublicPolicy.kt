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
            val value = fetch(attempt)
            try { verify(value); return value }
            catch (error: Exception) {
                if (attempt == 1 || MediaErrors.codeOf(error) != MediaErrorCode.MEDIA_UNCONFIRMED) throw error
            }
        }
        error("Unreachable")
    }

    fun shouldTryPublic(message: String): Boolean {
        val code = MediaErrors.classify(message)
        if (code in setOf(MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.ACCESS_DENIED,
                MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.MEDIA_ID_MISMATCH)) return false
        return code in setOf(MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrorCode.EXTRACTOR_CHANGED,
            MediaErrorCode.NETWORK_ERROR, MediaErrorCode.RATE_LIMITED) || listOf("502", "503", "remote end closed",
            "unexpected_eof", "server disconnected", "syndication endpoint returned empty json response").any { it in message.lowercase() }
    }

    fun canTrySecondary(message: String): Boolean = MediaErrors.classify(message) !in setOf(
        MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED, MediaErrorCode.ACCESS_DENIED,
        MediaErrorCode.RATE_LIMITED, MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.MEDIA_ID_MISMATCH)
}
