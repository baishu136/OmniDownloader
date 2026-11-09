package com.omni.downloader.engine

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.data.repository.SettingsRepository
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.net.URI

/** Live fixtures are supplied by adb arguments and never embedded in a production APK. */
class LivePlatformRegressionTest {
    @Test fun inspectOneSample() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: error("url argument is required")
        val caseId = args.getString("caseId") ?: "unknown"
        val route = args.getString("route") ?: "app"
        val settings = SettingsRepository.getInstance(context)
        val sessions = SessionRepository.getInstance(context)
        val site = LinkNormalizer.platformOf(url)
        val proxy = if (route.endsWith("_direct")) "" else settings.proxyUrl.value
        val previousNativeProxy = NetworkProxy.configuredUrl
        NetworkProxy.configuredUrl = proxy
        val network = (context.getSystemService(ConnectivityManager::class.java)).let { it.getNetworkCapabilities(it.activeNetwork) }
        val result = JSONObject().put("caseId", caseId).put("route", route).put("platform", site)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("proxyScheme", runCatching { URI(proxy).scheme }.getOrNull().orEmpty())
            .put("proxyLoopback", runCatching { URI(proxy).host in setOf("127.0.0.1", "localhost", "::1") }.getOrDefault(false))
            .put("hasSession", sessions.info(site)?.cookieCount?.let { it > 0 } ?: false)
            .put("vpn", network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            result.put("engineVersion", BundledEngineInstaller.installedVersion(context))
            if (route.startsWith("images")) {
                val post = SharedPython.run(context, "images", url, site, proxy)
                result.put("success", true).put("mediaId", post.optString("contentId"))
                    .put("imageCount", post.getJSONArray("resources").length()).put("extractRoute", post.optString("route"))
            } else if (route == "public_direct") {
                val data = SharedPython.run(context, "x-video", url, site, proxy)
                result.put("success", true).put("mediaId", data.optString("id"))
                    .put("videoFormats", data.optJSONArray("formats")?.length() ?: 0)
                    .put("children", data.optJSONArray("entries")?.length() ?: 0)
            } else if (route.startsWith("app")) {
                val meta = DownloadEngine.fetchVideoMetadata(context, url, proxy).getOrThrow()
                result.put("success", true).put("contentKind", meta.contentKind).put("mediaId", meta.mediaId)
                    .put("videoFormats", meta.availableVideoFormats.size).put("audioFormats", meta.availableAudioFormats.size)
                    .put("children", meta.multiMediaList.size).put("isGif", meta.isGif)
                    .put("maxPixels", meta.availableVideoFormats.maxOfOrNull { it.width.toLong() * it.height } ?: 0)
            } else {
                val requestContext = RequestContextFactory.create(context, site)
                try {
                    val request = YtDlpOptionsFactory.metadataRequest(url, site, proxy, false,
                        if (route == "syndication_public") null else requestContext.cookieFile)
                    if (route.startsWith("syndication")) request.addOption("--extractor-args", "twitter:api=syndication")
                    request.addOption("--extractor-retries", "1")
                    val response = YoutubeDL.getInstance().execute(request)
                    val data = JSONObject(response.out.trim())
                    result.put("success", true).put("mediaId", data.optString("id"))
                        .put("videoFormats", data.optJSONArray("formats")?.length() ?: 0)
                        .put("children", data.optJSONArray("entries")?.length() ?: 0)
                        .put("warnings", LogSanitizer.sanitize(response.err.orEmpty()).takeLast(1800))
                } finally { requestContext.cleanup() }
            }
        } catch (error: Exception) {
            val detail = LogSanitizer.sanitize(error.message.orEmpty())
            result.put("success", false).put("errorCode", MediaErrors.classify(detail).code).put("error", detail.takeLast(2400))
        }
        result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
        NetworkProxy.configuredUrl = previousNativeProxy
        java.io.File(context.cacheDir, "live-regression-result.json").writeText(result.toString())
        instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "OMNI_LIVE_RESULT=" + result.toString() + "\n") })
        println("OMNI_LIVE_RESULT=" + result.toString())
    }
}
