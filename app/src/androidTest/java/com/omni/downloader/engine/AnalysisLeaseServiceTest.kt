package com.omni.downloader.engine

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.service.DownloadService
import com.omni.downloader.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class AnalysisLeaseServiceTest {
    @Suppress("DEPRECATION")
    @Test fun finishingOldAnalysisDoesNotStopNewBackgroundAnalysis() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun foreground() = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(30).any { it.service.className == DownloadService::class.java.name && it.foreground }
        try {
            instrument.runOnMainSync {
                DownloadService.analysisLease(context, "old-test", true)
                DownloadService.analysisLease(context, "new-test", true)
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(foreground())
            instrument.uiAutomation.executeShellCommand("input keyevent 3").use {
                java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
            }
            DownloadService.analysisLease(context, "old-test", false)
            SystemClock.sleep(600)
            assertTrue("New analysis must retain the foreground service", foreground())
            DownloadService.analysisLease(context, "new-test", false)
            val idleDeadline = SystemClock.elapsedRealtime() + 5000
            while (foreground() && SystemClock.elapsedRealtime() < idleDeadline) SystemClock.sleep(50)
            assertFalse("Idle analysis service must stop", foreground())
        } finally { scenario.close() }
    }
}
