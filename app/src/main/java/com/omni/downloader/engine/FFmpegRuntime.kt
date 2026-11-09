package com.omni.downloader.engine

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Repair incomplete extractions even when the dependency's version marker is current. */
internal object FFmpegRuntime {
    @Synchronized fun ensure(context: Context) {
        val archive = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
        check(archive.isFile) { "[ENGINE_UNAVAILABLE] 缺少 FFmpeg 运行库资源" }
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg").canonicalFile
        root.mkdirs()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("usr/lib/") || !entry.name.substringAfterLast('/').contains(".so")) continue
                val path = root.toPath().resolve(entry.name).normalize()
                require(path.startsWith(root.toPath()))
                val file = path.toFile()
                file.parentFile!!.mkdirs()
                require(file.parentFile!!.canonicalPath.startsWith(root.path + File.separator))
                val prefix = zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(20)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    buffer.copyOf(count)
                }
                if (prefix.size >= 20 && prefix.take(4).map { it.toInt() and 255 } == listOf(127, 69, 76, 70)) {
                    val current = runCatching { file.inputStream().use { input -> ByteArray(20).also { input.read(it) } } }.getOrNull()
                    if (file.length() == entry.size && current?.contentEquals(prefix) == true) continue
                    val temp = File.createTempFile(".runtime-", ".tmp", file.parentFile)
                    try {
                        val crc = CRC32()
                        zip.getInputStream(entry).use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                crc.update(buffer, 0, count); output.write(buffer, 0, count)
                            }
                        } }
                        check(temp.length() == entry.size && crc.value == entry.crc) { "[ENGINE_UNAVAILABLE] FFmpeg 运行库完整性校验失败" }
                        Files.move(temp.toPath(), path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally { temp.delete() }
                } else {
                    // The packaged ZIP stores ELF SONAME aliases as relative
                    // symlink entries. Never follow an existing link to write.
                    require(entry.size in 1..512)
                    val target = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    require(target.matches(Regex("[A-Za-z0-9_+./-]+")) && !File(target).isAbsolute)
                    require(path.parent.resolve(target).normalize().startsWith(root.toPath()))
                    if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == target) continue
                    val temp = File.createTempFile(".runtime-link-", ".tmp", file.parentFile).toPath()
                    try {
                        Files.delete(temp)
                        Files.createSymbolicLink(temp, java.nio.file.Paths.get(target))
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally { Files.deleteIfExists(temp) }
                }
            }
        }
    }
}
