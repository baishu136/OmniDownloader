package com.omni.downloader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.ui.components.*
import com.omni.downloader.ui.theme.OmniDownloaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OptionMenuTest {
    @get:Rule val compose = createComposeRule()
    private val expanded = mutableStateOf(false)
    private var calls = 0
    private fun render(dark: Boolean = false, count: Int = 4) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OmniDownloaderTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().testTag("menu_harness")) {
                    Box(Modifier.padding(24.dp)) {
                        OutlinedButton(onClick = { expanded.value = true }) { Text("打开菜单") }
                        OmniOptionMenu(expanded.value, onDismissRequest = { expanded.value = false }) {
                            repeat(count) { index ->
                                OmniOptionMenuItem(label = "选项 $index", selected = index == 0, enabled = index != 2, onClick = { calls++ })
                            }
                        }
                    }
                }
            }
        }
    }
    private fun open() {
        compose.onNodeWithText("打开菜单").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(150) // Allow the platform Popup window's render frame to commit before capturing it.
        val instrument = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(instrument.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun selectionWaitsForExitAndClosingDisablesRows() {
        render(); open()
        compose.onNodeWithText("选项 0").assertIsSelected()
        compose.onNodeWithText("选项 2").assertIsNotEnabled()
        screenshot("option-menu-light.png")
        compose.onNodeWithText("选项 1").performClick()
        compose.runOnIdle { assertEquals(0, calls); assertFalse(expanded.value) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("选项 1").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun backDismissesWithoutExecutingAnOptionAndCanReopen() {
        render(); open()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
        compose.waitUntil(3000) { !expanded.value }
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
        open(); compose.onNodeWithText("选项 0").assertIsDisplayed()
        screenshot("option-menu-reopened.png")
    }
    @Test fun darkLongMenuScrollsAndTheLastChoiceWorks() {
        render(dark = true, count = 18); open()
        screenshot("option-menu-dark.png")
        compose.onNodeWithText("选项 17").performScrollTo()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("选项 17").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
    }
    @Test fun closingHalfwayThroughOpeningFinishesWithoutAStalePopup() {
        render()
        compose.onNodeWithText("打开菜单").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
        screenshot("option-menu-opening.png")
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        compose.onNodeWithText("选项 1").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
    }
    @Test fun outsideTapDismissesWithoutExecutingAnOption() {
        render(); open()
        // Inject through the window manager, so ACTION_OUTSIDE reaches the separate native Popup window.
        val instrument = InstrumentationRegistry.getInstrumentation()
        val display = instrument.targetContext.resources.displayMetrics
        instrument.uiAutomation.executeShellCommand("input tap ${display.widthPixels - 24} ${display.heightPixels - 200}").use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
        compose.waitUntil(3000) { !expanded.value }
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
