package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.repository.SessionRepository
import java.io.File

/**
 * 一次解析/下载使用的会话快照。
 * 原生 HTTP 使用 cookieHeader，yt-dlp 使用临时 Netscape cookieFile。
 * 任务结束后由调用方删除 cookieFile。
 */
data class RequestContext(
    val platform: String,
    val cookieHeader: String?,
    val cookieFile: File?
) {
    fun cleanup() {
        runCatching { cookieFile?.delete() }
    }
}

object RequestContextFactory {
    fun create(context: Context, platform: String): RequestContext {
        val repository = SessionRepository.getInstance(context)
        return RequestContext(
            platform = platform,
            cookieHeader = repository.cookieHeader(platform),
            cookieFile = repository.tempCookieFile(platform)
        )
    }
}
