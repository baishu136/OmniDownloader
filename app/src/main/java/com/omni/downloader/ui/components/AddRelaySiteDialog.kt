package com.omni.downloader.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.engine.WebTitleFetcher
import com.omni.downloader.ui.localization.LocalAppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 添加备用中转解析网站弹窗：
 * 支持输入网址后自动防抖检索网页标题并自动填充名称，亦支持手动点击刷新抓取。
 */
@Composable
fun AddRelaySiteDialog(
    onDismiss: () -> Unit,
    onAddSite: (RelaySite) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var isFetchingTitle by remember { mutableStateOf(false) }
    var fetchFailed by remember { mutableStateOf(false) }
    var userModifiedNameManually by remember { mutableStateOf(false) }
    var lastAutoFetchedTitle by remember { mutableStateOf("") }

    // 独立拉取标题方法
    fun triggerFetchTitle(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.length < 4 || (!cleanUrl.contains(".") && !cleanUrl.startsWith("http", ignoreCase = true))) {
            return
        }
        coroutineScope.launch {
            isFetchingTitle = true
            fetchFailed = false
            try {
                val fetchedTitle = WebTitleFetcher.fetchTitle(cleanUrl)
                if (!fetchedTitle.isNullOrBlank()) {
                    // 若用户尚未手动自定义输入，或当前名称与上次自动获取的一致，则自动回填
                    if (!userModifiedNameManually || nameInput.isBlank() || nameInput == lastAutoFetchedTitle) {
                        nameInput = fetchedTitle
                        lastAutoFetchedTitle = fetchedTitle
                        userModifiedNameManually = false
                    }
                } else {
                    fetchFailed = true
                }
            } catch (_: Exception) {
                fetchFailed = true
            } finally {
                isFetchingTitle = false
            }
        }
    }

    // 监听 URL 变化自动防抖获取标题
    LaunchedEffect(urlInput) {
        val clean = urlInput.trim()
        if (clean.length >= 4 && (clean.contains(".") || clean.startsWith("http", ignoreCase = true))) {
            delay(700) // 700ms 防抖
            triggerFetchTitle(clean)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.addRelaySiteBtn,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "输入网址后将自动检索网站名称，也可手动修改：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. 网址输入框（置顶）
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        fetchFailed = false
                    },
                    label = { Text("网站完整网址") },
                    placeholder = { Text("https://...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Link, contentDescription = null)
                    },
                    trailingIcon = {
                        when {
                            isFetchingTitle -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            urlInput.isNotBlank() -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { triggerFetchTitle(urlInput) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "重新检索名称",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            urlInput = ""
                                            if (!userModifiedNameManually) {
                                                nameInput = ""
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "清除网址",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                            else -> {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrBlank()) {
                                    IconButton(
                                        onClick = {
                                            urlInput = clipText.trim()
                                            triggerFetchTitle(urlInput)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "粘贴剪贴板",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // 2. 网站名称输入框（第二位）
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it
                        userModifiedNameManually = it.isNotBlank()
                    },
                    label = { Text("网站名称") },
                    placeholder = { Text("例如：备用解析工具") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Language, contentDescription = null)
                    },
                    trailingIcon = {
                        if (nameInput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    nameInput = ""
                                    userModifiedNameManually = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清空名称",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // 状态辅助提示
                if (isFetchingTitle) {
                    Text(
                        text = strings.fetchingTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else if (fetchFailed && nameInput.isBlank()) {
                    Text(
                        text = strings.fetchTitleFailed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = nameInput.trim()
                    var cleanUrl = urlInput.trim()

                    if (cleanName.isBlank() || cleanUrl.isBlank()) {
                        Toast.makeText(context, "请完整填写网站名称与网址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                        !cleanUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        cleanUrl = "https://$cleanUrl"
                    }

                    val site = RelaySite(
                        id = UUID.randomUUID().toString().replace("-", "").take(8),
                        name = cleanName,
                        url = cleanUrl,
                        iconUrl = "" // 默认采用高效本地品牌首字母矢量徽章，避免境外 icon.horse 服务超时挂起网络线程
                    )
                    onAddSite(site)
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("确认添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
