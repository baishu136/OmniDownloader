package com.omni.downloader.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.ui.localization.LocalAppStrings

/**
 * 中转站全局操作与状态持有者：
 * 隔离解析中状态与浏览器弹窗状态，实现零冗余重组。
 */
@Stable
class RelaySitesState {
    var resolvingSiteId by mutableStateOf<String?>(null)
    var resolvingSite by mutableStateOf<RelaySite?>(null)
    var resolvingVideoUrl by mutableStateOf("")
    var resolvingStatus by mutableStateOf("")

    var manualBrowserSite by mutableStateOf<RelaySite?>(null)
    var manualBrowserUrl by mutableStateOf("")

    var isExpanded by mutableStateOf(false)

    fun cancelResolve() {
        resolvingSiteId = null
        resolvingSite = null
        resolvingVideoUrl = ""
        resolvingStatus = ""
    }

    fun startResolve(site: RelaySite, url: String) {
        resolvingSiteId = site.id
        resolvingSite = site
        resolvingVideoUrl = url
        resolvingStatus = "正在连接中转站..."
    }

    fun updateResolvingStatus(status: String) {
        resolvingStatus = status
    }

    fun openManualBrowser(site: RelaySite, url: String) {
        manualBrowserSite = site
        manualBrowserUrl = url
    }

    fun dismissManualBrowser() {
        manualBrowserSite = null
        manualBrowserUrl = ""
    }
}

@Composable
fun rememberRelaySitesState(): RelaySitesState = remember { RelaySitesState() }

/**
 * 顶级 LazyColumn 平铺扩展函数：
 * 将中转站卡片直接作为 LazyColumn 的子项进行惰性渲染，
 * 每次仅测量视口内单张卡片（~2-3ms），彻底消除了嵌套 Column 在单帧内暴击测量多张卡片的掉帧问题！
 */
