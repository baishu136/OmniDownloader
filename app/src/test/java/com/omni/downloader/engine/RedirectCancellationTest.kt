package com.omni.downloader.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class RedirectCancellationTest {
    @Test fun cancelledSlowRedirectDoesNotHoldTheAnalysisForTheSocketTimeout() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try { server.accept().use { connection ->
                connection.getInputStream().bufferedReader().readLine()
                Thread.sleep(2000)
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        try {
            val client = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
            val start = System.nanoTime()
            val result = withTimeoutOrNull(150) {
                LinkNormalizer.resolveRedirects("http://127.0.0.1:${server.localPort}/slow", client, "test")
            }
            assertNull(result)
            assertTrue("Cancellation waited for the blocking socket", System.nanoTime() - start < 1_000_000_000L)
        } finally { server.close(); worker.interrupt(); worker.join(1000) }
    }
}
