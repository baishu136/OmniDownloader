package com.omni.downloader.engine

import java.io.IOException

/** Validate a resume response before it can append bytes or report completion. */
internal object HttpRangePolicy {
    data class Range(val append: Boolean, val total: Long, val complete: Boolean = false)

    fun evaluate(code: Int, header: String?, existing: Long, contentLength: Long): Range {
        if (code == 200) return Range(false, contentLength)
        if (code == 416) {
            val total = Regex("bytes \\*/(\\d+)").matchEntire(header.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (existing > 0 && total == existing) return Range(true, total, true)
            throw IOException("服务器无法续传当前片段，需从头重新下载")
        }
        if (code != 206) throw IOException("HTTP $code")
        val fields = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(header.orEmpty())?.groupValues
            ?: throw IOException("服务器返回无效的 Content-Range")
        val start = fields[1].toLongOrNull() ?: throw IOException("续传起点无效")
        val end = fields[2].toLongOrNull() ?: throw IOException("续传终点无效")
        val total = fields[3].toLongOrNull()
        if (start != existing || end < start || end == Long.MAX_VALUE ||
            (total != null && total <= end) || (contentLength >= 0 && contentLength != end - start + 1))
            throw IOException("续传区间与本地片段不一致，需从头重新下载")
        return Range(existing > 0, total ?: -1)
    }
}

internal class MediaHttpException(val status: Int, message: String) : IOException(message)
