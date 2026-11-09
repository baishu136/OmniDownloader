package com.omni.downloader.engine

import java.io.Closeable
import java.io.IOException
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Adds TLS to the proxy hop. Destination TLS remains end-to-end inside CONNECT.
 * Only loopback is exposed; proxy certificates and hostnames are always verified.
 */
internal class HttpsProxyBridge(
    private val configuration: ProxyConfiguration,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val capacity = Semaphore(8)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "omni-https-proxy").apply { isDaemon = true } }
    @Volatile var lastFailure: IOException? = null
        private set
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.localPort))
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        require(configuration.scheme == "https")
        Thread({
            while (!closed.get()) {
                val client = try { server.accept() } catch (_: IOException) { break }
                if (!capacity.tryAcquire()) { runCatching { client.close() }; continue }
                sockets += client
                try { workers.execute { relay(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { sockets -= client; runCatching { client.close() }; capacity.release() }
            }
        }, "omni-https-proxy-accept").apply { isDaemon = true; start() }
    }

    private fun relay(client: Socket) {
        val remote = Socket(Proxy.NO_PROXY)
        sockets += remote
        var tls: SSLSocket? = null
        var forwarding = false
        try {
            if (closed.get()) return
            remote.connect(InetSocketAddress(configuration.host, configuration.port), 8_000)
            remote.soTimeout = 8_000
            tls = tlsFactory.createSocket(remote, configuration.host, configuration.port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            forwarding = true
            tls.soTimeout = 60_000; client.soTimeout = 60_000
            val encrypted = tls
            workers.execute {
                try { client.getInputStream().copyTo(encrypted.outputStream, 16 * 1024) }
                catch (_: IOException) { }
                finally { runCatching { remote.close() }; runCatching { client.close() } }
            }
            encrypted.inputStream.copyTo(client.getOutputStream(), 16 * 1024)
        } catch (error: Exception) {
            lastFailure = error as? IOException ?: IOException("HTTPS proxy transport failed", error)
            if (!forwarding) runCatching {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nX-Omni-Proxy-Error: tls-proxy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        } finally {
            // TLS close_notify may perform network I/O. This always runs on a worker.
            runCatching { tls?.close() }; runCatching { remote.close() }; runCatching { client.close() }
            sockets -= client; sockets -= remote; capacity.release()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            // Raw sockets unblock both pumps without sending TLS on the caller thread.
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }
}
