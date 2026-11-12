package com.omni.downloader.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.downloader.data.model.*
import com.omni.downloader.engine.*
import com.omni.downloader.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BatchDownloadSheet(viewModel: MainViewModel, onDismiss: () -> Unit, onTasks: () -> Unit) {
    val controller = viewModel.batchDownloads
    val plan by controller.queue.plan.collectAsStateWithLifecycle()
    val running by controller.queue.running.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(DownloadType.VIDEO_WITH_AUDIO) }
    var audio by rememberSaveable { mutableStateOf(AudioFormat.MP3) }
    var all by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("batch_sheet"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("批量下载", style = MaterialTheme.typography.titleLarge)
                Text("每批最多 100 条链接，可直接粘贴分享文本；重复链接自动去重。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 100_000) text = it }, enabled = !running,
                    label = { Text("分享文本或链接（每行一条）") }, modifier = Modifier.fillMaxWidth().testTag("batch_input"), minLines = 3, maxLines = 5)
                TextButton(onClick = { text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(100_000) }, enabled = !running) { Text("粘贴剪贴板") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchChoice(type.label, !running) { close ->
                        listOf(DownloadType.VIDEO_WITH_AUDIO, DownloadType.VIDEO_ONLY, DownloadType.AUDIO_ONLY, DownloadType.GIF).forEach { value ->
                            DropdownMenuItem(text = { Text(value.label) }, onClick = { type = value; close() })
                        }
                    }
                    if (type == DownloadType.AUDIO_ONLY) BatchChoice(audio.ext.uppercase(), !running) { close ->
                        AudioFormat.entries.forEach { value -> DropdownMenuItem(text = { Text(value.ext.uppercase()) }, onClick = { audio = value; close() }) }
                    }
                }
                Row { Checkbox(checked = all, onCheckedChange = { all = it }, enabled = !running); Text("下载合集 / 多视频的全部分集", Modifier.padding(top = 12.dp)) }
                Text("视频使用最高可用画质，图文保存为 ZIP。批次进度表示解析和入队情况，实际下载进度请查看任务页。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { controller.start(text, BatchOptions(type, audio, all)) }, enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("batch_start")) { Text("解析并加入下载队列") }
                if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = controller::cancel) { Text("停止添加（已入队任务继续下载）") } }
                if (!running && plan.items.any { it.status != BatchStatus.QUEUED }) {
                    TextButton(onClick = controller::retry) { Text("继续 / 重试未入队条目") }
                    Text("继续处理沿用原批次的下载设置。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("已入队 ${plan.items.count { it.status == BatchStatus.QUEUED }} / ${plan.items.size} 条链接 · ${plan.items.sumOf { it.taskIds.size }} 个任务")
                TextButton(onClick = onTasks) { Text("查看下载任务") }
            }
            items(plan.items, key = { it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.url, maxLines = 2)
                        Text(item.note.ifBlank { "等待处理" }, color = if (item.status == BatchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!running && item.status == BatchStatus.FAILED) TextButton(onClick = {
                            onDismiss(); viewModel.updateInputUrl(item.url); viewModel.startAnalyze()
                        }) { Text("单独解析 / 验证") }
                    }
                }
            }
        }
    }
}

@Composable private fun BatchChoice(label: String, enabled: Boolean, content: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { content { expanded = false } }
    }
}
