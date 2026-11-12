package com.omni.downloader

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NavigationMotionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun fillAnimatesAndRapidNavigationSettlesOnTheLastTab() {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        SettingsRepository.getInstance(context).apply { setHasPromptedBilibiliLogin(true);setAppLanguage("zh-CN");setThemeMode("light") }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun pixels(tag: String): List<Int> {
            val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
            return (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image[x,y].toArgb() } }
        }
        try {
            compose.onNodeWithTag("nav_tab_0").assertIsSelected()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("nav_tab_2").performClick()
            compose.mainClock.advanceTimeBy(32)
            val early = pixels("nav_icon_2")
            compose.mainClock.advanceTimeBy(80)
            val middle = pixels("nav_icon_2")
            compose.mainClock.advanceTimeBy(400)
            val final = pixels("nav_icon_2")
            assertNotEquals("Fill should change the rendered icon during the transition", early, middle)
            assertNotEquals("Fill should finish after the intermediate frame", middle, final)
            compose.onNodeWithTag("nav_tab_3").performClick();compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("nav_tab_1").performClick();compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("nav_tab_0").performClick();compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("nav_tab_0").assertIsSelected()
            for (id in 1..3) compose.onNodeWithTag("nav_tab_$id").assertIsNotSelected()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "navigation-buttons.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } finally { compose.mainClock.autoAdvance = true;scenario.close() }
    }
}
