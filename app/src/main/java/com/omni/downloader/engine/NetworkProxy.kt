package com.omni.downloader.engine

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException

/** Native adapters follow the same configured proxy as yt-dlp. */
object NetworkProxy : ProxySelector() {
    @Volatile var configuredUrl: String = ""
        set(value) {
            synchronized(this) {
                if (field != value) { bridge?.close(); bridge = null; field = value }
            }
        }
    private var bridge: HttpsProxyBridge? = null
    override fun select(uri: URI): List<Proxy> {
        return synchronized(this) {
            val configuration = ProxyConfiguration.parse(configuredUrl)
            listOf(if (configuration?.scheme == "https") {
                (bridge ?: HttpsProxyBridge(configuration).also { bridge = it }).proxy
            } else configuration?.toProxy() ?: Proxy.NO_PROXY)
        }
    }
    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
}
