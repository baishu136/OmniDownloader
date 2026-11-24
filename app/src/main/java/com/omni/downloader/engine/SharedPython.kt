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
    private class Running(val process:Process) {
        val cancelled=java.util.concurrent.atomic.AtomicBoolean()
        fun cancel(){cancelled.set(true);runCatching{process.destroyForcibly()}}
    }
    private val processes = ConcurrentHashMap<String, Running>()
    @Synchronized fun install(context: Context) {
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val expected = context.assets.open("engine/python-extras.sha256").bufferedReader().use { it.readText().trim() }
        val home = File(base, "packages/python/usr/lib/python3.12/site-packages")
        home.mkdirs()
        val guardConfig=File(home,".omni-x-guard.json")
        val guardValue=JSONObject().put("directory",File(context.filesDir,"network_guard").path).toString()
        if(!guardConfig.isFile || guardConfig.readText()!=guardValue) {
            val temporary=File(home,".omni-x-guard.json.tmp");temporary.writeText(guardValue)
            java.nio.file.Files.move(temporary.toPath(),guardConfig.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
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
        val cacheDir = SafeFiles.ensureCacheDir(context)
        val builder = ProcessBuilder(args)
        builder.environment().apply {
            put("PYTHONHOME", home.path)
            put("PYTHONPATH", File(base, "yt-dlp/yt-dlp").path)
            put("LD_LIBRARY_PATH", "${home.path}/lib:${base.path}/packages/ffmpeg/usr/lib:${base.path}/packages/aria2c/usr/lib")
            put("SSL_CERT_FILE", "${home.path}/etc/tls/cert.pem")
            put("REQUESTS_CA_BUNDLE", "${home.path}/etc/tls/cert.pem")
            put("TMPDIR", cacheDir.path)
            put("PYTHONIOENCODING", "utf-8")
            put("OMNI_PIXIV_GUARD_DIR",File(context.filesDir,"network_guard").path)
            put("OMNI_X_GUARD_DIR",File(context.filesDir,"network_guard").path)
        }
        val stderr = SafeFiles.createTempFile(cacheDir, "resolver-", ".err")
        var running:Running?=null
        var reader:Thread?=null
        suspend fun ensureRunning(){currentCoroutineContext().ensureActive();if(running?.cancelled?.get()==true)throw kotlinx.coroutines.CancellationException("解析已取消")}
        try {
            ensureRunning()
            val active=Running(builder.redirectError(stderr).start());running=active
            val process=active.process;processes[taskId]=active
            ensureRunning()
            val output = StringBuilder()
            val readerError=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            reader = Thread {
                try{process.inputStream.bufferedReader().useLines{lines->lines.forEach{if(output.length<16_000_000)output.append(it).append('\n')}}}
                catch(error:Exception){readerError.set(error)}
            }.apply{name="OmniResolverOutput-$taskId";isDaemon=true;start()}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(if(action=="download-images")600 else if(action=="x-video")75 else 120)
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                ensureRunning()
                if(System.nanoTime()>=deadline)error("[NETWORK_ERROR] 解析或传输超时")
            }
            ensureRunning()
            reader.join(5000)
            ensureRunning()
            check(!reader.isAlive){"[ENGINE_UNAVAILABLE] 解析输出未结束"}
            readerError.get()?.let{throw java.io.IOException("[ENGINE_UNAVAILABLE] 读取解析输出失败",it)}
            val json = runCatching { JSONObject(output.toString()) }.getOrElse { error("[ENGINE_UNAVAILABLE] ${LogSanitizer.sanitize(stderr.readText().takeLast(600))}") }
            check(json.optBoolean("ok")) { LogSanitizer.sanitize(json.optString("error", "解析失败")) }
            json.getJSONObject("result")
        } catch(error:Exception){ensureRunning();throw error}
        finally {
            running?.let{active->
                processes.remove(taskId,active);active.cancel()
                runCatching{active.process.inputStream.close()};runCatching{active.process.errorStream.close()};runCatching{active.process.outputStream.close()}
            }
            runCatching{reader?.join(1000)}
            stderr.delete();scope.cleanup()
        }
    }
    fun cancel(taskId: String) { processes[taskId]?.cancel() }
}
