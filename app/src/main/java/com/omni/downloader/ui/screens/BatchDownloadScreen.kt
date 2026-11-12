package com.omni.downloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import com.omni.downloader.ui.localization.LocalAppStrings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.downloader.data.model.*
import com.omni.downloader.engine.*
import com.omni.downloader.ui.viewmodel.MainViewModel

@Composable fun BatchDownloadScreen(viewModel: MainViewModel, onTasks: () -> Unit, onSingle: () -> Unit) {
    val controller = viewModel.batchDownloads
    val drafts = viewModel.inputDrafts
    val draft by drafts.draft.collectAsStateWithLifecycle()
    val ready by drafts.ready.collectAsStateWithLifecycle()
    val draftError by drafts.error.collectAsStateWithLifecycle()
    val plan by controller.queue.plan.collectAsStateWithLifecycle()
    val running by controller.queue.running.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    var inputError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val keyboard = LocalSoftwareKeyboardController.current
    val text = if (draft.batchManual) draft.batchEntry else draft.batchText
    fun setText(value: String) { if (value.length <= 100_000) drafts.update { if (it.batchManual) it.copy(batchEntry = value) else it.copy(batchText = value) } }
    fun add(pastedText: String? = null): List<String>? = try {
        val current = drafts.draft.value
        val value = pastedText ?: if (current.batchManual) current.batchEntry else current.batchText
        val links = if (value.isBlank()) current.batchLinks else BatchInput.merge(current.batchLinks, value, current.batchManual)
        require(links.isNotEmpty()) { "请先填入链接" }
        drafts.update { it.copy(batchLinks = links, batchText = if (current.batchManual) it.batchText else "", batchEntry = if (current.batchManual) "" else it.batchEntry) }
        inputError = null; keyboard?.hide(); links
    } catch (failure: Exception) { inputError = failure.message; null }
    fun pasteAndAdd() {
        try {
            val value = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            require(value.isNotBlank()) { "剪贴板为空" }
            require(value.length <= 100_000) { "文本过长，请分批添加" }
            setText(value)
            add(value)
        } catch (failure: Exception) { inputError = failure.message ?: "无法读取剪贴板" }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空已填链接？") },
        text = { Text("未完成批次仍会保留，可继续处理。") },
        confirmButton = { TextButton(onClick = { drafts.update { it.copy(batchText = "", batchEntry = "", batchLinks = emptyList()) }; confirmClear = false }) { Text("清空输入") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    LazyColumn(Modifier.fillMaxSize().testTag("batch_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("批量下载", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !draft.batchManual, onClick = { drafts.update { it.copy(batchManual = false) } }, label = { Text("智能识别") }, enabled = ready, modifier = Modifier.testTag("batch_smart_mode"))
                FilterChip(selected = draft.batchManual, onClick = { drafts.update { it.copy(batchManual = true) } }, label = { Text("逐条添加") }, enabled = ready, modifier = Modifier.testTag("batch_manual_mode"))
            }
            OutlinedTextField(value = text, onValueChange = ::setText, enabled = ready,
                label = { Text(if (draft.batchManual) "填入一条链接或单条分享文本" else "粘贴多条链接或完整分享文本") },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { setText(""); inputError = null }, enabled = ready,
                        modifier = Modifier.testTag("batch_clear_icon")) {
                        Icon(Icons.Outlined.Clear, contentDescription = strings.clear, modifier = Modifier.size(19.dp))
                    } else IconButton(onClick = ::pasteAndAdd, enabled = ready, modifier = Modifier.testTag("batch_paste_icon")) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = strings.paste, modifier = Modifier.size(19.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("batch_input"), minLines = if (draft.batchManual) 1 else 3, maxLines = if (draft.batchManual) 2 else 5)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::pasteAndAdd, enabled = ready, modifier = Modifier.testTag("batch_paste")) { Text("粘贴剪贴板") }
                FilledTonalButton(onClick = { add() }, enabled = ready && text.isNotBlank(), modifier = Modifier.testTag("batch_add")) { Text(if (draft.batchManual) "添加链接" else "识别并添加") }
            }
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchChoice(draft.batchOptions.type.label, ready && !running) { close ->
                    listOf(DownloadType.VIDEO_WITH_AUDIO, DownloadType.VIDEO_ONLY, DownloadType.AUDIO_ONLY, DownloadType.GIF).forEach { value ->
                        DropdownMenuItem(text = { Text(value.label) }, onClick = { drafts.update { it.copy(batchOptions = it.batchOptions.copy(type = value)) }; close() })
                    }
                }
                if (draft.batchOptions.type == DownloadType.AUDIO_ONLY) BatchChoice(draft.batchOptions.audio.ext.uppercase(), ready && !running) { close ->
                    AudioFormat.entries.forEach { value -> DropdownMenuItem(text = { Text(value.ext.uppercase()) }, onClick = { drafts.update { it.copy(batchOptions = it.batchOptions.copy(audio = value)) }; close() }) }
                }
            }
            Row { Checkbox(checked = draft.batchOptions.allEpisodes, onCheckedChange = { value -> drafts.update { it.copy(batchOptions = it.batchOptions.copy(allEpisodes = value)) } }, enabled = ready && !running); Text("下载合集 / 多视频的全部分集", Modifier.padding(top = 12.dp)) }
        }
        item {
            Button(onClick = { add()?.let { controller.start(it.joinToString("\n"), drafts.draft.value.batchOptions, preservePending = true) } }, enabled = ready && !running && (text.isNotBlank() || draft.batchLinks.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("batch_start")) { Text("解析并加入下载队列") }
            if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = controller::cancel) { Text("停止添加（已入队任务继续下载）") } }
            if (!running && plan.items.any { it.status != BatchStatus.QUEUED }) {
                TextButton(onClick = controller::retry) { Text("继续 / 重试未入队条目") }
            }
            (error ?: draftError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("已入队 ${plan.items.count { it.status == BatchStatus.QUEUED }} / ${plan.items.size} 条链接 · ${plan.items.sumOf { it.taskIds.size }} 个任务")
            TextButton(onClick = onTasks) { Text("查看下载任务") }
        }
        item {
            Row {
                Text("已填链接 ${draft.batchLinks.size} / 100", Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { confirmClear = true }, enabled = ready && (draft.batchLinks.isNotEmpty() || text.isNotBlank())) { Text("清空输入") }
            }
        }
        items(draft.batchLinks, key = { "draft:$it" }) { url ->
            OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
                Text(url, Modifier.weight(1f), maxLines = 3)
                TextButton(onClick = { drafts.update { it.copy(batchLinks = it.batchLinks - url) } }) { Text("移除") }
            } }
        }
        if (plan.items.isNotEmpty()) item { Text("批次处理记录", style = MaterialTheme.typography.titleMedium) }
        items(plan.items, key = { it.id }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(item.url, maxLines = 2)
                Text(item.note.ifBlank { "等待处理" }, color = if (item.status == BatchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!running && item.status == BatchStatus.FAILED) TextButton(onClick = { onSingle(); viewModel.updateInputUrl(item.url); viewModel.startAnalyze() }) { Text("单独解析 / 验证") }
            } }
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
