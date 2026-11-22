package com.omni.downloader.ui

import android.view.View
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLaunchTest {
    private fun awaitExit(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5000L
        var finished = false
        while (!finished && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { finished = it.launchExitFinished }
            if (!finished) SystemClock.sleep(20)
        }
        assertTrue("Splash must release the post-exit work gate", finished)
    }

    @Test fun pageRemainsOpaqueAndStationaryAfterLaunchAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitExit(scenario)
            fun assertPageIsStationary() {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val page = activity.findViewById<View>(android.R.id.content)
                    assertEquals(1f, page.alpha)
                    assertEquals(0f, page.translationY)
                    assertEquals(1f, page.scaleX)
                    assertEquals(1f, page.scaleY)
                    assertEquals(View.LAYER_TYPE_NONE, page.layerType)
                }
            }
            assertPageIsStationary()
            scenario.recreate()
            awaitExit(scenario)
            assertPageIsStationary()
        }
    }

    @Test fun newActivityInTheSameProcessAlsoCompletesItsExit() {
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use { awaitExit(it) }
        }
    }

    @Test fun stoppingDuringExitDoesNotLeaveAnOverlayOrBlockedWorkGate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitExit(scenario)
            scenario.onActivity {
                val content = it.findViewById<View>(android.R.id.content)
                assertEquals(1f, content.alpha)
                assertEquals(0f, content.translationY)
                assertEquals(1f, content.scaleX)
                assertEquals(1f, content.scaleY)
                assertEquals(View.LAYER_TYPE_NONE, content.layerType)
            }
        }
    }
}
