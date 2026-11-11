package com.omni.downloader.engine

import android.content.Context
import android.net.Uri
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

object LocalGifConverter {
    suspend fun convert(context: Context, task: DownloadTask,
        progress: (Float, String, String, TaskStatus) -> Unit): Result<String> {
        val directory = File(context.filesDir, "media_tasks/${task.id}/output").apply { mkdirs() }
        val input = File(directory, "local-source.video")
        val output = File(directory, "local-${task.id}.gif")
        return try {
            val job = currentCoroutineContext()
            progress(1f, "", "正在读取本地视频", TaskStatus.PROCESSING)
            context.contentResolver.openInputStream(Uri.parse(task.url)).use { source ->
                requireNotNull(source) { "无法读取所选视频，请重新选择文件" }
                input.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
            job.ensureActive()
            progress(25f, "", "正在转换 GIF", TaskStatus.PROCESSING)
            FFmpegExecutor.convertToGif(context, input, output).getOrThrow()
            job.ensureActive()
            Result.success(output.absolutePath)
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (error: Exception) {
            output.delete()
            Result.failure(error)
        } finally { input.delete() }
    }
}
