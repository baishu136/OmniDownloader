package com.omni.downloader.engine

/**
 * 稳定的错误码。UI、日志和诊断导出使用同一套枚举，
 * 不再依赖某个平台返回的英文原文来判断下一步动作。
 */
enum class MediaErrorCode(val code: String, val userHint: String) {
    EXPORT_FAILED("EXPORT_FAILED", "成品导出失败，可更换目录后重试导出"),
    PREVIEW_ONLY("PREVIEW_ONLY", "当前仅取得试看内容，请确认目标视频的充电权益"),
    MEDIA_ID_MISMATCH("MEDIA_ID_MISMATCH", "平台未返回目标作品，已拒绝下载其他内容"),
    MEDIA_UNCONFIRMED("MEDIA_UNCONFIRMED", "本次未取得媒体，请重试或验证会话"),
    BROWSER_ASSIST_REQUIRED("BROWSER_ASSIST_REQUIRED", "请在内置浏览器打开并播放目标内容后捕获媒体"),
    ACCESS_DENIED("ACCESS_DENIED", "当前账号没有目标内容的可用访问权限"),
    ACCESS_UNCONFIRMED("ACCESS_UNCONFIRMED", "当前无法确认完整内容的访问证据"),
    AUTH_REQUIRED("AUTH_REQUIRED", "需要登录后才能继续，请导入对应平台的会话"),
    COOKIE_INVALID("COOKIE_INVALID", "会话文件无效或无法读取，请重新登录或导入 Cookie"),
    CHALLENGE_REQUIRED("CHALLENGE_REQUIRED", "平台要求完成验证码或人机验证"),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁，请稍后再试"),
    NETWORK_ERROR("NETWORK_ERROR", "网络连接失败，请检查网络或代理"),
    PROXY_ERROR("PROXY_ERROR", "代理握手失败；使用系统 VPN 时可清空应用代理，或核对代理协议、端口和认证"),
    SOURCE_EXPIRED("SOURCE_EXPIRED", "媒体地址已过期，正在尝试重新解析"),
    QUALITY_UNAVAILABLE("QUALITY_UNAVAILABLE", "所选清晰度当前不可用"),
    NO_MEDIA("NO_MEDIA", "该内容不是受支持的视频/音频"),
    CONTENT_UNAVAILABLE("CONTENT_UNAVAILABLE", "内容不可用、已删除或无权访问"),
    EXTRACTOR_CHANGED("EXTRACTOR_CHANGED", "平台页面结构已变化，解析规则需要更新"),
    ENGINE_UNAVAILABLE("ENGINE_UNAVAILABLE", "下载引擎组件缺失或不兼容"),
    POSTPROCESS_FAILED("POSTPROCESS_FAILED", "下载完成但合并或转码失败"),
    UNKNOWN("UNKNOWN", "未知错误")
}

object MediaErrors {

    /** 根据错误文本与 HTTP 状态码归类。顺序从具体到宽泛，避免被宽泛规则抢先命中。 */
    fun classify(message: String, httpStatus: Int? = null): MediaErrorCode {
        val text = message.lowercase()
        MediaErrorCode.values().firstOrNull { message.contains("[${it.code}]") }?.let { return it }
        if (text.containsAny("invalid netscape format", "does not look like a netscape", "failed to load cookies", "cookieloaderror", "http.cookiejar.loaderror")) return MediaErrorCode.COOKIE_INVALID
        if (text.containsAny("tunnel connection failed", "unable to connect to proxy", "proxyerror", "https proxy")) return MediaErrorCode.PROXY_ERROR
        if (text.contains("no video could be found")) return MediaErrorCode.MEDIA_UNCONFIRMED
        if (text.containsAny("isn't available to everyone", "can't be seen by certain audiences", "not available to everyone")) return MediaErrorCode.ACCESS_DENIED
        if (httpStatus != null) {
            when (httpStatus) {
                401, 407 -> return MediaErrorCode.AUTH_REQUIRED
                403 -> return MediaErrorCode.CONTENT_UNAVAILABLE
                404, 410 -> return MediaErrorCode.CONTENT_UNAVAILABLE
                429 -> return MediaErrorCode.RATE_LIMITED
            }
        }
        return when {
            text.containsAny(
                "captcha", "challenge", "verify you are human", "robot check",
                "not a bot", "confirm you're not a bot", "人机验证", "验证码", "滑块"
            ) -> MediaErrorCode.CHALLENGE_REQUIRED

            text.containsAny(
                "sign in to confirm", "sign in", "log in", "login required",
                "cookies are needed", "authentication", "account credentials",
                "请登录", "需要登录", "登录后"
            ) -> MediaErrorCode.AUTH_REQUIRED

            text.containsAny("429", "rate limit", "too many requests", "请求过于频繁", "限流")
                -> MediaErrorCode.RATE_LIMITED

            text.containsAny("timed out", "timeout", "connection reset", "connection aborted", "connection closed", "unexpected_eof", "eof occurred", "failed to connect", "unable to resolve host")
                -> MediaErrorCode.NETWORK_ERROR

            text.containsAny(
                "requested format is not available", "no video formats",
                "format is not available", "requested format not available",
                "所选清晰度", "格式不可用"
            ) -> MediaErrorCode.QUALITY_UNAVAILABLE

            text.containsAny(
                "instance not initialized", "module not found",
                "no module named", "cannot locate symbol", "dlopen failed", "exec format error",
                "python not found", "python: not found", "ffmpeg not", "ejs is not installed",
                "engine not ready", "引擎未就绪"
            ) -> MediaErrorCode.ENGINE_UNAVAILABLE

            text.containsAny(
                "unable to extract", "no such field", "unexpected response",
                "parse error", "结构", "规则需要更新"
            ) -> MediaErrorCode.EXTRACTOR_CHANGED

            text.containsAny(
                "no video", "not a video", "unsupported url", "没有视频",
                "不是视频", "仅支持图文", "no media"
            ) -> MediaErrorCode.NO_MEDIA

            text.containsAny(
                "private video", "video unavailable", "removed", "deleted",
                "not available in your country", "不可用", "已删除", "无权访问"
            ) -> MediaErrorCode.CONTENT_UNAVAILABLE

            text.containsAny(
                "timed out", "timeout", "connection refused", "connection reset",
                "failed to connect", "dns", "network is unreachable", "unable to resolve host",
                "proxy", "网络", "代理"
            ) -> MediaErrorCode.NETWORK_ERROR

            text.containsAny(
                "403", "expired", "signature", "过期", "token expired"
            ) -> MediaErrorCode.SOURCE_EXPIRED

            text.containsAny(
                "merge", "merging", "postprocess", "conversion", "转码", "合并"
            ) -> MediaErrorCode.POSTPROCESS_FAILED

            else -> MediaErrorCode.UNKNOWN
        }
    }

    fun codeOf(error: Throwable): MediaErrorCode {
        var cause: Throwable? = error
        while (cause?.cause != null && cause.cause !== cause) {
            cause = cause.cause
        }
        return classify(cause?.message ?: error.message ?: "")
    }

    /** 面向用户的单行错误，保留稳定错误码便于客服与诊断。 */
    fun describe(code: MediaErrorCode, detail: String? = null): String {
        val safeDetail = LogSanitizer.sanitize(detail.orEmpty()).trim()
        return if (safeDetail.isBlank()) {
            "[${code.code}] ${code.userHint}"
        } else {
            "[${code.code}] ${code.userHint} ($safeDetail)"
        }
    }

    fun describe(error: Throwable): String = describe(codeOf(error), error.message)

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
