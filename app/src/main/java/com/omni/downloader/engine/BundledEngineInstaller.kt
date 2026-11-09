package com.omni.downloader.engine

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Verified, offline bootstrap; never replaces a newer user-updated engine. */
object BundledEngineInstaller {
    const val VERSION = "2026.10.26"
    data class ArchiveInfo(val version: String?, val ejsPresent: Boolean)
    fun archiveInfo(context: Context): ArchiveInfo = runCatching {
        target(context).inputStream().buffered().use { input ->
            // Android's ZipFile rejects the zipapp shebang; Python accepts it.
            // Inspect through a stream after the first line, keeping the original signed payload intact.
            input.mark(1024)
            if (input.read() == '#'.code && input.read() == '!'.code) {
                var count = 2
                while (true) {
                    val byte = input.read()
                    require(byte >= 0 && count++ < 1024) { "Invalid zipapp header" }
                    if (byte == '\n'.code) break
                }
            } else input.reset()
            ZipInputStream(input).use { zip ->
                var version: String? = null
                var core = false
                var library = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "yt_dlp/version.py") {
                        version = Regex("__version__\\s*=\\s*['\"]([^'\"]+)").find(zip.bufferedReader().readText())?.groupValues?.get(1)
                    }
                    if (entry.name == "yt_dlp_ejs/yt/solver/core.min.js") core = true
                    if (entry.name == "yt_dlp_ejs/yt/solver/lib.min.js") library = true
                    if (version != null && core && library) break
                }
                ArchiveInfo(version, core && library)
            }
        }
    }.getOrDefault(ArchiveInfo(null, false))

    fun installedVersion(context: Context): String? = archiveInfo(context).version

    private fun target(context: Context) = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    fun install(context: Context) {
        val installed = installedVersion(context)
        if (installed != null && installed >= VERSION) return
        val expected = context.assets.open("engine/yt-dlp.sha256").bufferedReader().use { it.readText().trim() }
        val output = target(context)
        output.parentFile!!.mkdirs()
        val temporary = File(output.parentFile, "yt-dlp.bootstrap.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("engine/yt-dlp").use { input ->
                temporary.outputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count); stream.write(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "[ENGINE_UNAVAILABLE] 内置引擎完整性校验失败" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
}
