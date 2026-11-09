package com.omni.downloader.ui.components

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.omni.downloader.engine.ProxyConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Proxy overrides are process-wide; concurrent automatic/visible pages share a lease. */
internal class WebViewProxyLease private constructor() {
    private var released = false
    suspend fun release() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        gate.withLock {
            if (released) return@withLock
            released = true
            owners--
            if (owners == 0) {
                route = ""
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
                    try { withTimeoutOrNull(2000) { change(null) } } catch (_: Exception) { }
            }
        }
    }
    companion object {
        private val gate = Mutex()
        private var owners = 0
        private var route = ""
        suspend fun acquire(proxy: String): WebViewProxyLease = withContext(Dispatchers.Main.immediate) {
            gate.withLock {
                val rule = ProxyConfiguration.parse(proxy)?.webViewUrl.orEmpty()
                check(owners == 0 || route == rule) { "[NETWORK_ERROR] 浏览器网络已改变，请关闭旧页面后重试" }
                val supported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                check(rule.isBlank() || supported) { "[NETWORK_ERROR] 系统 WebView 暂不支持指定代理" }
                if (owners == 0) {
                    try { if (supported) withTimeout(2500) { change(rule.takeIf(String::isNotBlank)) } }
                    catch (error: Exception) {
                        if (supported) withContext(NonCancellable) { try { withTimeoutOrNull(2000) { change(null) } } catch (_: Exception) { } }
                        throw error
                    }
                    route = rule
                }
                owners++
                WebViewProxyLease()
            }
        }
        private suspend fun change(proxy: String?) = suspendCancellableCoroutine<Unit> { continuation ->
            val done = Runnable { if (continuation.isActive) continuation.resume(Unit) }
            val executor = Executor { it.run() }
            if (proxy == null) ProxyController.getInstance().clearProxyOverride(executor, done)
            else ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule(proxy).build(), executor, done)
        }
    }
}
