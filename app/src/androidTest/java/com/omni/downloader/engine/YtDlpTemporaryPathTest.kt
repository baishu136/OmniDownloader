package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.DownloadType
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class YtDlpTemporaryPathTest {
    @Test fun nativeFormatChecksUseThePrivateTaskDirectory(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val bytes = instrument.context.assets.open("quality-1080.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));val running = AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) try { server.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader();val line = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!line.startsWith("HEAD")) write(bytes);flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true;start() }
        val directory = File(context.cacheDir, "format-probe-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val url = "http://127.0.0.1:${server.localPort}/source.mp4"
            val info = JSONObject().put("id", "temp-probe").put("title", "temporary path fixture")
                .put("extractor", "generic").put("extractor_key", "Generic").put("webpage_url", url)
                .put("formats", JSONArray().put(JSONObject().put("format_id", "http-1080").put("url", url)
                    .put("ext", "mp4").put("protocol", "http").put("vcodec", "h264").put("acodec", "none")
                    .put("width", 1920).put("height", 1080)))
            val input = File(directory, "info.json").apply { writeText(info.toString()) }
            val request = YtDlpOptionsFactory.downloadRequest(url, LinkNormalizer.PLATFORM_GENERIC, "",
                YtDlpOptionsFactory.DownloadFormatPlan(DownloadType.VIDEO_ONLY, maxHeight = 1080),
                File(directory, "%(id)s.%(ext)s").path, infoFile = input)
            request.addOption("--check-formats")
            YoutubeDL.getInstance().execute(request)
            val result = File(directory, "temp-probe.mp4")
            assertTrue(result.isFile);assertEquals(bytes.size.toLong(), result.length())
            val probe = MediaVerifier.probe(result);assertNotNull(probe);assertEquals(1080, minOf(probe!!.width, probe.height));assertEquals(1920, maxOf(probe.width, probe.height))
            assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
        } finally {
            running.set(false);server.close();thread.join(1000)
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }
}
