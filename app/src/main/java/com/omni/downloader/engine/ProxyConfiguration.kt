package com.omni.downloader.engine

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

internal data class ProxyConfiguration(val scheme: String, val host: String, val port: Int) {
    val webViewUrl: String get() = "${if (scheme == "socks5") "socks" else scheme}://${if (host.contains(':')) "[$host]" else host}:$port"
    val url: String get() = "${if (scheme == "socks5") "socks5h" else scheme}://${if (host.contains(':')) "[$host]" else host}:$port"
    // Leave proxy-host DNS to the networking worker; settings validation/test
    // setup may run on the UI thread.
    fun toProxy(): Proxy {
        require(scheme != "https") { "HTTPS 代理需要加密连接适配" }
        return Proxy(if (scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    }

    companion object {
        fun parse(input: String): ProxyConfiguration? {
            val text = input.trim()
            if (text.isEmpty()) return null
            require(text.none(Char::isWhitespace)) { "代理地址中不能包含空格" }
            val uri = runCatching { URI(if (text.contains("://")) text else "http://$text") }
                .getOrElse { throw IllegalArgumentException("代理地址格式不正确，请输入主机和端口") }
            val scheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http" -> "http"
                "socks", "socks5", "socks5h" -> "socks5"
                "https" -> "https"
                else -> throw IllegalArgumentException("代理协议仅支持 HTTP、HTTPS 和 SOCKS5")
            }
            require(uri.rawUserInfo == null) { "暂不支持需要账号密码的代理，请使用免认证 HTTP 或 SOCKS5 地址" }
            require(uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) { "代理地址不能包含路径、查询参数或片段" }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
            require(!host.isNullOrBlank()) { "请填写有效的代理主机地址；IPv6 地址需使用方括号" }
            val port = if (uri.port == -1) when (scheme) { "socks5" -> 1080; "https" -> 443; else -> 8080 } else uri.port
            require(port in 1..65535) { "代理端口必须在 1–65535 之间" }
            return ProxyConfiguration(scheme, host, port)
        }
    }
}
