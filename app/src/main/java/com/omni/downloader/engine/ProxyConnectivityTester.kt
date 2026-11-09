package com.omni.downloader.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal data class ConnectivityResult(val name: String, val reachable: Boolean, val message: String, val elapsedMs: Long)

/** One-shot, cancellable, cookie-free probes. Never changes the application's saved route. */
internal object ProxyConnectivityTester {
    suspend fun test(input: String, endpoints: List<Pair<String, String>> = listOf(
        "国内站点" to "https://www.bilibili.com/", "国际站点" to "https://www.gstatic.com/generate_204"
    )): List<ConnectivityResult> = withContext(Dispatchers.IO) {
        val configuration = ProxyConfiguration.parse(input)
        val bridge = if (configuration?.scheme == "https") HttpsProxyBridge(configuration) else null
        val proxy = bridge?.proxy ?: configuration?.toProxy() ?: Proxy.NO_PROXY
        val client = OkHttpClient.Builder().proxy(proxy).cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        try {
            coroutineScope { endpoints.map { (name, url) -> async { probe(client, name, url) } }.awaitAll() }
                .map { result -> if (!result.reachable && bridge?.lastFailure is SSLException)
                    result.copy(message = "HTTPS 代理 TLS 握手或证书验证失败") else result }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown(); bridge?.close()
            }
        }
    }

    internal suspend fun probe(client: OkHttpClient, name: String, url: String): ConnectivityResult = suspendCancellableCoroutine { continuation ->
        val started = System.nanoTime()
        val call = client.newCall(Request.Builder().url(url).head()
            .header("User-Agent", "Mozilla/5.0 OmniDownloader-Connectivity").build())
        continuation.invokeOnCancellation { call.cancel() }
        fun finish(reachable: Boolean, message: String) {
            if (continuation.isActive) continuation.resume(ConnectivityResult(name, reachable, message, (System.nanoTime() - started) / 1_000_000))
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = when (e) {
                    is SocketTimeoutException -> "连接超时"
                    is java.io.InterruptedIOException -> "连接超时"
                    is UnknownHostException -> "域名解析失败"
                    is ConnectException -> "连接被拒绝或代理未启动"
                    is SSLException -> "TLS 握手或证书验证失败"
                    else -> if (e.message.orEmpty().contains("authenticate", true) || e.message.orEmpty().contains("407")) "代理需要认证" else "网络连接失败"
                }
                finish(false, message)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.header("X-Omni-Proxy-Error") != null) finish(false, "HTTPS 代理连接失败")
                    else if (it.code == 407) finish(false, "代理需要认证")
                    else finish(true, if (it.code in 200..399) "连接成功" else "已连接，站点返回 HTTP ${it.code}")
                }
            }
        })
    }
}
