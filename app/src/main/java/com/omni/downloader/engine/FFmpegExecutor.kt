package com.omni.downloader.engine

import android.content.Context
import android.util.Log
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
        format: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))

        val commands = when (format.lowercase()) {
            "m4a", "aac" -> listOf(
                ffmpeg.absolutePath,
                "-y",
                "-i", rawAudioFile.absolutePath,
                "-vn",
                "-c:a", "copy",
                outputFile.absolutePath
            )
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
        val ffmpeg = getFFmpegBinary(context)
            ?: return@withContext Result.failure(Exception("未能找到 FFmpeg 核心执行组件"))

        val filterGraph = "fps=15,scale='min(480,trunc(iw/2)*2)':-2:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer"
        val commands = listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i", inputFile.absolutePath,
            "-vf", filterGraph,
            "-loop", "0",
            outputFile.absolutePath
        )

        val res = executeCommand(context, commands)
        if (res.isSuccess && outputFile.exists() && outputFile.length() > 0) {
            return@withContext Result.success(Unit)
        }

        // 简易备选指令（若复杂滤镜图遇到非标编码，平滑降级并保证 -loop 0 无限循环与偶数分辨率）
        Log.w(TAG, "两阶段调色板 GIF 转换未通过，切换通用降级转换: ${res.exceptionOrNull()?.message}")
        val fallbackCommands = listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i", inputFile.absolutePath,
            "-vf", "fps=15,scale='min(480,trunc(iw/2)*2)':-2:flags=lanczos",
            "-loop", "0",
            outputFile.absolutePath
        )
        executeCommand(context, fallbackCommands)
    }

    private fun executeCommand(context: Context, commands: List<String>): Result<Unit> {
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
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.d(TAG, "FFmpeg 执行成功完成")
                return Result.success(Unit)
            } else {
                Log.e(TAG, "FFmpeg 报错退出 (code $exitCode): $output")
                return Result.failure(Exception("FFmpeg 处理失败 (code $exitCode): ${output.takeLast(400)}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg 进程启动异常", e)
            return Result.failure(e)
        }
    }
}
