package com.omni.downloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.omni.downloader.ui.design.TabProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.ui.components.FormatSelectorSheet
import com.omni.downloader.ui.components.RelaySitesOverlays
import com.omni.downloader.ui.components.TaskCard
import com.omni.downloader.ui.components.relaySitesItems
import com.omni.downloader.ui.components.rememberRelaySitesState
import com.omni.downloader.ui.design.OmniCard
import com.omni.downloader.ui.design.OmniMotion
import com.omni.downloader.ui.design.OmniSectionHeader
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.BilibiliPink
import com.omni.downloader.ui.theme.DouyinBlack
import com.omni.downloader.ui.theme.ErrorRed
import com.omni.downloader.ui.theme.FacebookBlue
import com.omni.downloader.ui.theme.InstagramPink
import com.omni.downloader.ui.theme.KuaishouOrange
import com.omni.downloader.ui.theme.PinterestRed
import com.omni.downloader.ui.theme.TikTokCyan
import com.omni.downloader.ui.theme.TwitterBlue
import com.omni.downloader.ui.theme.XiaohongshuRed
import com.omni.downloader.ui.theme.YouTubeRed
import com.omni.downloader.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToTasks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val inputUrl by viewModel.inputUrl.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val showSheet by viewModel.showFormatSheet.collectAsStateWithLifecycle()
    val errorDialogDetail by viewModel.errorDialogDetail.collectAsStateWithLifecycle()
    val selectedMultiMediaIndex by viewModel.selectedMultiMediaIndex.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedDownloadType.collectAsStateWithLifecycle()
    val selectedVideoFormat by viewModel.selectedVideoFormat.collectAsStateWithLifecycle()
    val selectedAudioFormat by viewModel.selectedAudioFormat.collectAsStateWithLifecycle()
    val hasPromptedBilibiliLogin by viewModel.hasPromptedBilibiliLogin.collectAsStateWithLifecycle()
    val bilibiliCookie by viewModel.bilibiliCookie.collectAsStateWithLifecycle()
    val relaySites by viewModel.relaySites.collectAsStateWithLifecycle()

    var showBilibiliGuideDialog by remember { mutableStateOf(false) }
    var showBilibiliLoginSheet by remember { mutableStateOf(false) }

    // 首页常驻组合在后台时不应弹出引导弹窗，只有当前页面可见时才触发
    LaunchedEffect(hasPromptedBilibiliLogin, bilibiliCookie, isActive) {
        if (isActive && !hasPromptedBilibiliLogin && bilibiliCookie.isEmpty()) {
            kotlinx.coroutines.delay(600)
            showBilibiliGuideDialog = true
        }
    }

    val strings = LocalAppStrings.current
    val onStartDirectDownloadAction = remember(viewModel, context) {
        { directUrl: String, title: String ->
            viewModel.startDirectDownload(context = context, directUrl = directUrl, title = title)
        }
    }

    val relayState = rememberRelaySitesState()
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "home_hero", contentType = "hero") {
                HomeHero()
            }

            item(key = "home_platforms", contentType = "platforms") {
                HomePlatformChips()
            }

            item(key = "home_input_panel", contentType = "input_panel") {
                HomeInputPanel(
                    viewModel = viewModel,
                    inputUrl = inputUrl,
                    isAnalyzing = isAnalyzing,
                    onAnalyze = {
                        keyboardController?.hide()
                        viewModel.startAnalyze()
                    }
                )
            }

            relaySitesItems(
                relaySites = relaySites,
                relayState = relayState,
                globalInputUrl = inputUrl,
                onNavigateToSettings = onNavigateToSettings,
                context = context
            )

            item(key = "home_active_tasks_section", contentType = "active_tasks") {
                HomeActiveTasksSection(
                    viewModel = viewModel,
                    onNavigateToTasks = onNavigateToTasks
                )
            }
        }

        // 中转站后台静默解析与浏览器排查浮层（脱离列表测量体系，避免重组污染列表）
        RelaySitesOverlays(
            relayState = relayState,
            onStartDirectDownload = onStartDirectDownloadAction
        )
    }

    // 详细错误弹窗
    if (isActive && errorDialogDetail != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissErrorDialog() },
            icon = {
                Icon(imageVector = Icons.Outlined.Error, contentDescription = null, tint = ErrorRed)
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
    if (isActive && showSheet && metadata != null) {
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

    // 首次启动 B站画质解锁引导弹窗
    if (isActive && showBilibiliGuideDialog) {
        com.omni.downloader.ui.components.BilibiliGuideDialog(
            onLoginClick = {
                showBilibiliGuideDialog = false
                viewModel.markBilibiliLoginPrompted()
                showBilibiliLoginSheet = true
            },
            onDismiss = {
                showBilibiliGuideDialog = false
                viewModel.markBilibiliLoginPrompted()
            }
        )
    }

    // 内置网页安全登录弹窗（自动截获 Cookie）
    if (isActive && showBilibiliLoginSheet) {
        com.omni.downloader.ui.components.BilibiliLoginSheet(
            onDismissRequest = {
                showBilibiliLoginSheet = false
            },
            onCookieCaptured = { capturedCookie ->
                showBilibiliLoginSheet = false
                viewModel.updateBilibiliCookie(capturedCookie)
                android.widget.Toast.makeText(context, strings.bilibiliLoginSuccess, android.widget.Toast.LENGTH_LONG).show()
            }
        )
    }
}

/**
 * 顶部品牌区：图标底板使用主色渐变，标题与版本号同一基线，
 * 标题仅保留应用名与版本，平台范围由下方标签说明。
 */
@Composable
private fun HomeHero() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "OmniDownloader",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val appVer = com.omni.downloader.OmniApp.appVersion
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
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

        }
    }
}

