package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.engine.SafeLog as Log
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FFmpegExecutor {

    private const val TAG = "FFmpegExecutor"

    /**
     * 获取系统 libffmpeg.so 可执行文件路径并确保具备执行权限
     */
    fun getFFmpegBinary(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpegFile = File(nativeDir, "libffmpeg.so")
        if (ffmpegFile.exists()) {
            if (!ffmpegFile.canExecute()) {
                ffmpegFile.setExecutable(true, false)
            }
            return ffmpegFile
        }

        // 备选路径：youtubedl-android 的 binDir
        val noBackupBin = File(context.noBackupFilesDir, "youtubedl-android/usr/bin/ffmpeg")
        if (noBackupBin.exists()) {
            if (!noBackupBin.canExecute()) {
                noBackupBin.setExecutable(true, false)
            }
            return noBackupBin
        }
        return null
    }

    /**
     * 音视频轨混流
     */
    suspend fun mergeVideoAndAudio(
        context: Context,
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))

        val commands = listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )

        executeCommand(context, commands)
    }

    /**
     * 仅去除音频保留画面
     */
    suspend fun stripAudio(
        context: Context,
        videoFile: File,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))

        val commands = listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i", videoFile.absolutePath,
            "-an",
            "-c:v", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )

        executeCommand(context, commands)
    }

    /**
     * 抽取音频并导出
     */
    suspend fun extractAudio(
        context: Context,
        rawAudioFile: File,
        outputFile: File,
        format: String,
        forceEncode: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))

        val commands = when (format.lowercase()) {
            "m4a", "aac" -> listOf(
                ffmpeg.absolutePath,
                "-y",
                "-i", rawAudioFile.absolutePath,
                "-vn",
                "-c:a", if (forceEncode) "aac" else "copy"
            ) + (if (forceEncode) listOf("-b:a", "192k") else emptyList()) + outputFile.absolutePath
            else -> listOf(
                ffmpeg.absolutePath,
                "-y",
                "-i", rawAudioFile.absolutePath,
                "-vn",
                "-q:a", "0",
                outputFile.absolutePath
            )
        }

        executeCommand(context, commands)
    }

    /**
     * 视频转换为 GIF 动图
     */
    suspend fun convertToGif(
        context: Context,
        inputFile: File,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (GifInspector.inspect(inputFile) != null) {
            MediaVerifier.verifyGif(inputFile)?.let { return@withContext Result.failure(Exception(it)) }
            if (inputFile.canonicalFile != outputFile.canonicalFile) inputFile.copyTo(outputFile, overwrite = true)
            return@withContext Result.success(Unit)
        }
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))
        outputFile.delete()
        val palette = File.createTempFile("omni-gif-palette-", ".png", context.cacheDir)
        val scale = "fps=15,scale='min(480,iw)':-1:flags=lanczos"
        try {
            // A single split graph buffers the whole video until palettegen
            // finishes. Separate passes keep conversion memory bounded.
            val paletteResult = executeCommand(context, listOf(ffmpeg.path, "-y", "-i", inputFile.path,
                "-vf", "$scale,palettegen=max_colors=256:stats_mode=diff", "-frames:v", "1", "-update", "1", palette.path))
            val result = if (paletteResult.isSuccess && palette.length() > 0) {
                executeCommand(context, listOf(ffmpeg.path, "-y", "-i", inputFile.path, "-i", palette.path,
                    "-filter_complex", "[0:v]$scale[v];[v][1:v]paletteuse=dither=bayer[gif]",
                    "-map", "[gif]", "-an", "-c:v", "gif", "-loop", "0", "-f", "gif", outputFile.path))
            } else paletteResult
            if (result.isSuccess && MediaVerifier.verifyGif(outputFile) == null) return@withContext Result.success(Unit)
            outputFile.delete()
            Log.w(TAG, "调色板 GIF 转换未通过，尝试标准 GIF 编码: ${result.exceptionOrNull()?.message}")
            val fallback = executeCommand(context, listOf(ffmpeg.path, "-y", "-i", inputFile.path,
                "-vf", scale, "-an", "-pix_fmt", "rgb8", "-c:v", "gif", "-loop", "0", "-f", "gif", outputFile.path))
            val error = MediaVerifier.verifyGif(outputFile)
            if (fallback.isSuccess && error == null) Result.success(Unit)
            else {
                outputFile.delete()
                Result.failure(Exception(error ?: "[POSTPROCESS_FAILED] GIF 转换失败", fallback.exceptionOrNull()))
            }
        } finally { palette.delete() }
    }

    internal suspend fun executeCommand(context: Context, commands: List<String>): Result<Unit> {
        try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val packagesDir = File(context.noBackupFilesDir, "youtubedl-android/packages")
            val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
            val pythonLibDir = File(packagesDir, "python/usr/lib")
            val aria2cLibDir = File(packagesDir, "aria2c/usr/lib")

            // 若依赖动态库目录尚未准备就绪，主动触发初始化
            if (!ffmpegLibDir.exists() || ffmpegLibDir.listFiles().isNullOrEmpty()) {
                try {
                    FFmpeg.getInstance().init(context.applicationContext)
                } catch (fe: Exception) {
                    Log.w(TAG, "初始化 FFmpeg 运行时库微异常: ${fe.message}")
                }
            }
            FFmpegRuntime.ensure(context)

            val pb = ProcessBuilder(commands)
            pb.directory(context.cacheDir)
            pb.redirectErrorStream(true)

            // 关键：注入动态库检索环境变量，防止 linker64 找不到 libav*.so 导致崩溃
            val ldDirs = listOf(ffmpegLibDir, pythonLibDir, aria2cLibDir, nativeDir)
                .filter { it.exists() }
                .joinToString(":") { it.absolutePath }

            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = ldDirs
            env["PATH"] = "${System.getenv("PATH") ?: ""}:${nativeDir.absolutePath}"
            env["TMPDIR"] = context.cacheDir.absolutePath

            Log.d(TAG, "执行 FFmpeg 调度: ${commands.joinToString(" ")}")

            val process = pb.start()
            return kotlinx.coroutines.withTimeout(30L * 60 * 1000) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { process.destroyForcibly() }
                    Thread({
                        val result = runCatching {
                            val tail = StringBuilder()
                            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                                tail.append(line).append('\n')
                                if (tail.length > 16000) tail.delete(0, tail.length - 12000)
                            } }
                            val code = process.waitFor()
                            if (code != 0) error("FFmpeg处理失败 (code $code): ${LogSanitizer.sanitize(tail.toString()).takeLast(1500)}")
                            Unit
                        }
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    }, "omni-ffmpeg-output").apply { isDaemon = true; start() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg 进程启动异常", e)
            return Result.failure(e)
        }
    }
}
