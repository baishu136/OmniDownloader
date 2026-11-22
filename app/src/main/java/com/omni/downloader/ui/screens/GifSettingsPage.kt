package com.omni.downloader.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.service.DownloadService
import com.omni.downloader.ui.localization.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
internal fun GifSettingsPage() {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val repository = remember { TaskRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val tasks by remember(repository) { repository.tasks.map { rows -> rows.filter {
        it.downloadType == DownloadType.GIF || (it.status == TaskStatus.COMPLETED && it.downloadType == DownloadType.IMAGE)
    }.map { it.copy(progress = 0f, speedText = "", etaText = "") } }.distinctUntilChanged() }.collectAsStateWithLifecycle(emptyList())
    var entries by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var deleting by remember { mutableStateOf<DownloadTask?>(null) }
    LaunchedEffect(tasks) {
        entries = withContext(Dispatchers.IO) { tasks.filter { task ->
            task.downloadType == DownloadType.GIF || task.localFilePath.endsWith(".gif", true) ||
                runCatching { context.contentResolver.getType(Uri.parse(task.localFilePath)) == "image/gif" }.getOrDefault(false)
        } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                repository.awaitLoaded()
                val task = DownloadTask(id = UUID.randomUUID().toString(), url = uri.toString(),
                    title = strings.gifConvertLocal, downloadType = DownloadType.GIF)
                repository.addTask(task)
                DownloadService.startDownload(context, task.id)
            }.onFailure { Toast.makeText(context, it.message ?: strings.gifCannotReadVideo, Toast.LENGTH_LONG).show() }
        }
    }
    fun open(task: DownloadTask, share: Boolean) {
        runCatching {
            val uri = if (task.localFilePath.startsWith("content://")) Uri.parse(task.localFilePath)
                else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(task.localFilePath))
            val intent = if (share) Intent(Intent.ACTION_SEND).setType("image/gif").putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/gif")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(if (share) Intent.createChooser(intent, strings.gifShareChooser) else intent)
        }.onFailure { Toast.makeText(context, strings.gifNoApp, Toast.LENGTH_LONG).show() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings_gif"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
        Button(shape = MaterialTheme.shapes.medium, onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text(strings.gifChooseVideo) }
        Text(strings.gifBackgroundHint)
        if (entries.isEmpty()) Text(strings.gifEmpty)
        }
        items(entries, key = { it.id }) { task ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(task.title)
                    Text(taskStatusLabel(task.status))
                    Row {
                        if (task.status == TaskStatus.COMPLETED) {
                            TextButton(onClick = { open(task, false) }) { Text(strings.open) }
                            TextButton(onClick = { open(task, true) }) { Text(strings.share) }
                            TextButton(onClick = { deleting = task }) { Text(strings.delete) }
                        } else if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
                            TextButton(onClick = { repository.updateTaskProgress(task.id, 0f, "", "", TaskStatus.PENDING); DownloadService.startDownload(context, task.id) }) { Text(strings.retry) }
                        } else {
                            TextButton(onClick = { DownloadService.cancelDownload(context, task.id) }) { Text(strings.cancel) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(strings.gifDeleteTitle) },
        text = { Text(strings.gifDeleteMessage) }, confirmButton = {
            TextButton(onClick = { scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    if (task.localFilePath.startsWith("content://")) context.contentResolver.delete(Uri.parse(task.localFilePath), null, null)
                    else { val file = File(task.localFilePath); check(!file.exists() || file.delete()) { strings.gifCannotDeleteFile } }
                    repository.removeTask(task.id)
                } }.onSuccess { deleting = null }.onFailure { Toast.makeText(context, strings.gifDeleteFailed, Toast.LENGTH_LONG).show() }
            } }) { Text(strings.delete) }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.cancel) } }) }
}

@Composable
private fun taskStatusLabel(status: TaskStatus): String {
    val s = LocalAppStrings.current
    return when (status) {
        TaskStatus.PENDING -> s.statusPending
        TaskStatus.DOWNLOADING -> s.statusDownloading
        TaskStatus.PROCESSING -> s.statusProcessing
        TaskStatus.COMPLETED -> s.statusCompleted
        TaskStatus.FAILED -> s.statusFailed
        TaskStatus.WAITING_FOR_AUTH -> s.statusWaitingAuth
        TaskStatus.CANCELLED -> s.statusCancelled
    }
}
