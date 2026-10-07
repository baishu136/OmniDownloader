package com.omni.downloader.ui.screens

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.omni.downloader.ui.theme.ErrorRed
import com.omni.downloader.ui.theme.SuccessGreen
import com.omni.downloader.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val isUpdatingEngine by viewModel.isUpdatingEngine.collectAsState()
    val savedProxyUrl by viewModel.proxyUrl.collectAsState()
    var proxyInput by remember(savedProxyUrl) { mutableStateOf(savedProxyUrl) }

    val relaySites by viewModel.relaySites.collectAsState()
    val hasShownRelayCompatTip by viewModel.hasShownRelayCompatTip.collectAsState()
    var showCompatDialog by remember { mutableStateOf(false) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var siteToDelete by remember { mutableStateOf<RelaySite?>(null) }

    val savedBilibiliCookie by viewModel.bilibiliCookie.collectAsState()
    var bilibiliCookieInput by remember(savedBilibiliCookie) { mutableStateOf(savedBilibiliCookie) }
    var showBilibiliLoginSheet by remember { mutableStateOf(false) }

    val savedDownloadPath by viewModel.downloadPath.collectAsState()
    var showPathDialog by remember { mutableStateOf(false) }

    val currentDownloadDir = remember(savedDownloadPath) {
        DownloadEngine.getDownloadDir(context).absolutePath
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

    val isEngineReady = DownloadEngine.isReady()
    val initError = DownloadEngine.getInitError()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // 语言设置 (Language) - 紧凑横向排布，不显示跟随系统
        item {
            var expanded by remember { mutableStateOf(false) }
            val languageOptions = listOf(
                "zh-CN" to strings.languageZhCn,
                "zh-TW" to strings.languageZhTw,
                "en" to strings.languageEn,
                "ja" to strings.languageJa
            )
            val currentDisplay = languageOptions.firstOrNull { it.first == appLanguage }?.second
                ?: strings.languageZhCn

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.languageSection,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = currentDisplay, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            languageOptions.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            fontWeight = if (code == appLanguage) FontWeight.Bold else FontWeight.Normal,
                                            color = if (code == appLanguage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.setAppLanguage(code)
                                        expanded = false
                                    },
                                    leadingIcon = if (code == appLanguage) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
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
            }
        }

        // 核心引擎运行状态
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.engineStatusTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (isEngineReady) SuccessGreen.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (isEngineReady) strings.engineStatusRunning else strings.engineStatusNotReady,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isEngineReady) SuccessGreen else ErrorRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!isEngineReady && initError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "反馈: $initError",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.updateEngine(context) },
                        enabled = !isUpdatingEngine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isUpdatingEngine) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.updating, fontSize = 12.5.sp)
                        } else {
                            Icon(imageVector = Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.updateEngine, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }

        // 网络代理配置 (Proxy)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.proxySection,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.proxyHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = if (strings.proxyPlaceholder.isNotEmpty()) {
                            { Text(strings.proxyPlaceholder, fontSize = 13.sp) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(strings.clearProxy, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Button(
                            onClick = {
                                viewModel.updateProxyUrl(proxyInput)
                                Toast.makeText(context, strings.proxySaved, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(strings.applyProxy, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 第三方中转网站配置 (Relay Sites)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.relaySitesSection,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "${relaySites.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (!hasShownRelayCompatTip) {
                                    showCompatDialog = true
                                } else {
                                    showAddSiteDialog = true
                                }
                            },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.addRelaySiteBtn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.relaySitesDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (relaySites.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strings.relaySitesEmptySettings,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            relaySites.forEach { site ->
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
                                val faviconUrl = remember(site.iconUrl, domain) {
                                    when {
                                        site.iconUrl.isNotBlank() && !site.iconUrl.endsWith(".ico", ignoreCase = true) -> site.iconUrl
                                        domain.isNotBlank() -> "https://icon.horse/icon/$domain"
                                        site.iconUrl.isNotBlank() -> site.iconUrl
                                        else -> ""
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
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
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(brandColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = brandChar,
                                                color = brandColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            if (faviconUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(faviconUrl)
                                                        .size(56, 56)
                                                        .crossfade(false)
                                                        .build(),
                                                    contentDescription = site.name,
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clip(RoundedCornerShape(3.dp))
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
                                            onClick = { siteToDelete = site },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "删除",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // B站登录凭证配置 (Cookie / SESSDATA)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.bilibiliCookieSection,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (savedBilibiliCookie.isNotEmpty()) {
                            Surface(
                                color = SuccessGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = strings.bilibiliCookieActive,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showBilibiliLoginSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.bilibiliAutoLoginBtn, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bilibiliCookieInput,
                        onValueChange = { bilibiliCookieInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 3,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(strings.clearBilibiliCookie, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Button(
                            onClick = {
                                viewModel.updateBilibiliCookie(bilibiliCookieInput)
                                Toast.makeText(context, strings.bilibiliCookieSaved, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(strings.applyBilibiliCookie, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 存储路径管理
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.storageSection,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (savedDownloadPath.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = strings.customBadge,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentDownloadDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(strings.resetDefault, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Button(
                            onClick = { showPathDialog = true },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.chooseFolder, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 存储空间管理与缓存深度清理
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = strings.storageCleanTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.cacheDir?.deleteRecursively()
                                val ytDir = java.io.File(context.noBackupFilesDir, "youtubedl-android")
                                if (ytDir.exists()) ytDir.deleteRecursively()
                                Toast.makeText(context, strings.cleanCacheSuccess, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "${strings.statusFailed}: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.cleanCacheAction, fontSize = 12.5.sp)
                    }
                }
            }
        }

        // 关于与版本
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentVer = remember {
                    try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.4.7"
                    } catch (e: Exception) {
                        "1.4.7"
                    }
                }
                Text(
                    text = "OmniDownloader v$currentVer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showPathDialog) {
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
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.selectViaFilePicker)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = strings.commonFolders, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        shape = RoundedCornerShape(8.dp)
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

    if (showBilibiliLoginSheet) {
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

    if (showCompatDialog) {
        RelaySiteCompatDialog(
            onDismiss = { showCompatDialog = false },
            onConfirm = {
                viewModel.markRelayCompatTipShown()
                showCompatDialog = false
                showAddSiteDialog = true
            }
        )
    }

    if (showAddSiteDialog) {
        AddRelaySiteDialog(
            onDismiss = { showAddSiteDialog = false },
            onAddSite = { site ->
                viewModel.addRelaySite(site)
                Toast.makeText(context, "已成功添加中转网站：${site.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (siteToDelete != null) {
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
                    shape = RoundedCornerShape(8.dp)
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
