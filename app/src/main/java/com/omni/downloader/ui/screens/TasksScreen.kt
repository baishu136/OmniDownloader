package com.omni.downloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.animation.togetherWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.components.TaskCard
import com.omni.downloader.ui.viewmodel.MainViewModel

@Composable
fun TasksScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    val tasks by viewModel.tasks.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("All (${tasks.size})", strings.tabDownloading, strings.tabCompleted)

    val filteredTasks by remember(tasks, selectedTabIndex) {
        derivedStateOf {
            when (selectedTabIndex) {
                1 -> tasks.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }
                2 -> tasks.filter { it.status == TaskStatus.COMPLETED }
                else -> tasks
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题与 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.tasksTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (tasks.any { it.status == TaskStatus.COMPLETED }) {
                IconButton(onClick = {
                    tasks.filter { it.status == TaskStatus.COMPLETED }.forEach {
                        viewModel.deleteTask(it.id, false)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = strings.clearCompleted,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(text = title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        androidx.compose.animation.Crossfade(
            targetState = selectedTabIndex,
            animationSpec = androidx.compose.animation.core.tween(150),
            label = "TaskTabTransition"
        ) { _ ->
            if (filteredTasks.isEmpty()) {
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
                            text = if (selectedTabIndex == 1) strings.emptyDownloading else if (selectedTabIndex == 2) strings.emptyCompleted else strings.emptyDownloading,
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
                    items(filteredTasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onCancel = { viewModel.cancelTask(context, task.id) },
                            onDelete = { deleteFile -> viewModel.deleteTask(task.id, deleteFile) },
                            onRetry = { viewModel.startAnalyze(task.url) }
                        )
                    }
                }
            }
        }
    }
}
