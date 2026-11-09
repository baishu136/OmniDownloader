package com.omni.downloader.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

/**
 * 读取实际安装包内的运行时信息，避免用构建配置代替真实版本。
 * 读取失败不阻塞下载流程，只返回 Unavailable。
 */
data class EngineDiagnostics(
    val ytDlpVersion: String,
    val libraryVersion: String,
    val engineReady: Boolean,
    val error: String? = null,
    val pythonRuntime: String = "?",
    val jsRuntime: String = "?",
    val ejsPresent: Boolean = false
) {
    val summary: String
        get() = "yt-dlp $ytDlpVersion · Python $pythonRuntime · $jsRuntime · EJS ${if (ejsPresent) "已内置" else "缺失"}"
}

object EngineDiagnosticsProbe {

    fun probe(context: Context): EngineDiagnostics {
        val youtubeDl = YoutubeDL.getInstance()
        val ytDlpVersion = runCatching { BundledEngineInstaller.installedVersion(context) ?: youtubeDl.version(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "?"
        val libraryVersion = runCatching { youtubeDl.versionName(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "?"
        return EngineDiagnostics(
            ytDlpVersion = ytDlpVersion,
            libraryVersion = libraryVersion,
            engineReady = DownloadEngine.isReady(),
            pythonRuntime = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib").listFiles()
                ?.mapNotNull { Regex("libpython([0-9]+\\.[0-9]+)\\.so").matchEntire(it.name)?.groupValues?.get(1) }?.firstOrNull() ?: "?",
            jsRuntime = if (java.io.File(context.applicationInfo.nativeLibraryDir, "libqjs.so").isFile) "QuickJS" else "JS缺失",
            ejsPresent = BundledEngineInstaller.archiveInfo(context).ejsPresent
        )
    }
}
