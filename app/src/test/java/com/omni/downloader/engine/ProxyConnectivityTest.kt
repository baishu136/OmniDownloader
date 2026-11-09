package com.omni.downloader.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProxyConnectivityTest {
    @Test fun socksUsesRemoteDestinationDnsWithoutDirectFallback(): Unit = runBlocking {
        val destination = AtomicReference<String>()
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                    output.write(byteArrayOf(5, 0)); output.flush()
                    check(input.readUnsignedByte() == 5); check(input.readUnsignedByte() == 1)
                    input.readUnsignedByte(); check(input.readUnsignedByte() == 3)
                    val host = ByteArray(input.readUnsignedByte()); input.readFully(host)
                    destination.set(host.toString(Charsets.UTF_8)); check(input.readUnsignedShort() == 80)
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
                    val reader = input.bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                }
            }.apply { isDaemon = true; start() }
            val result = ProxyConnectivityTester.test("socks5h://127.0.0.1:${server.localPort}", listOf("fixture" to "http://remote-dns.invalid/probe")).single()
            assertTrue(result.message, result.reachable)
            thread.join(3000); assertEquals("remote-dns.invalid", destination.get())
        }
    }
    @Test fun oneShotTestUsesRequestedProxyWithoutChangingSavedRouteOrSendingCookies(): Unit = runBlocking {
        val old = NetworkProxy.configuredUrl
        val request = AtomicReference<String>()
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    request.set(lines.joinToString("\n"))
                    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { isDaemon = true; start() }
            try {
                NetworkProxy.configuredUrl = "http://saved.example:7890"
                val result = ProxyConnectivityTester.test("127.0.0.1:${server.localPort}", listOf("fixture" to "http://not-a-real-host.invalid/probe")).single()
                assertTrue(result.reachable); assertEquals("连接成功", result.message)
                assertEquals("http://saved.example:7890", NetworkProxy.configuredUrl)
                thread.join(3000)
                assertTrue(request.get().startsWith("HEAD http://not-a-real-host.invalid/probe"))
                assertFalse(request.get().contains("Cookie:", true)); assertFalse(request.get().contains("Authorization:", true))
            } finally { NetworkProxy.configuredUrl = old }
        }
    }

    @Test fun closedProxyIsReportedAsFailureInsteadOfFallingBackToDirect(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val result = ProxyConnectivityTester.test("http://127.0.0.1:$port", listOf("fixture" to "http://127.0.0.1:1/")).single()
        assertFalse(result.reachable)
        assertTrue(result.message.contains("连接"))
    }

    @Test fun cancellationClosesInFlightProbe(): Unit = runBlocking {
        val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    entered.countDown()
                    try { if (reader.read() == -1) closed.countDown() } catch (_: java.io.IOException) { closed.countDown() }
                }
            }.apply { isDaemon = true; start() }
            val job = async(Dispatchers.Default) {
                ProxyConnectivityTester.test("127.0.0.1:${server.localPort}", listOf("fixture" to "http://unit.test/probe"))
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(closed.await(3, TimeUnit.SECONDS)); thread.join(3000)
        }
    }
}
