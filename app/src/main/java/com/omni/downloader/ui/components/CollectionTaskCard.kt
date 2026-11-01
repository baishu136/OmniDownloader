package com.omni.downloader.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.repository.TaskProgress
import com.omni.downloader.ui.design.LocalTabActive
import com.omni.downloader.ui.localization.AppStrings
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.ErrorRed
import com.omni.downloader.ui.theme.SuccessGreen
import com.omni.downloader.ui.theme.WarningOrange
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Collection card with bounded lazy episode details and isolated live progress. */
@Composable
fun CollectionTaskCard(
    collectionId: String,
    collectionTitle: String,
    tasks: List<DownloadTask>,
    onCancelCollection: (colId: String) -> Unit,
    onDeleteCollection: (colId: String, deleteFile: Boolean) -> Unit,
    onRetryCollection: (colId: String) -> Unit,
    onRetryEpisode: (taskId: String) -> Unit,
    progressFlowProvider: ((String) -> StateFlow<TaskProgress>)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val statusColors = com.omni.downloader.ui.theme.LocalStatusColors.current

    val totalCount = tasks.size
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val failedCount = tasks.count { it.status == TaskStatus.FAILED }
    val activeTask = tasks.find { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING }
    val isAllCompleted = completedCount == totalCount && totalCount > 0
    val isDownloading = tasks.any { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING }
    val isPending = tasks.any { it.status == TaskStatus.PENDING } && !isDownloading
    val hasFailed = tasks.any { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }

    val coverUrl = remember(tasks) {
        tasks.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl ?: ""
    }

    var expanded by rememberSaveable(collectionId) { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteLocalFiles by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：合集封面、合集标题与状态徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 92.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    val coverRequest = remember(coverUrl, context) {
                        if (coverUrl.isBlank()) null
                        else {
                            ImageRequest.Builder(context)
                                .data(coverUrl)
                                .size(184, 116)
                                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .apply {
                                    if (coverUrl.contains("bilibili") || coverUrl.contains("hdslb")) {
                                        setHeader("Referer", "https://www.bilibili.com/")
                                    }
                                }
                                .build()
                        }
                    }

                    if (coverRequest != null) {
                        AsyncImage(
                            model = coverRequest,
                            contentDescription = "合集封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 封面右下角贴片：标记合集总数
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(topStart = 4.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = "${totalCount}P",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = collectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // 模式与状态徽章
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BadgeChip(
                            text = "${strings.collectionBadge} · ${String.format(strings.collectionEpisodesCount, totalCount)}",
                            color = MaterialTheme.colorScheme.primary
                        )

                        val statusColor = when {
                            isAllCompleted -> statusColors.success
                            isDownloading -> MaterialTheme.colorScheme.primary
                            hasFailed -> statusColors.error
                            else -> statusColors.warning
                        }

                        val statusText = when {
                            isAllCompleted -> strings.statusCompleted
                            isDownloading -> "${strings.statusDownloading} (${(completedCount + 1).coerceAtMost(totalCount)}/$totalCount)"
                            isPending -> strings.statusPending
                            hasFailed -> if (failedCount > 0) "失败 $failedCount 集" else strings.statusCancelled
                            else -> strings.statusPending
                        }

                        BadgeChip(
                            text = statusText,
                            color = statusColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条与子任务进度微组件：隔离高频 StateFlow 读取
            CollectionProgressSection(
                tasks = tasks,
                completedCount = completedCount,
                totalCount = totalCount,
                isAllCompleted = isAllCompleted,
                activeTask = activeTask,
                strings = strings,
                progressFlowProvider = progressFlowProvider
            )

            // 可折叠分集明细列表
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) strings.collectionCollapseDetails else String.format(strings.collectionExpandDetails, totalCount, completedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (expanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 280.dp)
                ) {
                    items(tasks, key = { it.id }, contentType = { "episode" }) { subTask ->

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.size(width = 30.dp, height = 20.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "P${subTask.episodeIndex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = subTask.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            when (subTask.status) {
                                TaskStatus.COMPLETED -> {
                                    IconButton(
                                        onClick = {
                                            openMediaFile(context, subTask.localFilePath, subTask.downloadType)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayCircleOutline,
                                            contentDescription = "播放",
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                TaskStatus.DOWNLOADING, TaskStatus.PROCESSING -> {
                                    Text(
                                        text = "${episodeProgress(subTask, progressFlowProvider).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TaskStatus.FAILED -> {
                                    IconButton(
                                        onClick = { onRetryEpisode(subTask.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "重试",
                                            tint = ErrorRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                TaskStatus.PENDING -> {
                                    Text(
                                        text = strings.statusPending,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TaskStatus.CANCELLED -> {
                                    Text(
                                        text = strings.statusCancelled,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarningOrange
                                    )
                                }
                            }
                        }
                    }


                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 底部操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDownloading || isPending) {
                    CollectionActionBtn(
                        icon = Icons.Outlined.Close,
                        text = strings.collectionCancelAll,
                        isPrimary = false,
                        onClick = { showCancelDialog = true }
                    )
                }

                if (isAllCompleted) {
                    val firstCompleted = tasks.firstOrNull { it.status == TaskStatus.COMPLETED && it.localFilePath.isNotEmpty() }
                    if (firstCompleted != null) {
                        CollectionActionBtn(
                            icon = Icons.Outlined.PlayArrow,
                            text = strings.collectionPlayFirst,
                            isPrimary = true,
                            onClick = { openMediaFile(context, firstCompleted.localFilePath, firstCompleted.downloadType) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                if (hasFailed && !isDownloading) {
                    CollectionActionBtn(
                        icon = Icons.Outlined.Refresh,
                        text = strings.collectionRetryFailed,
                        isPrimary = true,
                        onClick = { onRetryCollection(collectionId) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (isAllCompleted || hasFailed) {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = strings.delete,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = strings.collectionDeleteConfirmTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = String.format(strings.collectionDeleteConfirmMessage, totalCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteLocalFiles = !deleteLocalFiles }
                    ) {
                        Checkbox(
                            checked = deleteLocalFiles,
                            onCheckedChange = { deleteLocalFiles = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "同时删除已下载的本地视频文件",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCollection(collectionId, deleteLocalFiles)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = {
                Text(
                    text = strings.collectionCancelConfirmTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = String.format(strings.collectionCancelConfirmMessage, collectionTitle),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCancelCollection(collectionId)
                        showCancelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.confirmStopAndClean)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

/**
 * 局部隔离的合集进度微组件：
 * 高频子任务进度的监听严格限制在当前函数内部，使得上层 CollectionTaskCard 完全不受重组污染
 */
@Composable
private fun CollectionProgressSection(
    tasks: List<DownloadTask>,
    completedCount: Int,
    totalCount: Int,
    isAllCompleted: Boolean,
    activeTask: DownloadTask?,
    strings: AppStrings,
    progressFlowProvider: ((String) -> StateFlow<TaskProgress>)?
) {
    // 隐藏页面中不再订阅高频子任务进度，避免后台动画与可见页滚动抢帧
    val isActive = LocalTabActive.current
    val activeProgressState = if (isActive && activeTask != null && progressFlowProvider != null) {
        progressFlowProvider(activeTask.id).collectAsStateWithLifecycle().value
    } else null
    val currentProgress = activeProgressState?.progress ?: (activeTask?.progress ?: 0f)
    val currentSpeed = activeProgressState?.speedText ?: (activeTask?.speedText ?: "")
    val currentEta = activeProgressState?.etaText ?: (activeTask?.etaText ?: "")

    // Only the active episode changes per tick; sum the fixed portion once.
    val fixedProgress = remember(tasks, activeTask?.id) {
        tasks.sumOf { task ->
            when {
                task.id == activeTask?.id -> 0.0
                task.status == TaskStatus.COMPLETED -> 100.0
                task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PROCESSING -> task.progress.coerceIn(0f, 100f).toDouble()
                else -> 0.0
            }
        }.toFloat()
    }
    val overallProgress = if (totalCount == 0) 0f else
        ((fixedProgress + currentProgress.coerceIn(0f, 100f)) / totalCount).coerceIn(0f, 100f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. 总进度条
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(strings.collectionTotalProgress, completedCount, totalCount, overallProgress.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${overallProgress.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isAllCompleted) SuccessGreen else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { (overallProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(3.5.dp)),
                color = if (isAllCompleted) SuccessGreen else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // 2. 当前分集子进度
        if (activeTask != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Downloading,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(strings.collectionCurrentSubTask, activeTask.episodeIndex, totalCount, activeTask.title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { (currentProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${currentProgress.toInt()}% · ${currentSpeed.ifBlank { "准备中" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        if (currentEta.isNotBlank()) {
                            Text(
                                text = "剩余 $currentEta",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    com.omni.downloader.ui.design.OmniPillButton(
        text = text, icon = icon, primary = isPrimary, onClick = onClick
    )
}

private fun openMediaFile(context: Context, filePath: String, downloadType: DownloadType) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
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

@Composable
private fun episodeProgress(task: DownloadTask, provider: ((String) -> StateFlow<TaskProgress>)?): Float {
    val flow = remember(task.id, provider) { provider?.invoke(task.id) }
    return if (LocalTabActive.current && flow != null) flow.collectAsStateWithLifecycle().value.progress else task.progress
}
