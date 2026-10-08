package com.omni.downloader.ui.components

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.downloader.data.model.RelaySite

/**
 * 中转站嗅探浏览器状态持有者：
 * 隔离浏览器弹窗与列表状态，实现零冗余重组。
 */
@Stable
class RelaySitesState {
    var browserSite by mutableStateOf<RelaySite?>(null)
    var browserInitialUrl by mutableStateOf("")

    var isExpanded by mutableStateOf(false)

    fun openBrowser(site: RelaySite, initialUrl: String = "") {
        browserSite = site
        browserInitialUrl = initialUrl
    }

    fun dismissBrowser() {
        browserSite = null
        browserInitialUrl = ""
    }

    // 兼容旧签名
    fun openManualBrowser(site: RelaySite, url: String) = openBrowser(site, url)
    fun dismissManualBrowser() = dismissBrowser()
    fun cancelResolve() = dismissBrowser()
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

        RelaySiteCard(
            site = site,
            onOpenSite = { targetSite ->
                relayState.openBrowser(targetSite, currentInputUrl.trim())
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
 * 中转站的智能嗅探浏览器弹窗（挂载在屏幕外层，不参与 LazyColumn 的任何滑动测量）
 */
@Composable
fun RelaySitesOverlays(
    relayState: RelaySitesState,
    onStartDirectDownload: (directUrl: String, title: String) -> Unit
) {
    val activeSite = relayState.browserSite
    if (activeSite != null) {
        RelayBrowserDialog(
            site = activeSite,
            initialVideoUrl = relayState.browserInitialUrl,
            onDismiss = { relayState.dismissBrowser() },
            onCapturedDownload = { directUrl, title ->
                onStartDirectDownload(directUrl, title)
                relayState.dismissBrowser()
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
                RelaySiteCard(
                    site = site,
                    onOpenSite = { targetSite ->
                        relayState.openBrowser(targetSite, globalInputUrl.trim())
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
