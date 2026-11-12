package com.omni.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.omni.downloader.data.repository.SettingsRepository
import java.io.File
import java.io.IOException

/** Source stays in private storage until the final destination has been committed. */
object StorageExporter {
    fun export(context: Context, source: File, targetOverride: String? = null): String {
        require(source.isFile && source.length() > 0) { "[EXPORT_FAILED] 暂存成品不存在" }
        val target = targetOverride ?: SettingsRepository.getInstance(context).downloadPath.value
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
        var created: Uri? = null
        try {
            if (target.startsWith("content://")) {
                val tree = Uri.parse(target)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                created = DocumentsContract.createDocument(context.contentResolver, parent, mime, source.name)
                    ?: throw IOException("目标目录无法创建文件，请重新授权")
            } else if (Build.VERSION.SDK_INT >= 29 && target.isBlank()) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OmniDownloader")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                created = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("系统下载目录无法创建文件")
            } else {
                val dir = if (target.isNotBlank()) File(target) else DownloadEngine.getDownloadDir(context)
                if ((!dir.isDirectory && !dir.mkdirs()) || !dir.isDirectory) throw IOException("目标目录不可用，请重新选择目录")
                var dest = File(dir, source.name)
                var index = 1
                while (dest.exists()) dest = File(dir, "${source.nameWithoutExtension}_${index++}.${source.extension}")
                val partial = File(dir, ".${dest.name}.${System.nanoTime()}.part")
                try {
                    source.inputStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
                    check(partial.length() == source.length()) { "导出大小不一致" }
                    check(partial.renameTo(dest)) { "无法发布目标文件" }
                } finally { partial.delete() }
                return dest.absolutePath
            }
            val uri = created!!
            source.inputStream().use { input ->
                (context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("无法写入目标文件")).use { output -> input.copyTo(output) }
            }
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
                total
            }
            check(copied == source.length()) { "导出大小校验失败" }
            if (Build.VERSION.SDK_INT >= 29 && target.isBlank()) context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri.toString()
        } catch (e: Exception) {
            created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw IOException("[EXPORT_FAILED] ${LogSanitizer.sanitize(e.message.orEmpty())}；成品已保留，可更换目录后重试导出", e)
        }
    }
}
