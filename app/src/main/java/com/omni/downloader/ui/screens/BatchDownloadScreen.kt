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
import com.omni.downloader.ui.components.OmniOptionMenu
import com.omni.downloader.ui.components.OmniOptionMenuItem
import com.omni.downloader.ui.viewmodel.MainViewModel

@Composable fun BatchDownloadScreen(viewModel: MainViewModel, onTasks: () -> Unit, onSingle: () -> Unit) {
    val controller = viewModel.batchDownloads
    val drafts = viewModel.inputDrafts
    val draft by drafts.draft.collectAsStateWithLifecycle()
    val draftReady by drafts.ready.collectAsStateWithLifecycle()
    val batchReady by controller.ready.collectAsStateWithLifecycle()
    val ready = draftReady && batchReady
    val draftError by drafts.error.collectAsStateWithLifecycle()
    val plan by controller.queue.plan.collectAsStateWithLifecycle()
    val running by controller.queue.running.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val preparations by controller.preparationStates.collectAsStateWithLifecycle()
    var inputError by remember { mutableStateOf<String?>(null) }
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val keyboard = LocalSoftwareKeyboardController.current
    val text = if (draft.batchManual) draft.batchEntry else draft.batchText
    fun setText(value: String) { if (value.length <= 100_000) drafts.update { if (it.batchManual) it.copy(batchEntry = value) else it.copy(batchText = value) } }
    fun add(pastedText: String? = null): List<String>? = try {
        val current = drafts.draft.value
        val value = pastedText ?: if (current.batchManual) current.batchEntry else current.batchText
        val links = controller.addLinks(value, current.batchManual)
        inputError = null; keyboard?.hide(); links
    } catch (failure: Exception) { inputError = failure.message; null }
    fun pasteAndAdd() {
        try {
            val value = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            require(value.isNotBlank()) { strings.clipboardEmpty }
            require(value.length <= 100_000) { strings.batchTextTooLong }
            setText(value)
            add(value)
        } catch (failure: Exception) { inputError = failure.message ?: strings.clipboardReadFailed }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(strings.batchClearLinksTitle) },
        text = { Text(strings.batchClearLinksMessage) },
        confirmButton = { TextButton(onClick = { controller.clearInput(); confirmClear = false }) { Text(strings.batchClearInput) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(strings.cancel) } })
    if (confirmHistory) AlertDialog(onDismissRequest = { confirmHistory = false }, title = { Text(strings.batchClearHistoryTitle) },
        text = { Text(strings.batchClearHistoryMessage) },
        confirmButton = { TextButton(enabled = !running, modifier = Modifier.testTag("batch_confirm_clear_history"), onClick = { controller.clearHistory(); confirmHistory = false }) { Text(strings.batchClearHistory) } },
        dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text(strings.cancel) } })
    LazyColumn(Modifier.fillMaxSize().testTag("batch_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(strings.batchTitle, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !draft.batchManual, onClick = { drafts.update { it.copy(batchManual = false) } }, label = { Text(strings.batchSmartMode) }, enabled = ready, modifier = Modifier.testTag("batch_smart_mode"))
                FilterChip(selected = draft.batchManual, onClick = { drafts.update { it.copy(batchManual = true) } }, label = { Text(strings.batchManualMode) }, enabled = ready, modifier = Modifier.testTag("batch_manual_mode"))
            }
            OutlinedTextField(value = text, onValueChange = ::setText, enabled = ready,
                label = { Text(if (draft.batchManual) strings.batchInputManual else strings.batchInputSmart) },
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
                TextButton(onClick = ::pasteAndAdd, enabled = ready, modifier = Modifier.testTag("batch_paste")) { Text(strings.pasteClipboard) }
                FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { add() }, enabled = ready && text.isNotBlank(), modifier = Modifier.testTag("batch_add")) { Text(if (draft.batchManual) strings.batchAddLink else strings.batchRecognizeAdd) }
            }
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchChoice(downloadTypeLabel(draft.batchOptions.type), ready && !running) { close ->
                    listOf(DownloadType.VIDEO_WITH_AUDIO, DownloadType.VIDEO_ONLY, DownloadType.AUDIO_ONLY, DownloadType.GIF).forEach { value ->
                        OmniOptionMenuItem(label = downloadTypeLabel(value), selected = value == draft.batchOptions.type, onClick = { drafts.update { it.copy(batchOptions = it.batchOptions.copy(type = value)) }; close() })
                    }
                }
                if (draft.batchOptions.type == DownloadType.AUDIO_ONLY) BatchChoice(draft.batchOptions.audio.ext.uppercase(), ready && !running) { close ->
                    AudioFormat.entries.forEach { value -> OmniOptionMenuItem(label = value.ext.uppercase(), selected = value == draft.batchOptions.audio, onClick = { drafts.update { it.copy(batchOptions = it.batchOptions.copy(audio = value)) }; close() }) }
                }
            }
            Row { Checkbox(modifier = Modifier.testTag("batch_all_episodes"), checked = draft.batchOptions.allEpisodes, onCheckedChange = { value -> drafts.update { it.copy(batchOptions = it.batchOptions.copy(allEpisodes = value)) } }, enabled = ready && !running); Text(strings.batchAllEpisodes, Modifier.padding(top = 12.dp)) }
        }
        item {
            Button(shape = MaterialTheme.shapes.medium, onClick = { add()?.let { controller.start(it.joinToString("\n"), drafts.draft.value.batchOptions, preservePending = true) } }, enabled = ready && !running && (text.isNotBlank() || draft.batchLinks.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("batch_start")) { Text(strings.batchStart) }
            if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = controller::cancel) { Text(strings.batchStopAdding) } }
            if (!running && plan.items.any { !it.inputRetired && it.status != BatchStatus.QUEUED }) {
                TextButton(onClick = controller::retry) { Text(strings.batchRetryPending) }
            }
            (error ?: draftError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(strings.batchProgressSummary.format(
                plan.items.count { it.status == BatchStatus.QUEUED }, plan.items.size,
                plan.items.sumOf { it.taskIds.size }))
            TextButton(onClick = onTasks) { Text(strings.batchViewTasks) }
        }
        item {
            Row {
                Text(strings.batchFilledCount.format(draft.batchLinks.size), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { confirmClear = true }, enabled = ready && (draft.batchLinks.isNotEmpty() || text.isNotBlank())) { Text(strings.batchClearInput) }
            }
        }
        items(draft.batchLinks, key = { "draft:$it" }) { url ->
            OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(url, maxLines = 3)
                    val preparation = preparations[url]
                    val note = if (url in draft.batchTasks && preparation == null) strings.batchAddedToQueue else preparation?.note
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (preparation?.status == BatchPreparationStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(onClick = { controller.removeLink(url) }) { Text(strings.batchRemove) }
            } }
        }
        if (plan.items.isNotEmpty()) item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(strings.batchHistoryTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !running, onClick = { confirmHistory = true }, modifier = Modifier.testTag("batch_clear_history")) { Text(strings.batchClearHistory) }
            }
        }
        items(plan.items, key = { it.id }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(item.url, maxLines = 2)
                Text(item.note.ifBlank { strings.batchWaiting }, color = if (item.status == BatchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!running && item.status == BatchStatus.FAILED) TextButton(onClick = { onSingle(); viewModel.updateInputUrl(item.url); viewModel.startAnalyze() }) { Text(strings.batchSingleAnalyze) }
            } }
        }
    }
}

/** 下载类型的本地化名称：数据层的 label 仅作为中文兜底，界面统一走 AppStrings。 */
@Composable private fun downloadTypeLabel(type: DownloadType): String {
    val s = LocalAppStrings.current
    return when (type) {
        DownloadType.VIDEO_WITH_AUDIO -> s.typeVideo
        DownloadType.VIDEO_ONLY -> s.tabMute
        DownloadType.AUDIO_ONLY -> s.typeAudio
        DownloadType.GIF -> s.badgeGif
        DownloadType.COVER -> s.typeCover
        DownloadType.IMAGE -> s.imageOriginal
        DownloadType.IMAGE_ZIP -> s.imageZip
    }
}

@Composable private fun BatchChoice(label: String, enabled: Boolean, content: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { expanded = true }, enabled = enabled) { Text(label) }
        OmniOptionMenu(expanded = expanded, onDismissRequest = { expanded = false }) { content { expanded = false } }
    }
}
