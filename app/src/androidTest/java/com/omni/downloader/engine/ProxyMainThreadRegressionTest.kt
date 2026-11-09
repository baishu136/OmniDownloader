package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProxyMainThreadRegressionTest {
    @Test fun blankProxyProbeAndTlsCleanupAreSafeWhenCalledFromMain(): Unit = runBlocking {
        val original = NetworkProxy.configuredUrl
        repeat(2) {
            val results = withContext(Dispatchers.Main) { ProxyConnectivityTester.test("") }
            assertEquals(2, results.size)
            assertEquals(original, NetworkProxy.configuredUrl)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "OMNI_DIRECT_PROBE=completed round=${it + 1} results=${results.map { r -> "${r.name}:${r.message}" }}\n")
            })
        }
    }
}
