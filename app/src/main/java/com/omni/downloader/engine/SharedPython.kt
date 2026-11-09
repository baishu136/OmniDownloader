package com.omni.downloader.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Uses the app's bundled runtime, scoped sessions and verified offline dependencies. */
object SharedPython {
    private val processes = ConcurrentHashMap<String, Process>()
    @Synchronized fun install(context: Context) {
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val expected = context.assets.open("engine/python-extras.sha256").bufferedReader().use { it.readText().trim() }
        val home = File(base, "packages/python/usr/lib/python3.12/site-packages")
        val marker = File(home, ".omni-extras-sha256")
        if (marker.isFile && marker.readText() == expected && File(home, "omni_media.py").isFile) return
        val bytes = context.assets.open("engine/python-extras.zip").use { it.readBytes() }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expected) { "[ENGINE_UNAVAILABLE] 网络依赖完整性校验失败" }
        home.mkdirs()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(home, entry.name).canonicalFile
                require(target.path.startsWith(home.canonicalPath + File.separator))
                if (!entry.isDirectory) { target.parentFile!!.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
            }
        }
        marker.writeText(expected)
    }

    suspend fun run(context: Context, action: String, url: String, platform: String, proxy: String,
        taskId: String = java.util.UUID.randomUUID().toString(), extra: List<String> = emptyList()): JSONObject = withContext(Dispatchers.IO) {
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val scope = RequestContextFactory.create(context, platform)
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val home = File(base, "packages/python/usr")
        val native = context.applicationInfo.nativeLibraryDir
        val script = File(home, "lib/python3.12/site-packages/omni_media.py")
        val args = mutableListOf("$native/libpython.so", script.path, "--action", action, "--url", url, "--platform", platform, "--proxy", proxy)
        scope.cookieFile?.let { args += listOf("--cookies", it.path) }
        args += extra
        val builder = ProcessBuilder(args)
        builder.environment().apply {
            put("PYTHONHOME", home.path)
            put("PYTHONPATH", File(base, "yt-dlp/yt-dlp").path)
            put("LD_LIBRARY_PATH", "${home.path}/lib:${base.path}/packages/ffmpeg/usr/lib:${base.path}/packages/aria2c/usr/lib")
            put("SSL_CERT_FILE", "${home.path}/etc/tls/cert.pem")
            put("REQUESTS_CA_BUNDLE", "${home.path}/etc/tls/cert.pem")
            put("TMPDIR", context.cacheDir.path)
            put("PYTHONIOENCODING", "utf-8")
        }
        val stderr = File.createTempFile("resolver-", ".err", context.cacheDir)
        try {
            val process = builder.redirectError(stderr).start()
            processes[taskId] = process
            currentCoroutineContext().ensureActive()
            val output = StringBuilder()
            val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 16_000_000) output.append(it).append('\n') } } }.apply { start() }
            if (!process.waitFor(if (action == "download-images") 600 else if (action == "x-video") 75 else 120, TimeUnit.SECONDS)) {
                process.destroyForcibly(); error("[NETWORK_ERROR] 解析或传输超时")
            }
            reader.join(5000)
            val json = runCatching { JSONObject(output.toString()) }.getOrElse { error("[ENGINE_UNAVAILABLE] ${LogSanitizer.sanitize(stderr.readText().takeLast(600))}") }
            check(json.optBoolean("ok")) { LogSanitizer.sanitize(json.optString("error", "解析失败")) }
            json.getJSONObject("result")
        } finally { processes.remove(taskId)?.destroy(); stderr.delete(); scope.cleanup() }
    }
    fun cancel(taskId: String) { processes.remove(taskId)?.destroyForcibly() }
}
