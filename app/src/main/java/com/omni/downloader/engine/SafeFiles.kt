package com.omni.downloader.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 统一的文件系统与安全路径辅助工具，避免因缓存目录被清理、父目录未创建、
 * 同路径覆盖自删或文件名超长/非法字符导致 open failed: ENOENT。
 */
internal object SafeFiles {

    private const val DEFAULT_MAX_NAME_BYTES = 180

    /** 确保目标目录存在；若为 null 或无法创建则返回原对象。 */
    fun ensureDir(dir: File?): File? {
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 确保应用 cacheDir 存在，防止系统或用户清理缓存后 File.createTempFile 直接 ENOENT。 */
    fun ensureCacheDir(context: Context): File {
        val dir = context.cacheDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 清空目录内子项而保留目录节点本身，避免破坏后续依赖该根目录的调用方。 */
    fun clearDirectoryContents(dir: File?) {
        if (dir == null) return
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
        }
    }

    /** 在创建临时文件前确保父目录存在。 */
    fun createTempFile(directory: File?, prefix: String, suffix: String): File {
        ensureDir(directory)
        return File.createTempFile(prefix, suffix, directory)
    }

    /** 在打开输出流前确保父目录已创建。 */
    fun openOutputStream(file: File, append: Boolean = false): FileOutputStream {
        ensureDir(file.parentFile)
        return FileOutputStream(file, append)
    }

    /** 安全拷贝文件：确保目标父目录存在，并跳过同路径自覆盖导致源文件被提前删除。 */
    fun safeCopy(source: File, destination: File, overwrite: Boolean = true): File {
        ensureDir(destination.parentFile)
        if (source.canonicalFile == destination.canonicalFile) {
            return destination
        }
        source.copyTo(destination, overwrite = overwrite)
        return destination
    }

    /**
     * 规范化输出文件名（不含扩展名）：
     * - 剔除路径分隔符、控制字符、NUL 及危险占位名（"."、".."）；
     * - 按 UTF-8 字节上限安全截断（不劈裂 Emoji 代理对），预留后缀与去重编号空间。
     */
    fun sanitizeFileName(
        name: String,
        fallback: String = "OmniMedia",
        maxUtf8Bytes: Int = DEFAULT_MAX_NAME_BYTES
    ): String {
        val normalized = name
            .replace(Regex("""[\\/:*?"<>|\u0000\r\n\t]"""), "_")
            .filter { ch -> Character.isDefined(ch) && !Character.isISOControl(ch) }
            .trim()
            .trim('.')
            .trim()

        if (normalized.isEmpty() || normalized == "." || normalized == "..") {
            return sanitizeFallback(fallback, maxUtf8Bytes)
        }

        val builder = StringBuilder()
        var usedBytes = 0
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val charCount = Character.charCount(cp)
            val piece = normalized.substring(i, i + charCount)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (usedBytes + pieceBytes > maxUtf8Bytes) {
                break
            }
            builder.append(piece)
            usedBytes += pieceBytes
            i += charCount
        }

        val truncated = builder.toString().trim().trim('.').trim()
        if (truncated.isEmpty() || truncated == "." || truncated == "..") {
            return sanitizeFallback(fallback, maxUtf8Bytes)
        }
        return truncated
    }

    private fun sanitizeFallback(fallback: String, maxUtf8Bytes: Int): String {
        val clean = fallback
            .replace(Regex("""[\\/:*?"<>|\u0000\r\n\t]"""), "_")
            .filter { ch -> Character.isDefined(ch) && !Character.isISOControl(ch) }
            .trim()
            .trim('.')
            .trim()
        if (clean.isEmpty() || clean == "." || clean == "..") {
            return "OmniMedia"
        }
        return if (clean.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) clean else "OmniMedia"
    }
}
