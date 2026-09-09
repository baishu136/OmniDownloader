package com.omni.downloader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.components.CollectionTaskCard
import com.omni.downloader.ui.components.TaskCard
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer

// 舒缓吸附视觉缓冲曲线：起步平滑柔顺、中段快速推进、终段以零斜率阻尼自然贴合靠岸
private val MotionCushionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

private sealed class TaskDisplayItem {
    data class Single(val task: DownloadTask) : TaskDisplayItem()
    data class Collection(
        val collectionId: String,
        val collectionTitle: String,
        val tasks: List<DownloadTask>
    ) : TaskDisplayItem()
}

private fun aggregateTasks(tasks: List<DownloadTask>): List<TaskDisplayItem> {
    val result = mutableListOf<TaskDisplayItem>()
    val processedCollectionIds = mutableSetOf<String>()

    tasks.forEach { task ->
        val colId = task.collectionId
        if (colId != null) {
            if (colId !in processedCollectionIds) {
                processedCollectionIds.add(colId)
                val groupTasks = tasks.filter { it.collectionId == colId }
                    .sortedBy { if (it.episodeIndex > 0) it.episodeIndex else Int.MAX_VALUE }
                val title = task.collectionTitle ?: groupTasks.firstOrNull()?.title ?: "视频合集"
                result.add(TaskDisplayItem.Collection(colId, title, groupTasks))
            }
        } else {
            result.add(TaskDisplayItem.Single(task))
        }
    }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    val tasks by viewModel.tasks.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 将底层任务根据合集维度进行聚合，不再逐一堆叠
    val allDisplayItems = remember(tasks) { aggregateTasks(tasks) }

    // 0: 全部, 1: 下载中, 2: 已完成
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var showClearDialog by remember { mutableStateOf(false) }

    val downloadingCount = remember(allDisplayItems) {
        allDisplayItems.count { item ->
            when (item) {
                is TaskDisplayItem.Single -> item.task.status == TaskStatus.DOWNLOADING || item.task.status == TaskStatus.PROCESSING || item.task.status == TaskStatus.PENDING
                is TaskDisplayItem.Collection -> item.tasks.any { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }
            }
        }
    }
    val completedCount = remember(allDisplayItems) {
        allDisplayItems.count { item ->
            when (item) {
                is TaskDisplayItem.Single -> item.task.status == TaskStatus.COMPLETED
                is TaskDisplayItem.Collection -> item.tasks.isNotEmpty() && item.tasks.all { it.status == TaskStatus.COMPLETED }
            }
        }
    }

    val tabs = remember(strings, allDisplayItems.size, downloadingCount, completedCount) {
        listOf(
            "${strings.tabAll} (${allDisplayItems.size})",
            "${strings.tabDownloading} ($downloadingCount)",
            "${strings.tabCompleted} ($completedCount)"
        )
    }

    // 判断是否有可以清除的任务：在「下载中」只要有未完成任务即可清除；在其他页面有结束记录即可清除
    val hasClearableTasks = remember(tasks, pagerState.targetPage) {
        when (pagerState.targetPage) {
            2 -> tasks.any { it.status == TaskStatus.COMPLETED }
            1 -> tasks.any {
                it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.PROCESSING ||
                it.status == TaskStatus.PENDING ||
                it.status == TaskStatus.FAILED ||
                it.status == TaskStatus.CANCELLED
            }
            else -> tasks.isNotEmpty()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题与清除操作（固定 64dp 高度与右侧 48dp 恒定占位，彻底消除上下浮动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.tasksTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasClearableTasks) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = strings.clearCompleted,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 标签切换栏，使用 targetPage 确保点击瞬时即响应启动，零感知延迟
        TabRow(
            selectedTabIndex = pagerState.targetPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.targetPage == index,
                    onClick = {
                        if (pagerState.targetPage != index) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    page = index,
                                    animationSpec = tween(durationMillis = 220, easing = MotionCushionEasing)
                                )
                            }
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (pagerState.targetPage == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // 功能区采用 HorizontalPager，开启 beyondBoundsPageCount=1 提前常驻预热渲染，结合 graphicsLayer 视差与阻尼微缩放提供丝滑视觉缓冲
        HorizontalPager(
            state = pagerState,
            beyondBoundsPageCount = 1,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 性能关键优化：在绘制阶段（Draw Phase）内部读取并计算动画状态，彻底绕过组合阶段，0 次无谓重组！
                        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                        val absOffset = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
                        // 1. 柔和透明度缓冲：翻出页面平滑淡化至 0.65，翻入页面自柔光中显现
                        alpha = 1f - (absOffset * 0.35f)
                        // 2. 景深微缩放缓冲：离开时微收缩 3.5%，进入时优雅舒展，呈现高级空间景深
                        val scale = 1f - (absOffset * 0.035f)
                        scaleX = scale
                        scaleY = scale
                        // 3. 微视差缓冲位移：反向补偿 24dp，产生前后分层视差流动感
                        translationX = pageOffset * 24.dp.toPx()
                    }
            ) {
                val pageItems = remember(allDisplayItems, page) {
                    when (page) {
                        1 -> allDisplayItems.filter { item ->
                            when (item) {
                                is TaskDisplayItem.Single -> item.task.status == TaskStatus.DOWNLOADING || item.task.status == TaskStatus.PROCESSING || item.task.status == TaskStatus.PENDING
                                is TaskDisplayItem.Collection -> item.tasks.any { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }
                            }
                        }
                        2 -> allDisplayItems.filter { item ->
                            when (item) {
                                is TaskDisplayItem.Single -> item.task.status == TaskStatus.COMPLETED
                                is TaskDisplayItem.Collection -> item.tasks.isNotEmpty() && item.tasks.all { it.status == TaskStatus.COMPLETED }
                            }
                        }
                        else -> allDisplayItems
                    }
                }

                if (pageItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when (page) {
                                    1 -> strings.emptyDownloading
                                    2 -> strings.emptyCompleted
                                    else -> strings.emptyAll
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = pageItems,
                            key = { item ->
                                when (item) {
                                    is TaskDisplayItem.Single -> "single_${item.task.id}"
                                    is TaskDisplayItem.Collection -> "col_${item.collectionId}"
                                }
                            }
                        ) { item ->
                            when (item) {
                                is TaskDisplayItem.Single -> {
                                    TaskCard(
                                        task = item.task,
                                        onCancel = { viewModel.cancelTask(context, item.task.id) },
                                        onDelete = { deleteFile -> viewModel.deleteTask(item.task.id, deleteFile) },
                                        onRetry = { viewModel.startAnalyze(item.task.url) }
                                    )
                                }
                                is TaskDisplayItem.Collection -> {
                                    CollectionTaskCard(
                                        collectionTitle = item.collectionTitle,
                                        tasks = item.tasks,
                                        onCancelCollection = { viewModel.cancelCollection(context, item.collectionId) },
                                        onDeleteCollection = { deleteFile -> viewModel.deleteCollection(item.collectionId, deleteFile) },
                                        onRetryCollection = { viewModel.retryCollection(context, item.collectionId) },
                                        onRetryEpisode = { taskId ->
                                            com.omni.downloader.service.DownloadService.startDownload(context, taskId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 清除任务确认弹窗
    if (showClearDialog) {
        val isDownloadingPage = pagerState.targetPage == 1
        val dialogTitle = if (isDownloadingPage) strings.clearDownloadingTitle else strings.clearTasksDialogTitle
        val dialogMessage = if (isDownloadingPage) strings.clearDownloadingMessage else strings.clearTasksDialogMessage
        val confirmText = if (isDownloadingPage) strings.confirmStopAndClean else strings.confirmClear

        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = dialogTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = dialogMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        if (isDownloadingPage) {
                            viewModel.cancelAndCleanAllActiveTasks(context)
                        } else {
                            viewModel.clearFinishedTasks(pagerState.targetPage)
                        }
                    }
                ) {
                    Text(
                        text = confirmText,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = strings.cancel)
                }
            }
        )
    }
}
