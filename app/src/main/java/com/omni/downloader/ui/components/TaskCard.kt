package com.omni.downloader.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.repository.TaskProgress
import com.omni.downloader.ui.design.LocalTabActive
import com.omni.downloader.ui.design.OmniPillButton
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Static card content is separated from the lifecycle-aware progress leaf. */
@Composable
fun TaskCard(
    task: DownloadTask,
    onCancel: (taskId: String) -> Unit,
    onDelete: (taskId: String, deleteFile: Boolean) -> Unit,
    onRetry: (url: String) -> Unit,
    progressFlowProvider: ((String) -> StateFlow<TaskProgress>)? = null,
    progressFlow: StateFlow<TaskProgress>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val statusColors = LocalStatusColors.current
    val isGifTask = task.downloadType == DownloadType.GIF || task.localFilePath.endsWith(".gif", ignoreCase = true)
    var showGifPreviewDialog by remember { mutableStateOf(false) }

    val resolvedProgressFlow = remember(task.id, progressFlow, progressFlowProvider) {
        progressFlow ?: progressFlowProvider?.invoke(task.id)
    }

    val targetStatusColor = when (task.status) {
        TaskStatus.COMPLETED -> statusColors.success
        TaskStatus.DOWNLOADING, TaskStatus.PROCESSING -> MaterialTheme.colorScheme.primary
        TaskStatus.FAILED -> statusColors.error
        else -> statusColors.warning
    }
    val statusColor by animateColorAsState(
        targetValue = targetStatusColor,
        animationSpec = tween(220),
        label = "taskStatusColor"
    )

    val statusText = when (task.status) {
        TaskStatus.PENDING -> strings.statusPending
        TaskStatus.DOWNLOADING -> strings.statusDownloading
        TaskStatus.PROCESSING -> strings.statusProcessing
        TaskStatus.COMPLETED -> strings.statusCompleted
        TaskStatus.FAILED -> strings.statusFailed
        TaskStatus.CANCELLED -> strings.statusCancelled
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：封面、标题与状态（在下载进度变动时完全不参与重组）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                TaskCover(task = task)

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BadgeChip(
                            text = when (task.downloadType) {
                                DownloadType.VIDEO_WITH_AUDIO -> "${strings.badgeVideo}: ${task.selectedResolution}"
                                DownloadType.VIDEO_ONLY -> "${strings.badgeMute}: ${task.selectedResolution}"
                                DownloadType.AUDIO_ONLY -> "${strings.badgeAudio}: ${task.audioFormat.ext.uppercase()}"
                                DownloadType.GIF -> "${strings.badgeGif}: GIF"
                                DownloadType.COVER -> "${strings.badgeCover}: 原图"
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                        BadgeChip(
                            text = statusText,
                            color = statusColor
                        )
                    }
                }
            }

            // 进度条与速度区域：高频 StateFlow 读取完全隔离在该独立微组件内
            if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PROCESSING) {
                Spacer(modifier = Modifier.height(12.dp))
                TaskDownloadingProgressSection(
                    fallbackStatus = task.status,
                    fallbackProgress = task.progress,
                    fallbackSpeedText = task.speedText,
                    fallbackEtaText = task.etaText,
                    progressFlow = resolvedProgressFlow
                )
            } else if (task.status == TaskStatus.FAILED && task.errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                TaskErrorSection(errorMessage = task.errorMessage)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 底部操作区（仅在任务状态变更时被调用，下载推进时 100% 保持跳过）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TaskStatus.DOWNLOADING, TaskStatus.PROCESSING, TaskStatus.PENDING -> {
                        OmniPillButton(
                            text = strings.cancel,
                            icon = Icons.Outlined.Close,
                            primary = false,
                            onClick = { onCancel(task.id) }
                        )
                    }

                    TaskStatus.COMPLETED -> {
                        OmniPillButton(
                            text = if (task.downloadType == DownloadType.GIF || task.downloadType == DownloadType.COVER) "查看" else strings.play,
                            icon = when (task.downloadType) {
                                DownloadType.COVER -> Icons.Outlined.Image
                                DownloadType.GIF -> Icons.Outlined.Visibility
                                else -> Icons.Outlined.PlayArrow
                            },
                            primary = true,
                            onClick = {
                                if (isGifTask) {
                                    showGifPreviewDialog = true
                                } else {
                                    openMediaFile(context, task.localFilePath, task.downloadType)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TaskIconBtn(
                            icon = Icons.Outlined.Share,
                            contentDescription = "分享",
                            onClick = { shareMediaFile(context, task.localFilePath, task.title) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        TaskIconBtn(
                            icon = Icons.Outlined.Delete,
                            contentDescription = strings.delete,
                            onClick = { onDelete(task.id, true) }
                        )
                    }

                    TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                        OmniPillButton(
                            text = strings.retry,
                            icon = Icons.Outlined.Refresh,
                            primary = true,
                            onClick = { onRetry(task.url) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TaskIconBtn(
                            icon = Icons.Outlined.Delete,
                            contentDescription = strings.delete,
                            onClick = { onDelete(task.id, false) }
                        )
                    }
                }
            }
        }
    }

    // 应用内原生无缝动图无限循环播放弹窗
    if (showGifPreviewDialog) {
        val targetFile = File(task.localFilePath)
        AlertDialog(
            onDismissRequest = { showGifPreviewDialog = false },
            title = {
                Text(
                    text = "GIF 动图无限循环播放",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (targetFile.exists()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            AsyncImage(
                                model = targetFile,
                                contentDescription = "GIF预览",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Text(
                            text = "动图文件不存在或已被删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openMediaFile(context, task.localFilePath, task.downloadType)
                }) {
                    Text("系统相册打开")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        shareMediaFile(context, task.localFilePath, task.title)
                    }) {
                        Text("分享")
                    }
                    TextButton(onClick = { showGifPreviewDialog = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }
}

/**
 * 封面：90x56dp 展示区，对应 180x112 物理像素的单一解码尺寸。
 */
@Composable
private fun TaskCover(task: DownloadTask) {
    val context = LocalContext.current
    val mediaCoverModel = remember(task.thumbnailUrl) {
        task.thumbnailUrl.ifBlank { null }
    }
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (task.downloadType) {
                DownloadType.AUDIO_ONLY -> Icons.Outlined.Audiotrack
                DownloadType.VIDEO_ONLY -> Icons.AutoMirrored.Outlined.VolumeOff
                DownloadType.GIF -> Icons.Outlined.Gif
                DownloadType.COVER -> Icons.Outlined.Image
                else -> Icons.Outlined.Videocam
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            modifier = Modifier.size(24.dp)
        )

        val imageRequest = remember(mediaCoverModel, context) {
            if (mediaCoverModel == null) null
            else {
                ImageRequest.Builder(context)
                    .data(mediaCoverModel)
                    .size(184, 116)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        if (mediaCoverModel.contains("bilibili") || mediaCoverModel.contains("hdslb")) {
                            setHeader("Referer", "https://www.bilibili.com/")
                        }
                    }
                    .build()
            }
        }

        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = "封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 失败信息条：点击查看完整日志。
 */
@Composable
private fun TaskErrorSection(errorMessage: String) {
    val context = LocalContext.current
    var showErrorDetailDialog by remember { mutableStateOf(false) }
    val statusColors = LocalStatusColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(statusColors.error.copy(alpha = 0.08f))
            .clickable { showErrorDetailDialog = true }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "错误: $errorMessage",
            style = MaterialTheme.typography.bodySmall,
            color = statusColors.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "查看详情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }

    if (showErrorDetailDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDetailDialog = false },
            icon = { Icon(Icons.Outlined.Error, contentDescription = null, tint = statusColors.error) },
            title = { Text("下载失败详细日志", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clip = android.content.ClipData.newPlainText("ErrorLog", errorMessage)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制错误日志", Toast.LENGTH_SHORT).show()
                    showErrorDetailDialog = false
                }) {
                    Text("复制日志")
                }
            },
            dismissButton = {
                TextButton(onClick = { showErrorDetailDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
internal fun BadgeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * 局部隔离的下载进度微组件：
 * 高频进度变动的观察作用域被严格限制在当前函数内部，上层 TaskCard 完全不受重组污染。
 */
@Composable
private fun TaskDownloadingProgressSection(
    fallbackStatus: TaskStatus,
    fallbackProgress: Float,
    fallbackSpeedText: String,
    fallbackEtaText: String,
    progressFlow: StateFlow<TaskProgress>? = null
) {
    // 页面不可见时不再订阅高频进度流：隐藏页常驻在组合树中，
    // 继续收集会让进度动画持续占用帧，拖慢当前可见页面的滚动。
    val isActive = LocalTabActive.current
    val fallbackState = remember(fallbackProgress, fallbackStatus, fallbackSpeedText, fallbackEtaText) {
        TaskProgress(fallbackProgress, fallbackSpeedText, fallbackEtaText, fallbackStatus)
    }
    val liveProgress = if (isActive && progressFlow != null) {
        progressFlow.collectAsStateWithLifecycle().value
    } else {
        fallbackState
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 进度直接使用最新值，不做插值动画：
        // 下载回调本身已是连续更新，额外的 tween 会在滚动时持续产生动画帧。
        LinearProgressIndicator(
            progress = { (liveProgress.progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (liveProgress.status == TaskStatus.PROCESSING) {
                    "正在合并音视频轨..."
                } else {
                    "${liveProgress.progress.toInt()}% · ${liveProgress.speedText}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (liveProgress.etaText.isNotEmpty()) {
                Text(
                    text = "剩余 ${liveProgress.etaText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskIconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit
) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openMediaFile(context: Context, filePath: String, downloadType: DownloadType) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val mimeType = when (downloadType) {
            DownloadType.AUDIO_ONLY -> "audio/*"
            DownloadType.COVER -> "image/*"
            DownloadType.GIF -> "image/gif"
            else -> "video/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "未找到可打开此文件的播放器或相册", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMediaFile(context: Context, filePath: String, title: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (filePath.endsWith(".mp3") || filePath.endsWith(".m4a") || filePath.endsWith(".flac")) "audio/*" else "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享视频").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
