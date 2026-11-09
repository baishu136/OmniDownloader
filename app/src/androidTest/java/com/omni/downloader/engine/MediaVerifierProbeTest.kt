package com.omni.downloader.engine

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class MediaVerifierProbeTest {
    @Test fun inspectsReportedSubsecondXLoopWithBundledFallback() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val file = File(context.filesDir, "qa-176-x-loop.mp4")
        assertTrue("Push the private reported sample into the QA emulator sandbox", file.isFile)
        try {
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val system = MediaVerifier.probe(file)
            val actual = MediaVerifier.probeWithFallback(context, file)
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_PROBE=" + JSONObject()
                .put("bytes", file.length()).put("system", system.toString()).put("fallback", actual.toString()) + "\n") })
            assertNotNull(actual); assertEquals(650, actual!!.width); assertEquals(430, actual.height)
            assertTrue(actual.durationMs in 500..600); assertFalse(actual.hasAudio)
        } finally { file.delete() }
    }
}
