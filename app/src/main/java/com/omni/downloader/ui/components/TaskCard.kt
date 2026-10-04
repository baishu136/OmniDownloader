package com.omni.downloader.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.ErrorRed
import com.omni.downloader.ui.theme.SuccessGreen
import com.omni.downloader.ui.theme.WarningOrange
import java.io.File

@Composable
fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onDelete: (deleteFile: Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val isGifTask = task.downloadType == DownloadType.GIF || task.localFilePath.endsWith(".gif", ignoreCase = true)
    var showGifPreviewDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：封面、标题与状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                val mediaCoverModel = remember(task.thumbnailUrl) {
                    task.thumbnailUrl.ifBlank { null }
                }

                Box(
                    modifier = Modifier
                        .size(width = 90.dp, height = 54.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    // 底层：轻量级静态占位图标，零动画开销
                    Icon(
                        imageVector = when (task.downloadType) {
                            DownloadType.AUDIO_ONLY -> Icons.Default.Audiotrack
                            DownloadType.VIDEO_ONLY -> Icons.Default.VolumeOff
                            DownloadType.GIF -> Icons.Default.Gif
                            DownloadType.COVER -> Icons.Default.Image
                            else -> Icons.Default.Videocam
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )

                    val imageRequest = remember(mediaCoverModel, context) {
                        if (mediaCoverModel == null) null
                        else {
                            coil.request.ImageRequest.Builder(context)
                                .data(mediaCoverModel)
                                .size(270, 162)
                                .crossfade(false)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .apply {
                                    if (mediaCoverModel.contains("bilibili") || mediaCoverModel.contains("hdslb")) {
                                        setHeader("Referer", "https://www.bilibili.com/")
                                    }
                                }
                                .build()
                        }
                    }

                    // 顶层：普通 AsyncImage 异步解码淡入覆盖，彻底移除 SubcomposeAsyncImage 延迟子组合与无限循环动画
                    if (imageRequest != null) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = "封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // 模式与清晰度徽章
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

                        val statusColor = when (task.status) {
                            TaskStatus.COMPLETED -> SuccessGreen
                            TaskStatus.DOWNLOADING, TaskStatus.PROCESSING -> MaterialTheme.colorScheme.primary
                            TaskStatus.FAILED -> ErrorRed
                            else -> WarningOrange
                        }

                        val statusText = when (task.status) {
                            TaskStatus.PENDING -> strings.statusPending
                            TaskStatus.DOWNLOADING -> strings.statusDownloading
                            TaskStatus.PROCESSING -> strings.statusProcessing
                            TaskStatus.COMPLETED -> strings.statusCompleted
                            TaskStatus.FAILED -> strings.statusFailed
                            TaskStatus.CANCELLED -> strings.statusCancelled
                        }

                        BadgeChip(
                            text = statusText,
                            color = statusColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 进度条与速度
            if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PROCESSING) {
                LinearProgressIndicator(
                    progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
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
                        text = if (task.status == TaskStatus.PROCESSING) "正在合并音视频轨..." else "${task.progress.toInt()}% · ${task.speedText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.etaText.isNotEmpty()) {
                        Text(
                            text = "剩余 ${task.etaText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (task.status == TaskStatus.FAILED && task.errorMessage.isNotEmpty()) {
                var showErrorDetailDialog by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showErrorDetailDialog = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "错误: ${task.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
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
                        icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed) },
                        title = { Text("下载失败详细日志", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = task.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val clip = android.content.ClipData.newPlainText("ErrorLog", task.errorMessage)
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

            Spacer(modifier = Modifier.height(10.dp))

            // 底部操作区（使用高性能轻量按钮，彻底消除 Material3 Button 的深层图层与 CompositionLocal 开销）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TaskStatus.DOWNLOADING, TaskStatus.PROCESSING, TaskStatus.PENDING -> {
                        TaskActionBtn(
                            icon = Icons.Default.Close,
                            text = strings.cancel,
                            isPrimary = false,
                            onClick = onCancel
                        )
                    }
                    TaskStatus.COMPLETED -> {
                        // 播放 / 查看动图与封面按钮
                        TaskActionBtn(
                            icon = when (task.downloadType) {
                                DownloadType.COVER -> Icons.Default.Image
                                DownloadType.GIF -> Icons.Default.Visibility
                                else -> Icons.Default.PlayArrow
                            },
                            text = if (task.downloadType == DownloadType.GIF || task.downloadType == DownloadType.COVER) "查看" else strings.play,
                            isPrimary = true,
                            onClick = {
                                if (isGifTask) {
                                    showGifPreviewDialog = true
                                } else {
                                    openMediaFile(context, task.localFilePath, task.downloadType)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 分享按钮
                        TaskActionBtn(
                            icon = Icons.Default.Share,
                            text = "分享",
                            isPrimary = false,
                            onClick = { shareMediaFile(context, task.localFilePath, task.title) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 删除按钮
                        TaskIconBtn(
                            icon = Icons.Default.DeleteOutline,
                            contentDescription = strings.delete,
                            onClick = { onDelete(true) }
                        )
                    }
                    TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                        TaskActionBtn(
                            icon = Icons.Default.Refresh,
                            text = strings.retry,
                            isPrimary = true,
                            onClick = onRetry
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TaskIconBtn(
                            icon = Icons.Default.DeleteOutline,
                            contentDescription = strings.delete,
                            onClick = { onDelete(false) }
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
                                contentDescription = "GIF 循环动图",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${task.title}\n(${String.format(java.util.Locale.getDefault(), "%.1f MB", targetFile.length() / (1024.0 * 1024.0))})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "动图文件不存在或已被移动",
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

@Composable
internal fun BadgeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TaskActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isPrimary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TaskIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun openMediaFile(context: Context, filePath: String, downloadType: DownloadType) {
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val isImage = downloadType == DownloadType.COVER || file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) || file.name.endsWith(".webp", true)
        val mimeType = when {
            isImage -> "image/*"
            downloadType == DownloadType.AUDIO_ONLY -> "audio/*"
            downloadType == DownloadType.GIF || file.name.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = when {
            isImage -> "查看封面图片"
            downloadType == DownloadType.GIF || file.name.endsWith(".gif", ignoreCase = true) -> "选择相册或看图应用查看动图"
            else -> "选择播放器播放"
        }
        try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: Exception) {
            val isGif = downloadType == DownloadType.GIF || file.name.endsWith(".gif", ignoreCase = true)
            if (isGif) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(fallbackIntent, "选择相册或看图应用查看动图"))
            } else {
                throw e
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开媒体文件: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMediaFile(context: Context, filePath: String, title: String) {
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val isImage = file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) || file.name.endsWith(".webp", true) || file.name.endsWith(".gif", true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when {
                isImage -> "image/*"
                file.name.endsWith(".mp3") || file.name.endsWith(".m4a") || file.name.endsWith(".flac") -> "audio/*"
                else -> "video/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享到..."))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
