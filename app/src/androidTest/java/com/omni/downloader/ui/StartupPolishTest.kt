package com.omni.downloader.ui

import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.util.TypedValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.R
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.theme.LaunchTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StartupPolishTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()

    @Test fun explicitLaunchAndWindowColorsIgnoreOppositeSystemTheme() {
        for ((mode, systemNight, background, icon) in listOf(
            ThemeCase("dark", Configuration.UI_MODE_NIGHT_NO, Color.rgb(17, 19, 24), Color.rgb(184, 195, 255)),
            ThemeCase("light", Configuration.UI_MODE_NIGHT_YES, Color.rgb(247, 247, 251), Color.rgb(69, 84, 200))
        )) {
            val base = instrument.targetContext
            val config = Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or systemNight
            }
            val context = base.createConfigurationContext(config)
            fun color(style: Int, attribute: Int): Int {
                val themed = ContextThemeWrapper(context, style)
                val value = TypedValue()
                assertTrue(themed.theme.resolveAttribute(attribute, value, true))
                return value.data
            }
            assertEquals(background, color(LaunchTheme.startingStyle(mode), androidx.core.splashscreen.R.attr.windowSplashScreenBackground))
            assertEquals(icon, color(LaunchTheme.startingStyle(mode), R.attr.omniLaunchIconColor))
            val window = ContextThemeWrapper(context, LaunchTheme.contentStyle(mode))
            val value = TypedValue()
            window.theme.resolveAttribute(android.R.attr.windowBackground, value, true)
            assertEquals(background, window.getColor(value.resourceId))
        }
    }

    @Test fun firstDrawWorkRunsOnceAndCanBeCancelledBeforeDrawing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val done = CountDownLatch(1)
            val calls = AtomicInteger()
            val cancelledCalls = AtomicInteger()
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                afterFirstDraw(view) { calls.incrementAndGet(); done.countDown() }
                afterFirstDraw(view) { cancelledCalls.incrementAndGet() }.invoke()
                assertEquals(0, calls.get())
            }
            assertTrue("Work must run after a drawn frame", done.await(5, TimeUnit.SECONDS))
            scenario.onActivity { it.window.decorView.invalidate() }
            instrument.waitForIdleSync()
            assertEquals(1, calls.get())
            assertEquals(0, cancelledCalls.get())
        }
    }

    @Test fun committedFrameWorkRunsOnceAndSupportsCancellation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val done = CountDownLatch(1)
            val calls = AtomicInteger()
            val cancelled = AtomicInteger()
            scenario.onActivity {
                afterCommittedFrame(it.window.decorView) { calls.incrementAndGet(); done.countDown() }
                afterCommittedFrame(it.window.decorView) { cancelled.incrementAndGet() }.invoke()
                assertEquals(0, calls.get())
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            instrument.waitForIdleSync()
            assertEquals(1, calls.get())
            assertEquals(0, cancelled.get())
        }
    }

    @Test fun themePreferenceChangePreservesTheLiveActivity() {
        val settings = SettingsRepository.getInstance(instrument.targetContext)
        val original = settings.themeMode.value
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                lateinit var first: MainActivity
                scenario.onActivity { first = it; settings.setThemeMode("dark") }
                instrument.waitForIdleSync()
                scenario.onActivity { assertSame(first, it); settings.setThemeMode("light") }
                instrument.waitForIdleSync()
                scenario.onActivity { assertSame(first, it) }
            } finally {
                scenario.onActivity {
                    settings.setThemeMode(original)
                    LaunchTheme.persistForNextLaunch(it, original)
                }
                instrument.waitForIdleSync()
            }
        }
    }

    private data class ThemeCase(val mode: String, val night: Int, val background: Int, val icon: Int)
}
