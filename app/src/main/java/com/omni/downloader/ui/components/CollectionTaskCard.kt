package com.omni.downloader.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.ErrorRed
import com.omni.downloader.ui.theme.SuccessGreen
import com.omni.downloader.ui.theme.WarningOrange
import java.io.File

/**
 * 专为多视频/合集下载打造的聚合展示卡片
 * 在下载页面统一呈现总进度条、当前分集子进度与可折叠明细
 */
@Composable
fun CollectionTaskCard(
    collectionTitle: String,
    tasks: List<DownloadTask>,
    onCancelCollection: () -> Unit,
    onDeleteCollection: (deleteFile: Boolean) -> Unit,
    onRetryCollection: () -> Unit,
    onRetryEpisode: (taskId: String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current

    val totalCount = tasks.size
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val failedCount = tasks.count { it.status == TaskStatus.FAILED }
    val activeTask = tasks.find { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING }
    val isAllCompleted = completedCount == totalCount && totalCount > 0
    val isDownloading = tasks.any { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING }
    val isPending = tasks.any { it.status == TaskStatus.PENDING } && !isDownloading
    val hasFailed = tasks.any { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }

    // 加权总进度百分比
    val overallProgress = remember(tasks) {
        if (totalCount == 0) 0f
        else {
            var sum = 0f
            tasks.forEach { t ->
                sum += when (t.status) {
                    TaskStatus.COMPLETED -> 100f
                    TaskStatus.DOWNLOADING, TaskStatus.PROCESSING -> t.progress.coerceIn(0f, 100f)
                    else -> 0f
                }
            }
            (sum / totalCount).coerceIn(0f, 100f)
        }
    }

    val coverUrl = remember(tasks) {
        tasks.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl ?: ""
    }

    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteLocalFiles by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
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
                                imageVector = Icons.Default.VideoLibrary,
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
                            isAllCompleted -> SuccessGreen
                            isDownloading -> MaterialTheme.colorScheme.primary
                            hasFailed -> ErrorRed
                            else -> WarningOrange
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

            // 1. 总进度条 (Overall Progress)
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

            // 2. 当前分集子进度 (Sub-Progress，在下载中或处理中时动态展示)
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
                                imageVector = Icons.Default.Downloading,
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
                            progress = { (activeTask.progress / 100f).coerceIn(0f, 1f) },
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
                                text = "${activeTask.progress.toInt()}% · ${activeTask.speedText.ifBlank { "准备中" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            if (activeTask.etaText.isNotBlank()) {
                                Text(
                                    text = "剩余 ${activeTask.etaText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 3. 可折叠分集明细列表
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
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.6.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    tasks.forEach { subTask ->
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
                                            imageVector = Icons.Default.PlayCircleOutline,
                                            contentDescription = "播放",
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                TaskStatus.DOWNLOADING, TaskStatus.PROCESSING -> {
                                    Text(
                                        text = "${subTask.progress.toInt()}%",
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
                                            imageVector = Icons.Default.Refresh,
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

            // 4. 底部操作栏
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDownloading || isPending) {
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = strings.collectionCancelAll, fontSize = 12.sp)
                    }
                }

                if (hasFailed && !isDownloading) {
                    Button(
                        onClick = onRetryCollection,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = strings.collectionRetryFailed, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (isAllCompleted) {
                    val firstPlayable = tasks.firstOrNull { it.localFilePath.isNotBlank() && File(it.localFilePath).exists() }
                    if (firstPlayable != null) {
                        Button(
                            onClick = {
                                openMediaFile(context, firstPlayable.localFilePath, firstPlayable.downloadType)
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = strings.collectionPlayFirst, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                // 删除按钮
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = strings.collectionDelete, fontSize = 12.sp)
                }
            }
        }
    }

    // 取消确认弹窗
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(text = strings.collectionCancelConfirmTitle, fontWeight = FontWeight.Bold) },
            text = { Text(text = strings.collectionCancelConfirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        onCancelCollection()
                    }
                ) {
                    Text(text = strings.confirmStopAndClean, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(text = strings.cancel)
                }
            }
        )
    }

    // 删除确认弹窗
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = strings.collectionDeleteConfirmTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(text = String.format(strings.collectionDeleteConfirmMessage, totalCount))
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
                        Text(text = "同时删除已下载的本地视频文件", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteCollection(deleteLocalFiles)
                    }
                ) {
                    Text(text = strings.delete, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = strings.cancel)
                }
            }
        )
    }
}
