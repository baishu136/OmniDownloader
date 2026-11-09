package com.omni.downloader.engine

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProxyConnectivityLiveTest {
    @Test fun reportsActualEndpointStatesWithoutChangingSavedRoute(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val input = InstrumentationRegistry.getArguments().getString("testProxy")
        assumeTrue("Opt-in proxy bridge required", input != null)
        val old = NetworkProxy.configuredUrl
        val results = ProxyConnectivityTester.test(input!!)
        assertEquals(old, NetworkProxy.configuredUrl)
        assertEquals(2, results.size)
        val json = JSONObject().put("completed", true).put("results", JSONArray().apply {
            results.forEach { put(JSONObject().put("name", it.name).put("reachable", it.reachable).put("message", it.message).put("elapsedMs", it.elapsedMs)) }
        })
        File(instrument.targetContext.cacheDir, "proxy-live-result.json").writeText(json.toString())
        instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_PROXY_TEST=$json\n") })
    }
}
