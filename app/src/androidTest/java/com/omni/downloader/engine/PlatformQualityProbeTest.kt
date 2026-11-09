package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

/** Live metadata and bounded resource-header checks; no user files or preferences are changed. */
class PlatformQualityProbeTest {
    @Test fun inspectRealReturnedResources() = runBlocking(Dispatchers.IO) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("url"))
        val expected = args.getString("expectedKind") ?: "VIDEO"
        val context = instrument.targetContext
        val platform = LinkNormalizer.platformOf(url)
        val proxy = args.getString("proxy") ?: SettingsRepository.getInstance(context).proxyUrl.value
        val saved = NetworkProxy.configuredUrl
        val row = JSONObject().put("caseId", args.getString("caseId")).put("platform", platform)
            .put("expectedKind", expected).put("success", false)
            .put("hasSession", (SessionRepository.getInstance(context).info(platform)?.cookieCount ?: 0) > 0)
            .put("network", if (proxy.isBlank()) "phone-direct" else "explicit-test-proxy")
        val client = OkHttpClient.Builder().proxySelector(NetworkProxy).cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(14, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val started = android.os.SystemClock.elapsedRealtime()
        fun sizeStatus(w: Int, h: Int, actualW: Int, actualH: Int) = when {
            actualW <= 0 || actualH <= 0 -> "RESOURCE_NOT_VERIFIED"
            w <= 0 || h <= 0 -> "METADATA_UNCONFIRMED"
            w == actualW && h == actualH -> "MATCH"
            w == actualH && h == actualW -> "ROTATED_MATCH"
            else -> "MISMATCH"
        }
        try {
            NetworkProxy.configuredUrl = proxy
            if (expected == "IMAGE_POST") {
                val post = SharedPython.run(context, "images", url, platform, proxy)
                val resources = post.getJSONArray("resources")
                check(resources.length() > 0) { "[NO_MEDIA] Empty image result" }
                row.put("success", true).put("mediaId", post.optString("contentId")).put("imageCount", resources.length())
                row.put("imageSizes", JSONArray().apply {
                    for (i in 0 until resources.length()) {
                        val r = resources.getJSONObject(i)
                        put(JSONArray(listOf(r.optInt("width"), r.optInt("height"))))
                    }
                })
                val sample = resources.getJSONObject(0)
                val request = Request.Builder().url(sample.getString("url"))
                sample.optJSONObject("headers")?.let { headers -> headers.keys().forEach { key -> request.header(key, headers.getString(key)) } }
                val referer = when (platform) { "Pixiv" -> "https://www.pixiv.net/"; "哔哩哔哩" -> "https://www.bilibili.com/"; else -> null }
                referer?.let { request.header("Referer", it) }
                DomesticMediaSource.headers(platform).forEach { (k,v) -> request.header(k,v) }
                runCatching {
                    client.newCall(request.build()).execute().use { response ->
                        row.put("resourceHttp", response.code)
                        if (response.isSuccessful) {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            val bounded = object : FilterInputStream(response.body!!.byteStream()) {
                                var left = 2 * 1024 * 1024
                                override fun read(): Int = if (left-- > 0) super.read() else -1
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    if (left <= 0) return -1
                                    val count = super.read(b, off, minOf(len, left)); if (count > 0) left -= count
                                    return count
                                }
                            }
                            bounded.use { BitmapFactory.decodeStream(it, null, options) }
                            row.put("actualSampleSize", JSONArray(listOf(options.outWidth, options.outHeight)))
                                .put("dimensionCheck", sizeStatus(sample.optInt("width"), sample.optInt("height"), options.outWidth, options.outHeight))
                        }
                    }
                }.onFailure { row.put("resourceError", LogSanitizer.sanitize(it.message.orEmpty()).take(300)) }
                if (!row.has("actualSampleSize")) {
                    val actual = pythonHeaderProbe(context, platform, proxy, sample.getString("url"), sample.optJSONObject("headers") ?: JSONObject(), false)
                    row.put("runtimeResourceCheck", actual)
                    if (actual.optInt("width") > 0) row.put("actualSampleSize", JSONArray(listOf(actual.getInt("width"), actual.getInt("height"))))
                        .put("dimensionCheck", sizeStatus(sample.optInt("width"), sample.optInt("height"), actual.getInt("width"), actual.getInt("height")))
                }
            } else {
                var metadata = DownloadEngine.fetchVideoMetadata(context, url, proxy).getOrThrow()
                row.put("mediaId", metadata.mediaId).put("children", metadata.multiMediaList.size).put("kind", metadata.contentKind)
                if (metadata.multiMediaList.isNotEmpty()) metadata = metadata.multiMediaList.first()
                val formats = metadata.availableVideoFormats.filter { !it.isSynthetic && !it.isAudioOnly }
                check(formats.isNotEmpty()) { "[NO_MEDIA] No measured video format" }
                row.put("success", true).put("videoFormats", formats.size)
                    .put("formatSizes", JSONArray().apply { formats.take(80).forEach { put(JSONArray(listOf(it.width, it.height))) } })
                val selected = formats.maxByOrNull { it.width.toLong() * it.height }!!
                row.put("maxSize", JSONArray(listOf(selected.width, selected.height)))
                val source = selected.sourceUrl.ifBlank { selected.formatId.takeIf { it.startsWith("http") }.orEmpty() }
                if (source.startsWith("http") && !selected.formatId.startsWith("ugoira_")) {
                    val headers = DomesticMediaSource.headers(platform) + selected.sourceHeaders
                    val actual = HttpMp4Probe.probe(source, client, headers)
                    if (actual != null) row.put("actualSampleSize", JSONArray(listOf(actual.width, actual.height)))
                        .put("dimensionCheck", sizeStatus(selected.width, selected.height, actual.width, actual.height))
                    else {
                        val actualPython = pythonHeaderProbe(context, platform, proxy, source, JSONObject(headers), true)
                        row.put("runtimeResourceCheck", actualPython)
                        if (actualPython.optInt("width") > 0) row.put("actualSampleSize", JSONArray(listOf(actualPython.getInt("width"), actualPython.getInt("height"))))
                            .put("dimensionCheck", sizeStatus(selected.width, selected.height, actualPython.getInt("width"), actualPython.getInt("height")))
                        else row.put("dimensionCheck", "RESOURCE_NOT_VERIFIED")
                    }
                } else row.put("dimensionCheck", if (selected.width > 0 && selected.height > 0) "UPSTREAM_METADATA_ONLY" else "METADATA_UNCONFIRMED")
            }
        } catch (error: Exception) {
            row.put("errorCode", MediaErrors.codeOf(error).code).put("error", LogSanitizer.sanitize(error.message.orEmpty()).takeLast(1000))
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            NetworkProxy.configuredUrl = saved
        }
        row.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
        File(context.cacheDir, "platform-quality-result.json").writeText(row.toString())
        instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_QUALITY=$row\n") })
    }

    private fun pythonHeaderProbe(context: android.content.Context, platform: String, proxy: String, source: String, headers: JSONObject, video: Boolean): JSONObject {
        SharedPython.install(context)
        val scope = RequestContextFactory.create(context, platform)
        val file = File.createTempFile("quality-header-", ".json", context.cacheDir)
        val errors = File.createTempFile("quality-header-", ".err", context.cacheDir)
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val home = File(base, "packages/python/usr")
        val code = """
import sys,json,omni_media as m
d=json.load(open(sys.argv[1],encoding='utf-8')); result={}
try:
 with m.session(d.get('cookies'),d['proxy']) as s:
  for span in (['bytes=0-131071','bytes=-131072'] if d['video'] else ['bytes=0-65535']):
   with s.get(d['url'],headers={**d['headers'],'Range':span,'Accept-Encoding':'identity'},timeout=(6,8),stream=True) as r:
    result['http']=r.status_code
    if r.status_code not in (200,206): break
    data=r.raw.read(131072 if d['video'] else 65536,decode_content=True)
    size=m.mp4_dimensions(data) if d['video'] else m.image_dimensions(data)
    if size:result.update(width=size[0],height=size[1]);break
except Exception as e: result['errorType']=type(e).__name__
print(json.dumps(result))
""".trimIndent()
        try {
            file.writeText(JSONObject().put("url", source).put("headers", headers).put("proxy", proxy).put("video", video)
                .put("cookies", scope.cookieFile?.path ?: JSONObject.NULL).toString())
            val builder = ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libpython.so", "-c", code, file.path)
                .redirectError(errors)
            builder.environment().apply {
                put("PYTHONHOME", home.path); put("PYTHONPATH", File(base, "yt-dlp/yt-dlp").path)
                put("LD_LIBRARY_PATH", "${home.path}/lib:${base.path}/packages/ffmpeg/usr/lib:${base.path}/packages/aria2c/usr/lib")
                put("SSL_CERT_FILE", "${home.path}/etc/tls/cert.pem"); put("REQUESTS_CA_BUNDLE", "${home.path}/etc/tls/cert.pem")
                put("TMPDIR", context.cacheDir.path); put("PYTHONIOENCODING", "utf-8")
            }
            val process = builder.start()
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return JSONObject().put("errorType", "ProbeTimeout")
                return runCatching { JSONObject(process.inputStream.bufferedReader().readText()) }.getOrElse { JSONObject().put("errorType", "NoProbeResult") }
            } finally { process.destroy() }
        } finally { file.delete(); errors.delete(); scope.cleanup() }
    }
}