/**
 * 链接输入面板：将输入框与两个主操作收进同一张卡片，
 * 视觉上形成「一屏内完成主流程」的聚焦区。
 */
@Composable
private fun HomeInputPanel(
    viewModel: MainViewModel,
    inputUrl: String,
    isAnalyzing: Boolean,
    onAnalyze: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current

    OmniCard(contentPadding = PaddingValues(14.dp)) {
        TextField(
            value = inputUrl,
            onValueChange = { viewModel.updateInputUrl(it) },
            modifier = Modifier.fillMaxWidth().testTag("home_link_input"),
            placeholder = {
                Text(
                    text = strings.inputPlaceholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (inputUrl.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateInputUrl("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = strings.clear,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                } else {
                    IconButton(onClick = { viewModel.checkClipboardAndPaste(context, autoDownload = false) }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = strings.paste,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            },
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (inputUrl.isNotBlank() && !isAnalyzing) onAnalyze()
            })
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = { viewModel.checkClipboardAndPaste(context, autoDownload = true) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),

                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = strings.pasteClipboard,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onAnalyze,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),

                contentPadding = PaddingValues(horizontal = 8.dp),
                enabled = !isAnalyzing && inputUrl.isNotBlank()
            ) {
                // 状态切换用短促淡入淡出，避免文字与图标瞬间跳变
                Crossfade(
                    targetState = isAnalyzing,
                    animationSpec = tween(150, easing = OmniMotion.Standard),
                    label = "analyzeButtonState"
                ) { analyzing ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (analyzing) {
                            TabProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.analyzing,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = strings.startAnalyze,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 支持平台：以低饱和圆点 + 标签铺开，避免使用彩色整块背景造成视觉噪音。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePlatformChips() {
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
    FlowRow(
        modifier = Modifier
            .testTag("home_platforms")
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        supportedPlatforms.forEach { (name, color) ->
            PlatformChip(
                name = name,
                color = if (name == "抖音") MaterialTheme.colorScheme.onSurfaceVariant else color
            )
        }
    }
}

@Composable
private fun PlatformChip(name: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 局部隔离的正在进行任务展示区：
 * 仅该独立 Composable 订阅 activeTasks 状态，下载进度高频刷新时完全不触发主屏输入框、中转卡片与平台标签重组。
 */
@Composable
private fun HomeActiveTasksSection(
    viewModel: MainViewModel,
    onNavigateToTasks: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = activeTasks.isNotEmpty(),
        enter = fadeIn(tween(OmniMotion.NORMAL)) + expandVertically(
            animationSpec = tween(OmniMotion.MEDIUM, easing = OmniMotion.Emphasized)
        ),
        exit = fadeOut(tween(OmniMotion.FAST)) + shrinkVertically(
            animationSpec = tween(OmniMotion.FAST)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OmniSectionHeader(
                title = "${strings.activeTasksTitle} (${activeTasks.size})",
                action = {
                    TextButton(onClick = onNavigateToTasks) {
                        Text(
                            text = strings.viewAll,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )

            val onCancelHomeTask = remember(viewModel, context) {
                { id: String -> viewModel.cancelTask(context, id) }
            }
            val onDeleteHomeTask = remember(viewModel) {
                { id: String, deleteFile: Boolean -> viewModel.deleteTask(id, deleteFile) }
            }
            val onRetryHomeTask = remember(viewModel) {
                { url: String -> viewModel.startAnalyze(url) }
            }

            activeTasks.take(2).forEach { task ->
                key(task.id) {
                    TaskCard(
                        task = task,
                        onCancel = onCancelHomeTask,
                        onDelete = onDeleteHomeTask,
                        onRetry = onRetryHomeTask,
                        progressFlowProvider = viewModel::getTaskProgress
                    )
                }
            }
        }
    }
}
