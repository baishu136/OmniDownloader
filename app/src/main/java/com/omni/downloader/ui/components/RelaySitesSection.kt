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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.downloader.data.model.RelaySite

/**
 * 中转站全局操作与状态持有者：
 * 隔离就地解析状态与浏览器弹窗状态，实现零冗余重组。
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

    fun startResolve(site: RelaySite, url: String) {
        resolvingSiteId = site.id
        resolvingSite = site
        resolvingVideoUrl = url
        resolvingStatus = "正在连接中转站..."
    }

    fun updateResolvingStatus(status: String) {
        resolvingStatus = status
    }

    fun cancelResolve() {
        resolvingSiteId = null
        resolvingSite = null
        resolvingVideoUrl = ""
        resolvingStatus = ""
    }

    fun openManualBrowser(site: RelaySite, url: String = "") {
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
 * 将中转站轻量卡片直接作为 LazyColumn 的子项进行惰性渲染，
 * 每次仅测量视口内单张卡片（~0.2ms），彻底消除了旧版内嵌输入框的卡顿。
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
        return
    }

    val visibleSites = if (relayState.isExpanded || relaySites.size <= 3) {
        relaySites
    } else {
        relaySites.take(3)
    }

    items(
        items = visibleSites,
        key = { "relay_site_${it.id}" },
        contentType = { "relay_card" }
    ) { site ->
        val currentInputUrl by rememberUpdatedState(globalInputUrl)
        val isCurrentResolving = relayState.resolvingSiteId == site.id
        val currentStatus = if (isCurrentResolving) relayState.resolvingStatus else ""

        RelaySiteCard(
            site = site,
            isResolving = isCurrentResolving,
            resolvingStatus = currentStatus,
            onStartResolve = { targetSite ->
                val finalUrl = currentInputUrl.trim()
                if (finalUrl.isBlank()) {
                    Toast.makeText(context, "请先在上方输入框粘贴欲解析的视频链接", Toast.LENGTH_SHORT).show()
                } else {
                    relayState.startResolve(targetSite, finalUrl)
                }
            },
            onCancelResolve = {
                relayState.cancelResolve()
            },
            onOpenManualBrowser = { targetSite ->
                relayState.openManualBrowser(targetSite, currentInputUrl.trim())
            },
            onDeleteSite = onDeleteSite
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
 * 中转站的后台静默解析引擎与备用排查弹窗（挂载在屏幕外层，不参与 LazyColumn 的任何滑动测量）
 */
@Composable
fun RelaySitesOverlays(
    relayState: RelaySitesState,
    onStartDirectDownload: (directUrl: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val resolvingSite = relayState.resolvingSite
    val resolvingVideoUrl = relayState.resolvingVideoUrl

    // 核心流转 1：就地后台静默解析（用户点击“直接解析”后自动提取并入队下载，完全不弹窗跳网页）
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

    // 核心流转 2：备用窗口排查弹窗（当且仅当用户主动点击“窗口排查”图标时打开）
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
        return
    }

    val context = LocalContext.current
    val relayState = rememberRelaySitesState()
    val visibleSites = if (relayState.isExpanded || relaySites.size <= 3) {
        relaySites
    } else {
        relaySites.take(3)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        visibleSites.forEach { site ->
            key(site.id) {
                val isCurrentResolving = relayState.resolvingSiteId == site.id
                val currentStatus = if (isCurrentResolving) relayState.resolvingStatus else ""

                RelaySiteCard(
                    site = site,
                    isResolving = isCurrentResolving,
                    resolvingStatus = currentStatus,
                    onStartResolve = { targetSite ->
                        val finalUrl = globalInputUrl.trim()
                        if (finalUrl.isBlank()) {
                            Toast.makeText(context, "请先在上方输入框粘贴欲解析的视频链接", Toast.LENGTH_SHORT).show()
                        } else {
                            relayState.startResolve(targetSite, finalUrl)
                        }
                    },
                    onCancelResolve = {
                        relayState.cancelResolve()
                    },
                    onOpenManualBrowser = { targetSite ->
                        relayState.openManualBrowser(targetSite, globalInputUrl.trim())
                    },
                    onDeleteSite = onDeleteSite
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
