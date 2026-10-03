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

@androidx.compose.runtime.Immutable
private sealed class TaskDisplayItem {
    @androidx.compose.runtime.Immutable
    data class Single(val task: DownloadTask) : TaskDisplayItem()
    @androidx.compose.runtime.Immutable
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
    var selectedSubTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
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
    val hasClearableTasks = remember(tasks, selectedSubTab) {
        when (selectedSubTab) {
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

        // 标签切换栏，点击瞬时即响应启动，零感知延迟
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = {
                        if (selectedSubTab != index) {
                            selectedSubTab = index
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedSubTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // 列表展示区：直通 LazyColumn，无双层 Pager 手势拦截开销，手势与测量直达 60fps 满帧
        val pageItems = remember(allDisplayItems, selectedSubTab) {
            when (selectedSubTab) {
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
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
                            text = when (selectedSubTab) {
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
                val onCancelTask = remember(viewModel, context) {
                    { taskId: String -> viewModel.cancelTask(context, taskId) }
                }
                val onDeleteTask = remember(viewModel) {
                    { taskId: String, deleteFile: Boolean -> viewModel.deleteTask(taskId, deleteFile) }
                }
                val onRetryTask = remember(viewModel) {
                    { url: String -> viewModel.startAnalyze(url) }
                }

                val onCancelCollection = remember(viewModel, context) {
                    { colId: String -> viewModel.cancelCollection(context, colId) }
                }
                val onDeleteCollection = remember(viewModel) {
                    { colId: String, deleteFile: Boolean -> viewModel.deleteCollection(colId, deleteFile) }
                }
                val onRetryCollection = remember(viewModel, context) {
                    { colId: String -> viewModel.retryCollection(context, colId) }
                }
                val onRetryEpisode = remember(context) {
                    { taskId: String -> com.omni.downloader.service.DownloadService.startDownload(context, taskId) }
                }

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
                        },
                        contentType = { item ->
                            when (item) {
                                is TaskDisplayItem.Single -> 0
                                is TaskDisplayItem.Collection -> 1
                            }
                        }
                    ) { item ->
                        when (item) {
                            is TaskDisplayItem.Single -> {
                                val currentId = item.task.id
                                val currentUrl = item.task.url
                                TaskCard(
                                    task = item.task,
                                    onCancel = remember(currentId) { { onCancelTask(currentId) } },
                                    onDelete = remember(currentId) { { deleteFile -> onDeleteTask(currentId, deleteFile) } },
                                    onRetry = remember(currentUrl) { { onRetryTask(currentUrl) } }
                                )
                            }
                            is TaskDisplayItem.Collection -> {
                                val currentColId = item.collectionId
                                CollectionTaskCard(
                                    collectionTitle = item.collectionTitle,
                                    tasks = item.tasks,
                                    onCancelCollection = remember(currentColId) { { onCancelCollection(currentColId) } },
                                    onDeleteCollection = remember(currentColId) { { deleteFile -> onDeleteCollection(currentColId, deleteFile) } },
                                    onRetryCollection = remember(currentColId) { { onRetryCollection(currentColId) } },
                                    onRetryEpisode = onRetryEpisode
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 清除任务确认弹窗
    if (showClearDialog) {
        val isDownloadingPage = selectedSubTab == 1
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
                            viewModel.clearFinishedTasks(selectedSubTab)
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
