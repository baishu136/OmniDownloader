package com.omni.downloader

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsOptionMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualSettingsThemeAndLanguageOptionsPersistAfterDismissal() {
        val instrument = InstrumentationRegistry.getInstrumentation(); val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldTheme = settings.themeMode.value; val oldLanguage = settings.appLanguage.value
        settings.setHasPromptedBilibiliLogin(true); settings.setThemeMode("light"); settings.setAppLanguage("zh-CN")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun screenshot(name: String) {
            compose.waitForIdle(); Thread.sleep(100)
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            compose.onNodeWithContentDescription("设置", useUnmergedTree = true).performClick()
            compose.onNodeWithText("浅色").performClick()
            compose.onNodeWithTag("omni_option_menu").assertIsDisplayed()
            screenshot("settings-option-menu-light.png")
            compose.onNodeWithText("深色").performClick()
            compose.waitUntil(3000) { settings.themeMode.value == "dark" }
            compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
            compose.onNodeWithText("深色").performClick()
            screenshot("settings-option-menu-dark.png")
            compose.onNodeWithText("浅色").performClick()
            compose.waitUntil(3000) { settings.themeMode.value == "light" }
            compose.onNodeWithText("简体中文").performClick()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(3000) { settings.appLanguage.value == "en" }
            compose.onNodeWithTag("omni_option_menu").assertDoesNotExist()
            assertEquals("en", settings.appLanguage.value)
        } finally { scenario.close(); settings.setThemeMode(oldTheme); settings.setAppLanguage(oldLanguage) }
    }
}
