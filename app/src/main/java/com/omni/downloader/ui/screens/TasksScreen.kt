package com.omni.downloader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.downloader.ui.components.CollectionTaskCard
import com.omni.downloader.ui.components.TaskCard
import com.omni.downloader.ui.design.OmniEmptyState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.downloader.ui.model.TaskDisplayItem
import com.omni.downloader.ui.design.RetainedTabHost
import com.omni.downloader.ui.design.OmniMotion
import com.omni.downloader.ui.design.OmniSegmentedTabs
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val taskListState by viewModel.taskListState.collectAsStateWithLifecycle()
    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }
    val tabListStates = List(3) { rememberSaveable(saver = LazyListState.Saver) { LazyListState() } }
    val downloadingCount = taskListState.pages[1].size
    val completedCount = taskListState.pages[2].size
    val tabs = remember(strings, taskListState.pages[0].size, downloadingCount, completedCount) {
        listOf(
            "${strings.tabAll} (${taskListState.pages[0].size})",
            "${strings.tabDownloading} ($downloadingCount)",
            "${strings.tabCompleted} ($completedCount)"
        )
    }
    val hasClearableTasks = taskListState.hasClearableTasks[selectedSubTab]

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部大标题 + 清除操作（右侧恒定占位，避免按钮显隐导致标题抖动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.tasksTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${strings.tabDownloading} $downloadingCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = hasClearableTasks,
                    enter = fadeIn(tween(OmniMotion.NORMAL)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(OmniMotion.FAST)) + scaleOut(targetScale = 0.8f)
                ) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = strings.clearCompleted,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        OmniSegmentedTabs(
            titles = tabs,
            selectedIndex = selectedSubTab,
            onSelect = { selectedSubTab = it },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        RetainedTabHost(
            selectedTab = selectedSubTab,
            tabCount = 3,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { subTab ->
            TaskListPane(
                subTab = subTab,
                pageItems = taskListState.pages[subTab],
                viewModel = viewModel,
                listState = tabListStates[subTab]
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskListPane(
    subTab: Int,
    pageItems: List<TaskDisplayItem>,
    viewModel: MainViewModel,
    listState: LazyListState
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current

    if (pageItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            OmniEmptyState(
                icon = when (subTab) {
                    1 -> Icons.Outlined.Download
                    2 -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.Inbox
                },
                title = when (subTab) {
                    1 -> strings.emptyDownloading
                    2 -> strings.emptyCompleted
                    else -> strings.emptyAll
                },
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

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

    val progressFlowProvider = remember(viewModel) { { id: String -> viewModel.getTaskProgress(id) } }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
            val placementModifier = Modifier
            when (item) {
                is TaskDisplayItem.Single -> {
                    TaskCard(
                        task = item.task,
                        onCancel = onCancelTask,
                        onDelete = onDeleteTask,
                        onRetry = onRetryTask,
                        progressFlowProvider = progressFlowProvider,
                        modifier = placementModifier
                    )
                }
                is TaskDisplayItem.Collection -> {
                    CollectionTaskCard(
                        collectionId = item.collectionId,
                        collectionTitle = item.collectionTitle,
                        tasks = item.tasks,
                        onCancelCollection = onCancelCollection,
                        onDeleteCollection = onDeleteCollection,
                        onRetryCollection = onRetryCollection,
                        onRetryEpisode = onRetryEpisode,
                        progressFlowProvider = progressFlowProvider,
                        modifier = placementModifier
                    )
                }
            }
        }
    }
}
