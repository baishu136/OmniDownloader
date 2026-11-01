package com.omni.downloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omni.downloader.R
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private lateinit var repository: TaskRepository

    private val taskQueue = ConcurrentLinkedQueue<DownloadTask>()
    @Volatile
    private var isProcessing = false
    @Volatile
    private var currentRunningTaskId: String? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OmniDownloader:DownloadWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld == false) {
                // 最多持有 60 分钟防止异常泄漏，保障息屏不断流
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        repository = TaskRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                val task = repository.getTask(taskId)
                if (task != null && !taskQueue.contains(task)) {
                    taskQueue.offer(task)
                    processNextTask()
                }
            }
            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                taskQueue.removeIf { it.id == taskId }
                if (currentRunningTaskId == taskId) {
                    DownloadEngine.cancelTask(taskId)
                    currentRunningTaskId = null
                }
                repository.updateTaskProgress(taskId, 0f, "", "", TaskStatus.CANCELLED)
                if (taskQueue.isEmpty() && currentRunningTaskId == null) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            ACTION_CANCEL_ALL -> {
                currentRunningTaskId?.let { runningId ->
                    DownloadEngine.cancelTask(runningId)
                    currentRunningTaskId = null
                }
                taskQueue.clear()
                // 保留工作协程的占用状态，等待取消退出后再启动新任务。
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun processNextTask() {
        if (isProcessing) return
        val next = taskQueue.poll() ?: run {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        acquireWakeLock()
        isProcessing = true
        currentRunningTaskId = next.id
        startForeground(NOTIFICATION_ID, buildProgressNotification(next, 0f, "准备下载..."))

        serviceScope.launch {
            val proxy = com.omni.downloader.data.repository.SettingsRepository.getInstance(applicationContext).proxyUrl.value
            var lastNotificationTime = 0L
            var lastProgressUpdateTime = 0L
            var lastStatus: TaskStatus? = null
            var lastReportedProgress = -1f
            var lastReportedSpeed = ""

            val result = DownloadEngine.executeDownload(applicationContext, next, proxy) { progress, speed, eta, status ->
                synchronized(this@DownloadService) {
                    if (currentRunningTaskId != next.id) return@executeDownload
                    val now = android.os.SystemClock.elapsedRealtime()
                    val isStatusChanged = status != lastStatus

                    // 1. UI 状态节流：状态发生转换时立即可靠送达；同状态内至少间隔 300ms 且有有效进度/速度变动才更新
                    val isProgressEffective = kotlin.math.abs(progress - lastReportedProgress) >= 0.5f ||
                            speed != lastReportedSpeed ||
                            progress >= 100f
                    if (isStatusChanged || (now - lastProgressUpdateTime >= 300 && isProgressEffective)) {
                        lastProgressUpdateTime = now
                        lastStatus = status
                        lastReportedProgress = progress
                        lastReportedSpeed = speed
                        repository.updateTaskProgress(next.id, progress, speed, eta, status)
                    }

                    // 2. 系统通知 Binder 节流：状态转换立即更新；同状态内至少间隔 800ms，彻底消除 IPC 造成的全局掉帧
                    if ((status == TaskStatus.DOWNLOADING || status == TaskStatus.PROCESSING) &&
                        (isStatusChanged || now - lastNotificationTime >= 800)) {
                        lastNotificationTime = now
                        val statusText = if (status == TaskStatus.PROCESSING) {
                            eta.ifBlank { "正在合并音视频..." }
                        } else {
                            "$speed ${if (eta.isNotEmpty()) "· 剩余 $eta" else ""}"
                        }
                        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(next, progress, statusText, status))
                    }
                }
            }

            synchronized(this@DownloadService) {
                // 已取消的任务不再发布完成通知，也不覆盖 CANCELLED 状态。
                if (currentRunningTaskId == next.id) {
                    result.onSuccess { finalPath ->
                        repository.markCompleted(next.id, finalPath)
                        MediaScannerConnection.scanFile(
                            applicationContext,
                            arrayOf(finalPath),
                            null
                        ) { _, _ -> }
                        showCompletedNotification(next)
                    }.onFailure { err ->
                        repository.markFailed(next.id, err.message ?: "下载过程中遇到错误")
                    }
                }

                isProcessing = false
                currentRunningTaskId = null
                processNextTask()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示当前视频/音频的实时下载与合并进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        task: DownloadTask,
        progress: Float,
        subText: String,
        status: TaskStatus = TaskStatus.DOWNLOADING
    ) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${if (status == TaskStatus.PROCESSING) "正在处理" else "正在下载"}: ${task.title}")
            .setContentText(subText)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setContentIntent(getLaunchPendingIntent())
            .build()

    private fun showCompletedNotification(task: DownloadTask) {
        val completedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("下载完成")
            .setContentText(task.title)
            .setAutoCancel(true)
            .setContentIntent(getLaunchPendingIntent())
            .build()

        notificationManager.notify(task.id.hashCode(), completedNotification)
    }

    private fun getLaunchPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "omni_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TASK = "com.omni.downloader.START_TASK"
        const val ACTION_CANCEL_TASK = "com.omni.downloader.CANCEL_TASK"
        const val ACTION_CANCEL_ALL = "com.omni.downloader.CANCEL_ALL"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun startDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        fun cancelAllDownloads(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            context.startService(intent)
        }
    }
}