fun LazyListScope.relaySitesItems(
    relaySites: List<RelaySite>,
    relayState: RelaySitesState,
    globalInputUrl: String,
    onNavigateToSettings: (() -> Unit)? = null,
    onDeleteSite: ((siteId: String) -> Unit)? = null,
    context: Context
) {
    if (relaySites.isEmpty()) {
        // 主页中无中转站时不占位展示空卡片
        return
    }

    val visibleSites = if (relayState.isExpanded || relaySites.size <= 3) {
        relaySites
    } else {
        val topSites = relaySites.take(3).toMutableList()
        val currentResolvingId = relayState.resolvingSiteId
        if (currentResolvingId != null && topSites.none { it.id == currentResolvingId }) {
            val current = relaySites.find { it.id == currentResolvingId }
            if (current != null) {
                topSites[topSites.lastIndex] = current
            }
        }
        topSites
    }

    items(
        items = visibleSites,
        key = { "relay_site_${it.id}" },
        contentType = { "relay_card" }
    ) { site ->
        val isCurrentResolving = relayState.resolvingSiteId == site.id
        val currentStatus = if (isCurrentResolving) relayState.resolvingStatus else ""

        val onStartResolveCard = remember(site, globalInputUrl, context) {
            { targetSite: RelaySite, targetUrl: String ->
                val finalUrl = targetUrl.ifBlank { globalInputUrl }.trim()
                if (finalUrl.isBlank()) {
                    Toast.makeText(context, "请先输入或粘贴待中转下载的视频链接", Toast.LENGTH_SHORT).show()
                } else {
                    relayState.startResolve(targetSite, finalUrl)
                }
            }
        }
        val onCancelResolveCard = remember(relayState) {
            { relayState.cancelResolve() }
        }
        val onOpenManualBrowserCard = remember(relayState, globalInputUrl) {
            { targetSite: RelaySite, targetUrl: String ->
                relayState.openManualBrowser(targetSite, targetUrl.ifBlank { globalInputUrl }.trim())
            }
        }
        val onDeleteSiteCard = remember(site.id, relayState, onDeleteSite) {
            if (onDeleteSite != null) {
                { siteId: String ->
                    if (relayState.resolvingSiteId == siteId) {
                        relayState.cancelResolve()
                    }
                    onDeleteSite(siteId)
                }
            } else null
        }

        RelaySiteCard(
            site = site,
            isResolving = isCurrentResolving,
            resolvingStatus = currentStatus,
            onStartResolve = onStartResolveCard,
            onCancelResolve = onCancelResolveCard,
            onOpenManualBrowser = onOpenManualBrowserCard,
            onDeleteSite = onDeleteSiteCard
        )
    }

    if (relaySites.size > 3) {
        item(key = "relay_expand_button", contentType = "relay_action_button") {
            TextButton(
                onClick = { relayState.isExpanded = !relayState.isExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = if (relayState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (relayState.isExpanded) "收起部分中转网站" else "展开全部 ${relaySites.size} 个中转网站",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 中转站的后台静默解析引擎与排查弹窗（挂载在屏幕外层，不参与 LazyColumn 的任何滑动测量）
 */
@Composable
fun RelaySitesOverlays(
    relayState: RelaySitesState,
    onStartDirectDownload: (directUrl: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val resolvingSite = relayState.resolvingSite
    val resolvingVideoUrl = relayState.resolvingVideoUrl

    if (resolvingSite != null && resolvingVideoUrl.isNotBlank()) {
        SilentRelayEngine(
            site = resolvingSite,
            videoUrl = resolvingVideoUrl,
            onSuccess = { directUrl: String, title: String ->
                val siteName = resolvingSite.name
                relayState.cancelResolve()
                Toast.makeText(context, "中转解析成功，已自动加入本地下载队列！", Toast.LENGTH_SHORT).show()
                onStartDirectDownload(
                    directUrl,
                    title.ifBlank { "中转下载_${siteName}" }
                )
            },
            onError = { errorMsg: String ->
                relayState.cancelResolve()
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            },
            onProgress = { statusMsg: String ->
                relayState.updateResolvingStatus(statusMsg)
            }
        )
    }

    val manualBrowserSite = relayState.manualBrowserSite
    if (manualBrowserSite != null) {
        RelayBrowserDialog(
            site = manualBrowserSite,
            initialVideoUrl = relayState.manualBrowserUrl,
            onDismiss = { relayState.dismissManualBrowser() },
            onCapturedDownload = { directUrl, title ->
                onStartDirectDownload(directUrl, title)
                relayState.dismissManualBrowser()
            }
        )
    }
}

/**
 * 兼容旧接口的 Composable
 */
@Composable
fun RelaySitesSection(
    relaySites: List<RelaySite>,
    globalInputUrl: String,
    onStartDirectDownload: (directUrl: String, title: String) -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
    onDeleteSite: ((siteId: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (relaySites.isEmpty()) {
        // 主页中无中转站时不占位展示
        return
    }

    val context = LocalContext.current
    val relayState = rememberRelaySitesState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val visibleSites = if (relayState.isExpanded || relaySites.size <= 3) {
            relaySites
        } else {
            val topSites = relaySites.take(3).toMutableList()
            val currentResolvingId = relayState.resolvingSiteId
            if (currentResolvingId != null && topSites.none { it.id == currentResolvingId }) {
                val current = relaySites.find { it.id == currentResolvingId }
                if (current != null) {
                    topSites[topSites.lastIndex] = current
                }
            }
            topSites
        }

        visibleSites.forEach { site ->
            key(site.id) {
                val isCurrentResolving = relayState.resolvingSiteId == site.id
                val currentStatus = if (isCurrentResolving) relayState.resolvingStatus else ""

                RelaySiteCard(
                    site = site,
                    isResolving = isCurrentResolving,
                    resolvingStatus = currentStatus,
                    onStartResolve = { targetSite, targetUrl ->
                        val finalUrl = targetUrl.ifBlank { globalInputUrl }.trim()
                        if (finalUrl.isBlank()) {
                            Toast.makeText(context, "请先输入或粘贴待中转下载的视频链接", Toast.LENGTH_SHORT).show()
                        } else {
                            relayState.startResolve(targetSite, finalUrl)
                        }
                    },
                    onCancelResolve = { relayState.cancelResolve() },
                    onOpenManualBrowser = { targetSite, targetUrl ->
                        relayState.openManualBrowser(targetSite, targetUrl.ifBlank { globalInputUrl }.trim())
                    },
                    onDeleteSite = if (onDeleteSite != null) {
                        { siteId ->
                            if (relayState.resolvingSiteId == siteId) {
                                relayState.cancelResolve()
                            }
                            onDeleteSite(siteId)
                        }
                    } else null
                )
            }
        }

        if (relaySites.size > 3) {
            TextButton(
                onClick = { relayState.isExpanded = !relayState.isExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = if (relayState.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (relayState.isExpanded) "收起部分中转网站" else "展开全部 ${relaySites.size} 个中转网站",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    RelaySitesOverlays(
        relayState = relayState,
        onStartDirectDownload = onStartDirectDownload
    )
}
