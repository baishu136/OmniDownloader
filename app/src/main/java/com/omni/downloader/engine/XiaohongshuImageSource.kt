package com.omni.downloader.engine

import kotlinx.serialization.json.*
import java.net.URI

/** H5 styles can burn a platform logo into the image. Use the same asset's source route. */
internal object XiaohongshuImageSource {
    private val asset = Regex("(?:[A-Za-z0-9_-]+/){0,7}[A-Za-z0-9_-]{16,160}")
    private fun assetUrl(key: String) = key.takeIf { it.length <= 512 && asset.matches(it) }
        ?.let { "https://ci.xiaohongshu.com/$it?imageView2/format/jpg" }

    fun originalUrl(image: JsonObject?): String? {
        if (image == null) return null
        assetUrl((image["fileId"] as? JsonPrimitive)?.contentOrNull.orEmpty())?.let { return it }
        val urls = listOf("original", "urlOriginal", "originalUrl", "original_url", "url", "urlDefault", "urlPre")
            .mapNotNull { (image[it] as? JsonPrimitive)?.contentOrNull } +
            (image["infoList"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull }
        return urls.firstNotNullOfOrNull(::originalUrl)
    }

    fun originalUrl(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null) return null
        if (host == "ci.xiaohongshu.com") return url
        if (!host.startsWith("sns-webpic-") || !host.endsWith(".xhscdn.com")) return null
        assetUrl(uri.rawPath.substringAfterLast('/').substringBefore('!'))
    }.getOrNull()

    fun downloadUrl(url: String): String {
        originalUrl(url)?.let { return it }
        val styled = runCatching { URI(url).let {
            val host = it.host.orEmpty().lowercase(java.util.Locale.ROOT)
            host.startsWith("sns-webpic-") && host.endsWith(".xhscdn.com") && '!' in it.path
        } }.getOrDefault(false)
        check(!styled) { "[MEDIA_UNCONFIRMED] 未能定位小红书标准图片源，请重新解析；未下载水印预览图" }
        return url
    }
}
