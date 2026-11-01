package com.omni.downloader.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.omni.downloader.ui.design.TabProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite
import java.util.Locale

/**
 * 中转站卡片：
 * 保持「就地直接解析」的核心交互，视觉上收敛为一行图标 + 名称 + 两个操作，
 * 解析中的状态条以展开动画呈现，不改变卡片静态部分的测量。
 */
@Composable
fun RelaySiteCard(
    site: RelaySite,
    isResolving: Boolean = false,
    resolvingStatus: String = "",
    onStartResolve: (site: RelaySite) -> Unit,
    onCancelResolve: () -> Unit = {},
    onOpenManualBrowser: (site: RelaySite) -> Unit = {},
    onDeleteSite: ((siteId: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val domain = remember(site.url) {
        try {
            Uri.parse(site.url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    val (brandChar, brandColor) = remember(site.name, site.url) {
        val lowerName = site.name.lowercase(Locale.ROOT)
        val lowerUrl = site.url.lowercase(Locale.ROOT)
        when {
            lowerName.contains("bili") || lowerUrl.contains("bilibili") -> "B" to Color(0xFFFB7299)
            lowerName.contains("twitter") || lowerName.contains("x2") || lowerUrl.contains("twitter") -> "X" to Color(0xFF1D9BF0)
            lowerName.contains("green") || lowerUrl.contains("greenvideo") -> "G" to Color(0xFF10B981)
            lowerName.contains("snap") || lowerUrl.contains("snapany") -> "S" to Color(0xFF8B5CF6)
            lowerName.contains("tiktok") || lowerUrl.contains("tiktok") -> "T" to Color(0xFF06B6D4)
            lowerName.contains("youtube") || lowerUrl.contains("youtube") -> "Y" to Color(0xFFEF4444)
            else -> {
                val initial = site.name.trim().take(1).uppercase(Locale.ROOT).ifBlank { "W" }
                val palette = listOf(
                    Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFFF59E0B),
                    Color(0xFF14B8A6), Color(0xFF3B82F6), Color(0xFF8B5CF6)
                )
                val color = palette[kotlin.math.abs(site.name.hashCode()) % palette.size]
                initial to color
            }
        }
    }

    val borderColor by animateColorAsState(
        targetValue = if (isResolving) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(220),
        label = "relayBorder"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                if (isResolving) onCancelResolve() else onStartResolve(site)
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isResolving) 1.4.dp else 1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 品牌徽标：纯矢量首字母，零网络请求
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brandColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = brandChar,
                        color = brandColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = site.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = domain.ifBlank { site.url },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (isResolving) onCancelResolve() else onStartResolve(site)
                    },

                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp),
                    colors = if (isResolving) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                ) {
                    if (isResolving) {
                        TabProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(text = "取消", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "直接解析",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                IconButton(
                    onClick = { onOpenManualBrowser(site) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = "窗口排查",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (onDeleteSite != null) {
                    IconButton(
                        onClick = { onDeleteSite(site.id) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 解析中动态状态条（平滑展开）
            AnimatedVisibility(
                visible = isResolving,
                enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(140))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TabProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = resolvingStatus.ifBlank { "正在后台自动提取视频直链并加入下载..." },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
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
