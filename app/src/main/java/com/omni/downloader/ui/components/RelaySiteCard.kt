package com.omni.downloader.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
 * 极致轻量化中转站捷径卡片：
 * 剥离了沉重的内嵌 BasicTextField 与 IME 状态机，
 * 仅保留纯粹高效的展示与点击交互，使 LazyColumn 滑动测量耗时降至极限，彻底消解掉帧卡顿。
 */
@Composable
fun RelaySiteCard(
    site: RelaySite,
    onOpenSite: (site: RelaySite) -> Unit,
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpenSite(site) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 网站品牌徽标（常驻纯矢量首字母，零网络开销、零延迟、零灰块）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brandColor.copy(alpha = 0.15f)),
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

            // 网站名称与域名展示
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = domain.ifBlank { site.url },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 智能嗅探动作按钮
            FilledTonalButton(
                onClick = { onOpenSite(site) },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "嗅探解析",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 删除按钮（仅在设置页等提供回调时展示）
            if (onDeleteSite != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onDeleteSite(site.id) },
                    modifier = Modifier.size(30.dp)
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
    }
}
