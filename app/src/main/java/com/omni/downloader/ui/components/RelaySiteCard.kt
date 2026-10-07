package com.omni.downloader.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.omni.downloader.data.model.RelaySite

/**
 * 对应用户手绘设计的已添加中转网站卡片组件 (Image 1)
 * 支持“全流程软件原生页处理”：
 * - 纯就地静默解析，不弹全屏网页
 * - 实时显示后台进度与取消交互
 * - 保留备用网页版排查入口
 */
@Composable
fun RelaySiteCard(
    site: RelaySite,
    isResolving: Boolean = false,
    resolvingStatus: String = "",
    onStartResolve: (site: RelaySite, videoUrl: String) -> Unit,
    onCancelResolve: () -> Unit = {},
    onOpenManualBrowser: (site: RelaySite, videoUrl: String) -> Unit = { _, _ -> },
    onDeleteSite: ((siteId: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputUrl by remember { mutableStateOf("") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (isResolving) 1.5.dp else 1.dp,
            if (isResolving) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 第一行：[网站图标] + [网址名] + [手动浏览备用入口] + [删除按钮]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 网站图标 (精美品牌徽标 + 免代理高可用 PNG + 动态首字母兜底，杜绝灰块)
                val domain = remember(site.url) {
                    try {
                        Uri.parse(site.url).host ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                }
                val (brandChar, brandColor) = remember(site.name, site.url) {
                    val lowerName = site.name.lowercase(java.util.Locale.ROOT)
                    val lowerUrl = site.url.lowercase(java.util.Locale.ROOT)
                    when {
                        lowerName.contains("bili") || lowerUrl.contains("bilibili") -> "B" to Color(0xFFFB7299)
                        lowerName.contains("twitter") || lowerName.contains("x2") || lowerUrl.contains("twitter") -> "X" to Color(0xFF1D9BF0)
                        lowerName.contains("green") || lowerUrl.contains("greenvideo") -> "G" to Color(0xFF10B981)
                        lowerName.contains("snap") || lowerUrl.contains("snapany") -> "S" to Color(0xFF8B5CF6)
                        lowerName.contains("tiktok") || lowerUrl.contains("tiktok") -> "T" to Color(0xFF06B6D4)
                        lowerName.contains("youtube") || lowerUrl.contains("youtube") -> "Y" to Color(0xFFEF4444)
                        else -> {
                            val initial = site.name.trim().take(1).uppercase(java.util.Locale.ROOT).ifBlank { "W" }
                            val palette = listOf(
                                Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFFF59E0B),
                                Color(0xFF14B8A6), Color(0xFF3B82F6), Color(0xFF8B5CF6)
                            )
                            val color = palette[kotlin.math.abs(site.name.hashCode()) % palette.size]
                            initial to color
                        }
                    }
                }

                val faviconUrl = remember(site.iconUrl, domain) {
                    when {
                        site.iconUrl.isNotBlank() && !site.iconUrl.endsWith(".ico", ignoreCase = true) -> site.iconUrl
                        domain.isNotBlank() -> "https://icon.horse/icon/$domain"
                        site.iconUrl.isNotBlank() -> site.iconUrl
                        else -> ""
                    }
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brandColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    // 底层：常驻品牌首字母高质徽标（0开销、0灰块、0闪烁）
                    Text(
                        text = brandChar,
                        color = brandColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                    val faviconRequest = remember(faviconUrl, context) {
                        if (faviconUrl.isBlank()) null
                        else {
                            ImageRequest.Builder(context)
                                .data(faviconUrl)
                                .size(66, 66)
                                .crossfade(false)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .build()
                        }
                    }

                    // 顶层：普通轻量 AsyncImage（异步淡入覆盖，彻底移除 SubcomposeAsyncImage 导致的多次延迟子组合测量）
                    if (faviconRequest != null) {
                        AsyncImage(
                            model = faviconRequest,
                            contentDescription = site.name,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 网址名
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 备用：在内置窗口排查 (轻量小图标)
                IconButton(
                    onClick = { onOpenManualBrowser(site, inputUrl) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = "窗口排查",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 删除按钮（仅在提供回调时展示）
                if (onDeleteSite != null) {
                    IconButton(
                        onClick = { onDeleteSite(site.id) },
                        modifier = Modifier.size(28.dp),
                        enabled = !isResolving
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第二行：高性能轻量输入框 + 内置[粘贴剪切板]图标 + [⚡ 解析] 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    enabled = !isResolving,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboardController?.hide()
                        if (!isResolving) {
                            onStartResolve(site, inputUrl)
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (inputUrl.isEmpty()) {
                                    Text(
                                        text = "输入或粘贴欲中转下载的视频链接...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                            if (inputUrl.isNotBlank() && !isResolving) {
                                IconButton(
                                    onClick = { inputUrl = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (!isResolving) {
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = cm.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                                            if (text.isNotBlank()) {
                                                inputUrl = text
                                                Toast.makeText(context, "已粘贴剪贴板链接", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "粘贴剪切板",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 原生解析操作按钮
                Button(
                    onClick = {
                        keyboardController?.hide()
                        if (isResolving) {
                            onCancelResolve()
                        } else {
                            onStartResolve(site, inputUrl)
                        }
                    },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = if (isResolving) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                             else ButtonDefaults.buttonColors()
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "取消", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "解析", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 第三行：全流程软件页解析动态进度条 (仅在解析中时平滑展开)
            AnimatedVisibility(
                visible = isResolving,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = resolvingStatus.ifBlank { "正在后台静默中转提取直链..." },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 对应用户手绘设计的【添加网址】卡片组件 (Image 2)
 */
@Composable
fun AddRelaySiteCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加网址",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "添加网址",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
