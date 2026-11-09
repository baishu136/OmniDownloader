package com.omni.downloader.engine

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Opt-in diagnostics for differences observed during platform acceptance. No account data. */
class PlatformRequestProbeTest {
    private fun publish(value: JSONObject) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "OMNI_REQUEST_PROBE=$value\n") })

    @Test fun instagramHeaderComparison() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        DownloadEngine.ensureInitialized(instrument.targetContext).getOrThrow()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: error("url is required")
        val proxy = args.getString("proxy").orEmpty()
        val rows = JSONArray()
        listOf("app_headers", "upstream_headers").forEach { variant ->
            val request = if (variant == "app_headers") YtDlpOptionsFactory.metadataRequest(url, LinkNormalizer.PLATFORM_INSTAGRAM, proxy, false)
                else YoutubeDLRequest(url).apply {
                    addOption("-J"); addOption("--no-playlist"); addOption("--ignore-config"); addOption("--no-cache-dir")
                    addOption("--geo-bypass"); addOption("--socket-timeout", "30")
                    if (proxy.isNotBlank()) addOption("--proxy", proxy)
                }
            val result = JSONObject().put("variant", variant)
            try {
                val json = JSONObject(YoutubeDL.getInstance().execute(request).out.trim())
                result.put("success", true).put("id", json.optString("id")).put("formats", json.optJSONArray("formats")?.length() ?: 0)
            } catch (error: Exception) { result.put("success", false).put("errorCode", MediaErrors.codeOf(error).code) }
            rows.put(result)
        }
        publish(JSONObject().put("probe", "instagram_headers").put("results", rows))
    }

    @Test fun facebookFormatContinuity() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        DownloadEngine.ensureInitialized(instrument.targetContext).getOrThrow()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: error("url is required")
        val proxy = args.getString("proxy").orEmpty()
        val rows = JSONArray()
        repeat(3) {
            val result = JSONObject().put("request", it + 1)
            try {
                val json = JSONObject(YoutubeDL.getInstance().execute(YtDlpOptionsFactory.metadataRequest(url, LinkNormalizer.PLATFORM_FACEBOOK, proxy, false)).out.trim())
                val formats = json.optJSONArray("formats") ?: JSONArray()
                val summary = JSONArray()
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val id = f.optString("format_id")
                    summary.put(JSONObject().put("id", if (id.contains("://")) "<url>" else id)
                        .put("width", f.optInt("width")).put("height", f.optInt("height"))
                        .put("vcodec", f.optString("vcodec")).put("acodec", f.optString("acodec")))
                }
                result.put("success", true).put("mediaId", json.optString("id")).put("formats", summary)
            } catch (error: Exception) { result.put("success", false).put("errorCode", MediaErrors.codeOf(error).code) }
            rows.put(result)
        }
        publish(JSONObject().put("probe", "facebook_formats").put("results", rows))
    }
}
