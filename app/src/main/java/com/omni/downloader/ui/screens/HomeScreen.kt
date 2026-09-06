package com.omni.downloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.components.FormatSelectorSheet
import com.omni.downloader.ui.components.TaskCard
import com.omni.downloader.ui.theme.*
import com.omni.downloader.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToTasks: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val inputUrl by viewModel.inputUrl.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val metadata by viewModel.metadata.collectAsState()
    val showSheet by viewModel.showFormatSheet.collectAsState()
    val errorDialogDetail by viewModel.errorDialogDetail.collectAsState()
    val selectedMultiMediaIndex by viewModel.selectedMultiMediaIndex.collectAsState()
    val selectedType by viewModel.selectedDownloadType.collectAsState()
    val selectedVideoFormat by viewModel.selectedVideoFormat.collectAsState()
    val selectedAudioFormat by viewModel.selectedAudioFormat.collectAsState()
    val tasks by viewModel.tasks.collectAsState()

    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current

    val activeTasks by remember(tasks) {
        derivedStateOf {
            tasks.filter {
                it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 顶部品牌区
        item {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "OmniDownloader",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val appVer = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.2.8"
                        } catch (e: Exception) {
                            "1.2.8"
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "v$appVer",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.appSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 平台支持标签直接铺开展示
        item {
            val supportedPlatforms = remember {
                listOf(
                    "哔哩哔哩" to BilibiliPink,
                    "抖音" to DouyinBlack,
                    "快手" to KuaishouOrange,
                    "小红书" to XiaohongshuRed,
                    "YouTube" to YouTubeRed,
                    "TikTok" to TikTokCyan,
                    "X (Twitter)" to TwitterBlue,
                    "Instagram" to InstagramPink,
                    "Facebook" to FacebookBlue,
                    "Pinterest" to PinterestRed
                )
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                supportedPlatforms.forEach { (name, color) ->
                    PlatformChip(name = name, color = color)
                }
            }
        }

        // 输入与操作面板（固定高度 minLines=3, maxLines=3，防止输入文本时页面跳动）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { viewModel.updateInputUrl(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = strings.inputPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.5.sp
                            )
                        },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Link, contentDescription = null)
                        },
                        trailingIcon = {
                            if (inputUrl.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateInputUrl("") }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = strings.clear)
                                }
                            } else {
                                IconButton(onClick = { viewModel.checkClipboardAndPaste(context, autoDownload = false) }) {
                                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = strings.paste)
                                }
                            }
                        },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            viewModel.startAnalyze()
                        })
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.checkClipboardAndPaste(context, autoDownload = true) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = strings.pasteClipboard,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.startAnalyze()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !isAnalyzing && inputUrl.isNotBlank()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.analyzing,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.startAnalyze,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 正在进行的任务提示条
        if (activeTasks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${strings.activeTasksTitle} (${activeTasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToTasks) {
                        Text(strings.viewAll)
                    }
                }
            }

            items(items = activeTasks.take(2), key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onCancel = { viewModel.cancelTask(context, task.id) },
                    onDelete = { viewModel.deleteTask(task.id, it) },
                    onRetry = { viewModel.startAnalyze(task.url) }
                )
            }
        }
    }

    // 详细错误弹窗
    if (errorDialogDetail != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissErrorDialog() },
            icon = {
                Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed)
            },
            title = {
                Text(text = strings.analyzeFailedTitle, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = errorDialogDetail ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.proxyTip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("ErrorLog", errorDialogDetail)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, strings.errorLogCopied, Toast.LENGTH_SHORT).show()
                        viewModel.dismissErrorDialog()
                    }
                ) {
                    Text(strings.copyErrorLog)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissErrorDialog()
                        onNavigateToSettings()
                    }
                ) {
                    Text(strings.setProxy)
                }
            }
        )
    }

    // 解析结果配置弹窗
    if (showSheet && metadata != null) {
        FormatSelectorSheet(
            metadata = metadata!!,
            selectedMultiMediaIndex = selectedMultiMediaIndex,
            selectedType = selectedType,
            selectedVideoFormat = selectedVideoFormat,
            selectedAudioFormat = selectedAudioFormat,
            onSelectMultiMedia = { viewModel.selectMultiMediaIndex(it) },
            onSelectType = { viewModel.selectDownloadType(it) },
            onSelectVideoFormat = { viewModel.selectVideoFormat(it) },
            onSelectAudioFormat = { viewModel.selectAudioFormat(it) },
            onConfirmDownload = { saveCover -> viewModel.startDownload(context, saveCover) },
            onDownloadAllMultiMedia = { viewModel.startDownloadAllMultiMedia(context) },
            onDownloadSelectedMultiMedia = { indices, saveCover -> viewModel.startDownloadSelectedMultiMedia(context, indices, saveCover) },
            onSaveCoverDirectly = { viewModel.downloadCoverDirectly(context) },
            onDismiss = { viewModel.dismissFormatSheet() }
        )
    }
}

@Composable
private fun PlatformChip(name: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}
