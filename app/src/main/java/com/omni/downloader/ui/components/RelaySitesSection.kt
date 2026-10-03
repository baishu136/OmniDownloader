package com.omni.downloader.ui.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite

/**
 * 独立隔离的高性能中转站列表组件：
 * 1. 状态完全内聚：解析进度与浏览器排查弹窗状态不向外冒泡，彻底杜绝全局重组；
 * 2. 局部细粒度刷新：单站点解析时其余站点 100% 智能跳过重组；
 * 3. 智能折叠机制：站点超过 3 个时支持一键平滑展开/收起，大幅降低海量 TextField 常驻带来的绘制与测量负担；
 * 4. 0 开销集成：内置自动化 SilentRelayEngine 与 RelayBrowserDialog。
 */
@Composable
fun RelaySitesSection(
    relaySites: List<RelaySite>,
    globalInputUrl: String,
    onStartDirectDownload: (directUrl: String, title: String) -> Unit,
    onAddSiteClick: () -> Unit,
    onDeleteSite: (siteId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var resolvingSiteId by remember { mutableStateOf<String?>(null) }
    var resolvingSite by remember { mutableStateOf<RelaySite?>(null) }
    var resolvingVideoUrl by remember { mutableStateOf("") }
    var resolvingStatus by remember { mutableStateOf("") }

    var manualBrowserSite by remember { mutableStateOf<RelaySite?>(null) }
    var manualBrowserUrl by remember { mutableStateOf("") }

    // 当添加较多站点时（> 3），默认折叠，避免海量输入框常驻造成滑动卡顿
    var isExpanded by remember { mutableStateOf(false) }

    val visibleSites = remember(relaySites, isExpanded, resolvingSiteId) {
        if (isExpanded || relaySites.size <= 3) {
            relaySites
        } else {
            // 如果有正在解析的站点，确保其始终在可见列表中
            val topSites = relaySites.take(3).toMutableList()
            if (resolvingSiteId != null && topSites.none { it.id == resolvingSiteId }) {
                val current = relaySites.find { it.id == resolvingSiteId }
                if (current != null) {
                    topSites[topSites.lastIndex] = current
                }
            }
            topSites
        }
    }

    val onCancelResolveAction = remember {
        {
            resolvingSiteId = null
            resolvingSite = null
            resolvingVideoUrl = ""
            resolvingStatus = ""
        }
    }

    val onStartResolveAction: (RelaySite, String) -> Unit = remember(globalInputUrl) {
        { targetSite, targetUrl ->
            val finalUrl = targetUrl.ifBlank { globalInputUrl }.trim()
            if (finalUrl.isBlank()) {
                Toast.makeText(context, "请先输入或粘贴待中转下载的视频链接", Toast.LENGTH_SHORT).show()
            } else {
                resolvingSiteId = targetSite.id
                resolvingSite = targetSite
                resolvingVideoUrl = finalUrl
                resolvingStatus = "正在连接中转站..."
            }
        }
    }

    val onOpenManualBrowserAction: (RelaySite, String) -> Unit = remember(globalInputUrl) {
        { targetSite, targetUrl ->
            manualBrowserSite = targetSite
            manualBrowserUrl = targetUrl.ifBlank { globalInputUrl }.trim()
        }
    }

    val onDeleteSiteAction: (String) -> Unit = remember(onDeleteSite) {
        { siteId ->
            if (resolvingSiteId == siteId) {
                resolvingSiteId = null
                resolvingSite = null
                resolvingVideoUrl = ""
                resolvingStatus = ""
            }
            onDeleteSite(siteId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (relaySites.isEmpty()) {
            AddRelaySiteCard(onClick = onAddSiteClick)
        } else {
            // 逐一渲染可见卡片（入参全部稳定，未激活站点 100% 智能跳过重组）
            visibleSites.forEach { site ->
                key(site.id) {
                    val isCurrentResolving = resolvingSiteId == site.id
                    val currentStatus = if (isCurrentResolving) resolvingStatus else ""

                    RelaySiteCard(
                        site = site,
                        isResolving = isCurrentResolving,
                        resolvingStatus = currentStatus,
                        onStartResolve = onStartResolveAction,
                        onCancelResolve = onCancelResolveAction,
                        onOpenManualBrowser = onOpenManualBrowserAction,
                        onDeleteSite = onDeleteSiteAction
                    )
                }
            }

            // 展开 / 收起更多中转网站按钮（当站点 > 3 时提供）
            if (relaySites.size > 3) {
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isExpanded) "收起部分中转网站" else "展开全部 ${relaySites.size} 个中转网站",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 添加更多中转网址按钮
            OutlinedButton(
                onClick = onAddSiteClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "添加更多中转网址",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // 后台静默中转自动化解析引擎（完全内聚在局部，上报进度不污染主屏）
    if (resolvingSiteId != null && resolvingSite != null) {
        SilentRelayEngine(
            site = resolvingSite!!,
            videoUrl = resolvingVideoUrl,
            onSuccess = { directUrl: String, title: String ->
                val siteName = resolvingSite?.name ?: "中转站"
                resolvingSiteId = null
                resolvingSite = null
                resolvingVideoUrl = ""
                resolvingStatus = ""
                Toast.makeText(context, "中转解析成功，已自动加入本地下载队列！", Toast.LENGTH_SHORT).show()
                onStartDirectDownload(
                    directUrl,
                    title.ifBlank { "中转下载_${siteName}" }
                )
            },
            onError = { errorMsg: String ->
                resolvingSiteId = null
                resolvingSite = null
                resolvingVideoUrl = ""
                resolvingStatus = ""
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            },
            onProgress = { statusMsg: String ->
                resolvingStatus = statusMsg
            }
        )
    }

    // 备用：用户主动点击卡片右上角“窗口排查”小图标时唤起内置浏览器
    manualBrowserSite?.let { site ->
        RelayBrowserDialog(
            site = site,
            initialVideoUrl = manualBrowserUrl,
            onDismiss = {
                manualBrowserSite = null
                manualBrowserUrl = ""
            },
            onCapturedDownload = { directUrl, title ->
                onStartDirectDownload(directUrl, title)
                manualBrowserSite = null
                manualBrowserUrl = ""
            }
        )
    }
}
