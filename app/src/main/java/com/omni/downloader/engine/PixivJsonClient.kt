package com.omni.downloader.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Applies the saved session to the actual API host/path, including host-only
 * www.pixiv.net cookies. No anonymous retry is used for access failures. */
internal class PixivJsonClient(
    private val client: OkHttpClient,
    private val cookieForUrl: (String) -> String?,
) {
    fun get(url: String, knownRestrictedArtwork: Boolean = false, retry: Boolean = true): Result<JSONObject> {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://www.pixiv.net/").header("Accept", "application/json")
        cookieForUrl(url)?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
        return runCatching {
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val restricted404 = response.code == 404 && knownRestrictedArtwork
                    val code = when {
                        restricted404 || response.code in setOf(401, 403) || response.code in 300..399 -> MediaErrorCode.AUTH_REQUIRED
                        response.code in setOf(404, 410) -> MediaErrorCode.CONTENT_UNAVAILABLE
                        response.code == 429 -> MediaErrorCode.RATE_LIMITED
                        else -> MediaErrorCode.NETWORK_ERROR
                    }
                    val detail = when {
                        restricted404 -> "已确认作品存在，但当前会话未获准读取受限动图资源（HTTP 404）；请在 Pixiv 登录可查看该作品的账号，保存会话并确认 Pixiv Cookie 已开启后重试"
                        response.code == 404 -> "作品接口 HTTP 404；请在 Pixiv 网页确认该账号能访问作品，并检查 Pixiv Cookie 已开启。匿名限制与作品删除均可能返回 404"
                        else -> "HTTP ${response.code}"
                    }
                    error(MediaErrors.describe(code, detail))
                }
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optBoolean("error", false)) {
                    error(MediaErrors.describe(MediaErrorCode.CONTENT_UNAVAILABLE,
                        root.optString("message").ifBlank { "Pixiv 返回错误" }))
                }
                root
            }
        }.recoverCatching { error ->
            if (!retry || MediaErrors.codeOf(error) != MediaErrorCode.NETWORK_ERROR) throw error
            Thread.sleep(500)
            get(url, knownRestrictedArtwork, retry = false).getOrThrow()
        }
    }
}
