package com.omni.downloader.ui.components

import androidx.compose.runtime.Composable
import com.omni.downloader.data.model.RelaySite

/**
 * 历史静默中转引擎占位（已废弃）：
 * 系统已全面迁移至原生可靠的 RelayBrowserDialog 前台智能嗅探浏览器，
 * 彻底消除后台离屏 WebView 引发的 Cloudflare 人机验证死锁与非法 URL 报错。
 */
@Deprecated("Use RelayBrowserDialog instead")
@Composable
fun SilentRelayEngine(
    site: RelaySite,
    videoUrl: String,
    onSuccess: (directUrl: String, title: String) -> Unit,
    onError: (errorMessage: String) -> Unit,
    onProgress: (statusMessage: String) -> Unit
) {
    // 已废弃，无操作
}
