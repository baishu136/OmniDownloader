package com.omni.downloader.ui.screens

import android.net.Uri
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.omni.downloader.ui.design.RetainedTabHost
import com.omni.downloader.ui.design.LocalTabActive
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.omni.downloader.ui.design.TabProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.ui.components.AddRelaySiteDialog
import com.omni.downloader.ui.components.RelaySiteCompatDialog
import com.omni.downloader.ui.design.OmniCard
import com.omni.downloader.ui.design.OmniDivider
import com.omni.downloader.ui.design.OmniIconBox
import com.omni.downloader.ui.design.OmniSectionHeader
import com.omni.downloader.ui.design.OmniSettingRow
import com.omni.downloader.ui.design.OmniStatusPill
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.LocalStatusColors
import com.omni.downloader.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    var selectedPage by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    val tabActive = LocalTabActive.current
    BackHandler(enabled = tabActive && selectedPage != SettingsPage.ROOT) { selectedPage = SettingsPage.ROOT }
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val statusColors = LocalStatusColors.current
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val isUpdatingEngine by viewModel.isUpdatingEngine.collectAsStateWithLifecycle()
    val savedProxyUrl by viewModel.proxyUrl.collectAsStateWithLifecycle()
    var proxyInput by remember(savedProxyUrl) { mutableStateOf(savedProxyUrl) }

    val relaySites by viewModel.relaySites.collectAsStateWithLifecycle()
    val hasShownRelayCompatTip by viewModel.hasShownRelayCompatTip.collectAsStateWithLifecycle()
    var showCompatDialog by remember { mutableStateOf(false) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var siteToDelete by remember { mutableStateOf<RelaySite?>(null) }

    val savedBilibiliCookie by viewModel.bilibiliCookie.collectAsStateWithLifecycle()
    var bilibiliCookieInput by remember(savedBilibiliCookie) { mutableStateOf(savedBilibiliCookie) }
    var showBilibiliLoginSheet by remember { mutableStateOf(false) }

    val savedDownloadPath by viewModel.downloadPath.collectAsStateWithLifecycle()
    var showPathDialog by remember { mutableStateOf(false) }

    // 仅在组合期做字符串拼接，不在主线程执行 File.exists()/mkdirs() 等磁盘 I/O，
    // 这是之前切换到设置页时出现掉帧的主要原因之一。
    val defaultDownloadDir = remember {
        DownloadEngine.getDownloadDir().absolutePath
    }
    val currentDownloadDir = remember(savedDownloadPath, defaultDownloadDir) {
        savedDownloadPath.ifBlank { defaultDownloadDir }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val resolvedPath = if (docId.startsWith("primary:")) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/${docId.substringAfter("primary:")}"
                } else {
                    uri.path ?: docId
                }
                if (resolvedPath.isNotBlank()) {
                    viewModel.setDownloadPath(resolvedPath)
                    Toast.makeText(context, "${strings.pathSaved}: $resolvedPath", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val engineState by remember(selectedPage) {
        flow {
            do {
                val state = DownloadEngine.isReady() to DownloadEngine.getInitError()
                emit(state)
                if (selectedPage != SettingsPage.ENGINE || state.first || state.second != null) break
                delay(250)
            } while (true)
        }
    }.collectAsStateWithLifecycle(initialValue = DownloadEngine.isReady() to DownloadEngine.getInitError())
    val isEngineReady = engineState.first
    val initError = engineState.second
    val pageListStates = List(SettingsPage.entries.size) {
        rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsToolbar(selectedPage) { selectedPage = SettingsPage.ROOT }
        RetainedTabHost(selectedPage.ordinal, SettingsPage.entries.size, Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = SettingsPage.entries[index]
            LazyColumn(
                state = pageListStates[index],
                modifier = Modifier.fillMaxSize().testTag("settings_page_" + page.name),
                contentPadding = if (page == SettingsPage.ROOT) PaddingValues(bottom = 24.dp)
                    else PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
            ) {
                if (page == SettingsPage.ROOT) {
                    items(SettingsPage.entries.filter { it != SettingsPage.ROOT }, key = { it.name }, contentType = { "category" }) { category ->
                        SettingsCategoryRow(category) { selectedPage = category }
                    }
                }
                if (page == SettingsPage.APPEARANCE) {
                    // ==================== 外观 ====================
                    item(key = "settings_appearance_card") {
                        OmniCard(contentPadding = PaddingValues(0.dp)) {
                            val languageOptions = listOf(
                                "zh-CN" to strings.languageZhCn,
                                "zh-TW" to strings.languageZhTw,
                                "en" to strings.languageEn,
                                "ja" to strings.languageJa
                            )
                            OmniSettingRow(
                                icon = Icons.Outlined.Language,
                                title = strings.languageSection
                            ) {
                                SettingDropdown(
                                    options = languageOptions,
                                    currentValue = appLanguage,
                                    fallbackLabel = strings.languageZhCn,
                                    onSelect = viewModel::setAppLanguage
                                )
                            }

                            OmniDivider(startIndent = 62)

                            val appearanceOptions = listOf(
                                "system" to strings.appearanceSystem,
                                "light" to strings.appearanceLight,
                                "dark" to strings.appearanceDark
                            )
                            OmniSettingRow(
                                icon = Icons.Outlined.Palette,
                                title = strings.appearanceSection
                            ) {
                                SettingDropdown(
                                    options = appearanceOptions,
                                    currentValue = themeMode,
                                    fallbackLabel = strings.appearanceSystem,
                                    onSelect = viewModel::setThemeMode
                                )
                            }
                        }
                    }


                }

                if (page == SettingsPage.ENGINE) {
                    // ==================== 下载引擎 ====================
                    item(key = "settings_engine_header") {
                        OmniSectionHeader(title = strings.engineSection)
                    }
                    item(key = "settings_engine_card") {
                        OmniCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OmniIconBox(icon = Icons.Outlined.SystemUpdateAlt, size = 36, corner = 10)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.engineStatusTitle,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = strings.engineHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OmniStatusPill(
                                    text = if (isEngineReady) strings.engineStatusRunning else strings.engineStatusNotReady,
                                    color = if (isEngineReady) statusColors.success else statusColors.error
                                )
                            }

                            if (!isEngineReady && initError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "反馈: $initError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColors.error
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.updateEngine(context) },
                                enabled = !isUpdatingEngine,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),

                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                if (isUpdatingEngine) {
                                    TabProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.updating, style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.SystemUpdateAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.updateEngine, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }


                }

                if (page == SettingsPage.NETWORK) {
                    // ==================== 网络代理 ====================
                    item(key = "settings_proxy_header") {
                        OmniSectionHeader(title = strings.proxySection, subtitle = strings.proxyHint)
                    }
                    item(key = "settings_proxy_card") {
                        OmniCard {
                            OutlinedTextField(
                                value = proxyInput,
                                onValueChange = { proxyInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = if (strings.proxyPlaceholder.isNotEmpty()) {
                                    { Text(strings.proxyPlaceholder, style = MaterialTheme.typography.bodyMedium) }
                                } else null,
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (savedProxyUrl.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = {
                                            proxyInput = ""
                                            viewModel.updateProxyUrl("")
                                            Toast.makeText(context, strings.proxyCleared, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),

                                    ) {
                                        Text(strings.clearProxy, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(
                                    onClick = {
                                        viewModel.updateProxyUrl(proxyInput)
                                        Toast.makeText(context, strings.proxySaved, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp),

                                ) {
                                    Text(strings.applyProxy, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }


                }

                if (page == SettingsPage.RELAY) {
                    // ==================== 中转网站 ====================
                    item(key = "settings_relay_header") {
                        OmniSectionHeader(
                            title = "${strings.relaySitesSection} (${relaySites.size})",
                            subtitle = strings.relaySitesDesc,
                            action = {
                                Button(
                                    onClick = {
                                        if (!hasShownRelayCompatTip) {
                                            showCompatDialog = true
                                        } else {
                                            showAddSiteDialog = true
                                        }
                                    },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 11.dp),

                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(strings.addRelaySiteBtn, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        )
                    }
                    if (relaySites.isEmpty()) {
                        item(key = "settings_relay_empty", contentType = "relay_empty") {
                            OmniCard {
                                Text(strings.relaySitesEmptySettings, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
                            }
                        }
                    } else {
                        items(relaySites, key = { "settings_relay_${it.id}" }, contentType = { "relay_setting" }) { site ->
                            OmniCard(contentPadding = PaddingValues(10.dp)) {
                                RelaySiteSettingsItem(site = site, onDelete = { siteToDelete = site })
                            }
                        }
                    }


                }

                if (page == SettingsPage.ACCOUNTS) {
                    // ==================== B站账号 ====================
                    item(key = "settings_bilibili_header") {
                        OmniSectionHeader(title = strings.bilibiliCookieSection)
                    }
                    item(key = "settings_bilibili_card") {
                        OmniCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OmniIconBox(icon = Icons.Outlined.Language, size = 36, corner = 10)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.bilibiliCookieHint,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (savedBilibiliCookie.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = strings.bilibiliCookieActive,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = statusColors.success
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { showBilibiliLoginSheet = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),

                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.bilibiliAutoLoginBtn, style = MaterialTheme.typography.labelLarge)
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = bilibiliCookieInput,
                                onValueChange = { bilibiliCookieInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 3,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (savedBilibiliCookie.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = {
                                            bilibiliCookieInput = ""
                                            viewModel.clearBilibiliCookie()
                                            Toast.makeText(context, strings.bilibiliCookieCleared, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),

                                    ) {
                                        Text(strings.clearBilibiliCookie, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(
                                    onClick = {
                                        viewModel.updateBilibiliCookie(bilibiliCookieInput)
                                        Toast.makeText(context, strings.bilibiliCookieSaved, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp),

                                ) {
                                    Text(strings.applyBilibiliCookie, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }


                }

                if (page == SettingsPage.DOWNLOADS) {
                    // ==================== 存储 ====================
                    item(key = "settings_storage_header") {
                        OmniSectionHeader(title = strings.storageSection)
                    }
                    item(key = "settings_storage_card") {
                        OmniCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OmniIconBox(icon = Icons.Outlined.Folder, size = 36, corner = 10)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentDownloadDir,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (savedDownloadPath.isNotBlank()) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            OmniStatusPill(text = strings.customBadge, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = strings.storageSection,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (savedDownloadPath.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.resetDownloadPath()
                                            Toast.makeText(context, strings.pathReset, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),

                                    ) {
                                        Text(strings.resetDefault, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(
                                    onClick = { showPathDialog = true },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),

                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(strings.chooseFolder, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }

                    item(key = "settings_clean_cache") {
                        OmniCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OmniIconBox(icon = Icons.Outlined.CleaningServices, size = 36, corner = 10)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.storageCleanTitle,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        context.cacheDir?.deleteRecursively()
                                        val ytDir = File(context.noBackupFilesDir, "youtubedl-android")
                                        if (ytDir.exists()) ytDir.deleteRecursively()
                                        Toast.makeText(context, strings.cleanCacheSuccess, Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "${strings.statusFailed}: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),

                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.cleanCacheAction, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }


                }

                if (page == SettingsPage.ABOUT) {
                    // ==================== 关于 ====================
                    item(key = "settings_about") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val currentVer = com.omni.downloader.OmniApp.appVersion
                            Text(
                                text = "OmniDownloader v$currentVer",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(strings.appSubtitle, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                    item(key = "settings_github") {
                        OutlinedButton(
                            onClick = {
                                val url = "https://github.com/baishu136/OmniDownloader"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                try { context.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("GitHub", url))
                                    Toast.makeText(context, url, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("settings-github")
                        ) {
                            Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("GitHub", fontWeight = FontWeight.SemiBold)
                                Text("github.com/baishu136/OmniDownloader", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                }
            }
        }
    }

    if (tabActive && showPathDialog) {
        var inputPath by remember { mutableStateOf(savedDownloadPath.ifEmpty { currentDownloadDir }) }
        val defaultDownload = remember {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloader").absolutePath
        }
        val moviesDir = remember {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OmniDownloader").absolutePath
        }
        val musicDir = remember {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "OmniDownloader").absolutePath
        }

        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text(strings.changeFolderDialogTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = strings.changeFolderDialogHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            dirPickerLauncher.launch(null)
                            showPathDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),

                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.selectViaFilePicker)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.commonFolders,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SuggestionChip(
                            onClick = { inputPath = defaultDownload },
                            label = { Text(strings.folderDefault, fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { inputPath = moviesDir },
                            label = { Text(strings.folderMovies, fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { inputPath = musicDir },
                            label = { Text(strings.folderMusic, fontSize = 11.sp) }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        label = { Text(strings.pathInputLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = inputPath.trim()
                        if (clean.isNotEmpty()) {
                            viewModel.setDownloadPath(clean)
                            Toast.makeText(context, strings.pathSaved, Toast.LENGTH_SHORT).show()
                        }
                        showPathDialog = false
                    }
                ) {
                    Text(strings.saveSettings)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPathDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (tabActive && showBilibiliLoginSheet) {
        com.omni.downloader.ui.components.BilibiliLoginSheet(
            onDismissRequest = {
                showBilibiliLoginSheet = false
            },
            onCookieCaptured = { capturedCookie ->
                showBilibiliLoginSheet = false
                viewModel.updateBilibiliCookie(capturedCookie)
                bilibiliCookieInput = capturedCookie
                Toast.makeText(context, strings.bilibiliLoginSuccess, Toast.LENGTH_LONG).show()
            }
        )
    }

    if (tabActive && showCompatDialog) {
        RelaySiteCompatDialog(
            onDismiss = { showCompatDialog = false },
            onConfirm = {
                viewModel.markRelayCompatTipShown()
                showCompatDialog = false
                showAddSiteDialog = true
            }
        )
    }

    if (tabActive && showAddSiteDialog) {
        AddRelaySiteDialog(
            onDismiss = { showAddSiteDialog = false },
            onAddSite = { site ->
                viewModel.addRelaySite(site)
                Toast.makeText(context, "已成功添加中转网站：${site.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (tabActive && siteToDelete != null) {
        val targetSite = siteToDelete!!
        AlertDialog(
            onDismissRequest = { siteToDelete = null },
            title = {
                Text(
                    text = strings.deleteRelaySiteConfirmTitle,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = String.format(strings.deleteRelaySiteConfirmMessage, targetSite.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeRelaySite(targetSite.id)
                        siteToDelete = null
                        Toast.makeText(context, "已删除：${targetSite.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),

                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { siteToDelete = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

/**
 * 轻量下拉选择器：外层只负责弹出菜单，选中项由调用方持久化。
 */
@Composable
private fun SettingDropdown(
    options: List<Pair<String, String>>,
    currentValue: String,
    fallbackLabel: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == currentValue }?.second ?: fallbackLabel

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),

        ) {
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (code == currentValue) FontWeight.Bold else FontWeight.Normal,
                            color = if (code == currentValue) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                    leadingIcon = if (code == currentValue) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * 中转站设置项：独立的轻量组件，删除操作不会触发整屏重组。
 */
@Composable
private fun RelaySiteSettingsItem(
    site: RelaySite,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val domain = remember(site.url) {
        try {
            Uri.parse(site.url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    val (brandChar, brandColor) = remember(site.name) {
        val initial = site.name.trim().take(1).uppercase(java.util.Locale.ROOT).ifBlank { "W" }
        val palette = listOf(
            Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFFF59E0B),
            Color(0xFF14B8A6), Color(0xFF3B82F6), Color(0xFF8B5CF6)
        )
        val color = palette[kotlin.math.abs(site.name.hashCode()) % palette.size]
        initial to color
    }
    val faviconUrl = remember(site.iconUrl) {
        if (site.iconUrl.isNotBlank() &&
            !site.iconUrl.contains("icon.horse", ignoreCase = true) &&
            !site.iconUrl.endsWith(".ico", ignoreCase = true)
        ) {
            site.iconUrl
        } else ""
    }

    val faviconRequest = remember(faviconUrl, context) {
        if (faviconUrl.isBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(faviconUrl)
                .size(56, 56)
                .crossfade(false)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = brandChar,
                    color = brandColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                if (faviconRequest != null) {
                    AsyncImage(
                        model = faviconRequest,
                        contentDescription = site.name,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = domain.ifBlank { site.url },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
