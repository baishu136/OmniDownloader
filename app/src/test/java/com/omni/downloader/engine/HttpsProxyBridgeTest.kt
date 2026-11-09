package com.omni.downloader.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class HttpsProxyBridgeTest {
    private object Certificates {
        val context: SSLContext
        val manager: X509TrustManager
        init {
            val folder = java.nio.file.Files.createTempDirectory("omni-test-tls-").toFile()
            val store = File(folder, "fixture.p12")
            try {
                val tool = File(System.getProperty("java.home"), "bin/keytool.exe")
                val process = ProcessBuilder(tool.path, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "fixture-only", "-keypass", "fixture-only",
                    "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1,DNS:localhost", "-validity", "2", "-noprompt")
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
                val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "fixture-only".toCharArray()) } }
                val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
                context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(manager), null) }
            } finally { store.delete(); folder.delete() }
        }
    }

    @Test fun encryptedProxyCarriesConnectAndDestinationTlsWithoutChangingPayload(): Unit = runBlocking {
        val context = Certificates.context
        (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            var connect = ""
            val thread = Thread {
                (server.accept() as SSLSocket).use { outer ->
                    outer.soTimeout = 5000
                    val input = outer.inputStream.bufferedReader()
                    connect = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) { }
                    outer.outputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); outer.outputStream.flush()
                    (context.socketFactory.createSocket(outer, "localhost", 443, true) as SSLSocket).use { inner ->
                        inner.useClientMode = false; inner.startHandshake()
                        val reader = inner.inputStream.bufferedReader()
                        check(reader.readLine().startsWith("HEAD /probe"))
                        while (!reader.readLine().isNullOrEmpty()) { }
                        inner.outputStream.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); inner.outputStream.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            HttpsProxyBridge(ProxyConfiguration("https", "127.0.0.1", server.localPort), context.socketFactory).use { bridge ->
                val client = OkHttpClient.Builder().proxy(bridge.proxy).sslSocketFactory(context.socketFactory, Certificates.manager)
                    .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                try {
                    val result = ProxyConnectivityTester.probe(client, "fixture", "https://localhost/probe")
                    assertTrue(result.message, result.reachable); assertEquals("连接成功", result.message)
                    thread.join(2000); assertTrue(connect.startsWith("CONNECT localhost:443"))
                    assertNull(bridge.lastFailure)
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }

    @Test fun untrustedProxyCertificateIsRejectedInsteadOfDowngradingToPlainHttp(): Unit = runBlocking {
        (Certificates.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            val thread = Thread {
                runCatching { (server.accept() as SSLSocket).use { it.soTimeout = 4000; it.startHandshake() } }
            }.apply { isDaemon = true; start() }
            HttpsProxyBridge(ProxyConfiguration("https", "127.0.0.1", server.localPort)).use { bridge ->
                val client = OkHttpClient.Builder().proxy(bridge.proxy).callTimeout(6, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                try {
                    val result = ProxyConnectivityTester.probe(client, "fixture", "http://example.invalid/")
                    assertFalse(result.reachable); assertTrue(bridge.lastFailure is SSLException)
                    thread.join(2000)
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
}
