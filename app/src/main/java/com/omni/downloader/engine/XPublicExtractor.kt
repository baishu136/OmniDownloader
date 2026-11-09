package com.omni.downloader.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Independent official public endpoint using Android TLS, never an account Cookie header. */
internal object XPublicExtractor {
    private val client = OkHttpClient.Builder().proxySelector(NetworkProxy)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()

    fun extract(context: Context, url: String, proxyUrl: String = ""): JSONObject {
        val identifier = Regex("/status/(\\d+)").find(url)?.groupValues?.get(1)
            ?: error("[MEDIA_ID_MISMATCH] 需要单条 X 帖子链接")
        // Same token expression as upstream TwitterIE; evaluate only a validated numeric ID.
        val quickJs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        val jsBuilder = ProcessBuilder(quickJs.path, "-e",
            "console.log(((Number('$identifier') / 1e15) * Math.PI).toString(36).replace(/(0+|\\.)/g, ''))")
        jsBuilder.environment()["LD_LIBRARY_PATH"] = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib").path
        val process = jsBuilder.start()
        val token = try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "[ENGINE_UNAVAILABLE] JS 运行时超时" }
            process.inputStream.bufferedReader().readText().trim().also {
                check(process.exitValue() == 0 && it.matches(Regex("[1-9a-z]+"))) { "[ENGINE_UNAVAILABLE] JS 运行时未返回有效签名" }
            }
        } finally { process.destroy() }
        val request = Request.Builder().url("https://cdn.syndication.twimg.com/tweet-result?id=$identifier&token=$token")
            .header("User-Agent", "Googlebot").build()
        val data = client.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> error("[AUTH_REQUIRED] 官方公开接口未允许访问目标内容")
                404, 410 -> error("[CONTENT_UNAVAILABLE] 目标内容当前不可用")
                429 -> error("[RATE_LIMITED] 公开接口请求受限，请稍后再试")
            }
            check(response.isSuccessful) { "[NETWORK_ERROR] 公开接口 HTTP ${response.code}" }
            val body = response.body ?: error("[MEDIA_UNCONFIRMED] 接口未返回媒体")
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 4 * 1024 * 1024) { "[EXTRACTOR_CHANGED] 媒体元数据超过限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        check(data.optString("id_str", data.optString("id")) == identifier) { "[MEDIA_ID_MISMATCH] 公开接口未确认原帖子" }
        val media = data.optJSONArray("mediaDetails") ?: error("[MEDIA_UNCONFIRMED] 未取得目标帖子的媒体信息")
        val entries = JSONArray()
        val requestedIndex = Regex("/video/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        for (index in 0 until media.length()) {
            val item = media.optJSONObject(index) ?: continue
            val type = item.optString("type")
            if (type !in setOf("video", "animated_gif") || (requestedIndex != null && requestedIndex != index + 1)) continue
            val video = item.optJSONObject("video_info") ?: continue
            val variants = video.optJSONArray("variants") ?: continue
            val formats = JSONArray()
            var mediaId = item.optString("id_str").ifBlank { "$identifier:${index + 1}" }
            for (i in 0 until variants.length()) {
                val variant = variants.optJSONObject(i) ?: continue
                val source = variant.optString("url")
                if (!source.startsWith("https://video.twimg.com/")) continue
                if (source.contains(".m3u8")) {
                    // Enumerate the actual HLS variants so the fallback does not impose a 720p ceiling.
                    runCatching {
                        val requestInfo = YtDlpOptionsFactory.metadataRequest(source, LinkNormalizer.PLATFORM_GENERIC, proxyUrl, false)
                        requestInfo.addOption("--socket-timeout", "8")
                        requestInfo.addOption("--retries", "0")
                        requestInfo.addOption("--extractor-retries", "0")
                        val raw = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(requestInfo).out.trim()
                        val hls = JSONObject(raw).optJSONArray("formats")
                        if (hls != null) appendHlsFormats(formats, hls)
                    }
                    continue
                }
                if (variant.optString("content_type") != "video/mp4") continue
                Regex("/(?:ext_tw_video|amplify_video)/(\\d+)").find(source)?.groupValues?.get(1)?.let { mediaId = it }
                val size = Regex("/(\\d+)x(\\d+)/").find(source)
                val original = item.optJSONObject("original_info")
                formats.put(JSONObject().put("format_id", "http-${variant.optLong("bitrate", i.toLong())}")
                    .put("url", source).put("ext", "mp4").put("protocol", "https")
                    .put("width", size?.groupValues?.get(1)?.toIntOrNull() ?: original?.optInt("width") ?: 0)
                    .put("height", size?.groupValues?.get(2)?.toIntOrNull() ?: original?.optInt("height") ?: 0)
                    .put("vcodec", "unknown").put("acodec", if (type == "animated_gif") "none" else "unknown")
                    .put("http_headers", JSONObject().put("Referer", "https://x.com/")))
            }
            if (formats.length() == 0) continue
            entries.put(JSONObject().put("id", mediaId).put("title", data.optString("text", identifier).take(240))
                .put("uploader", data.optJSONObject("user")?.optString("name").orEmpty())
                .put("thumbnail", item.optString("media_url_https"))
                .put("webpage_url", "https://x.com/i/status/$identifier/video/${index + 1}")
                .put("duration", video.optLong("duration_millis") / 1000.0).put("formats", formats)
                .put("extractor", "TwitterPublicNative").put("_omni_route", "public_native_tls"))
        }
        check(entries.length() > 0) { "[MEDIA_UNCONFIRMED] 官方公开接口未提供指定媒体的 MP4，需验证会话或重试" }
        return if (entries.length() == 1) entries.getJSONObject(0) else JSONObject().put("_type", "playlist")
            .put("id", identifier).put("title", identifier).put("entries", entries)
    }

    /** HLS video variants may be silent; their companion audio renditions must
     * remain in the same snapshot for exact-video+bestaudio selection. */
    internal fun appendHlsFormats(target: JSONArray, hls: JSONArray) {
        for (index in 0 until hls.length()) {
            val source = hls.optJSONObject(index) ?: continue
            val format = JSONObject(source.toString())
            format.put("format_id", "hls-${format.optString("format_id")}")
            // Generic HLS metadata can identify audio via vcodec=none without
            // naming its codec. Preserve that as audio in the app's format UI.
            if (format.optString("vcodec") == "none" && format.optString("acodec").isBlank())
                format.put("acodec", "unknown")
            target.put(format)
        }
    }
}
