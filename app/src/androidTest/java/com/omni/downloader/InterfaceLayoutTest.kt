package com.omni.downloader

import android.content.Context
import android.graphics.Bitmap
import androidx.core.view.WindowCompat
import com.omni.downloader.ui.localization.ZhHansStrings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.localization.EnStrings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class InterfaceLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun annotatedHomeAndCategorizedSettingsKeepDraftsAndBackStack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("key_has_prompted_bilibili_login", true)
            .putBoolean("key_has_shown_relay_compat_tip", true).putInt("key_app_config_version", 40)
            .putString("key_app_language", "en").putString("key_theme_mode", "light")
            .putString("key_proxy_url", "").putString("key_relay_sites", Json.encodeToString(
                (1..40).map { RelaySite("ui-" + it, "Relay " + it, "https://example.org/" + it) }
            )).commit()
        File(context.filesDir, "download_tasks_v1.json").writeText("[]")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun screenshot(name: String) {
            compose.waitForIdle()
            Thread.sleep(350) // Settle ripple, placeholder and cross-process system-bar color transitions.
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull(image)
            File(context.getExternalFilesDir(null), name + ".png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        fun nav(label: String) {
            compose.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
            compose.waitForIdle()
        }
        fun category(name: String) {
            compose.onNodeWithTag("settings_category_" + name).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("settings_page_" + name).assertIsDisplayed()
        }
        fun back() {
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("settings_page_ROOT").assertIsDisplayed()
        }
        try {
            screenshot("home-first-frame")
            compose.waitForIdle()
            Thread.sleep(600) // Allow the platform launch splash to leave the screen.
            val platformBounds = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot
            val inputBounds = compose.onNodeWithTag("home_link_input").fetchSemanticsNode().boundsInRoot
            assertTrue("Platform tags must precede the link field", platformBounds.bottom < inputBounds.top)
            assertTrue("Link field must have a single-line height", inputBounds.height <= 64f * context.resources.displayMetrics.density)
            compose.onNodeWithText(EnStrings.appSubtitle).assertDoesNotExist()
            compose.onNodeWithText(EnStrings.relaySitesSection).assertDoesNotExist()
            screenshot("home-light")
            compose.onNodeWithTag("home_link_input").performTextInput("https://example.org/retained")
            nav("Settings")
            screenshot("settings-root-light")
            category("APPEARANCE")
            compose.onNodeWithText(EnStrings.appearanceLight).performClick()
            compose.onNodeWithText(EnStrings.appearanceDark).performClick()
            compose.waitForIdle()
            assertEquals("dark", prefs.getString("key_theme_mode", ""))
            scenario.onActivity { activity ->
                val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertFalse("Dark theme needs light status icons", bars.isAppearanceLightStatusBars)
                assertFalse("Dark theme needs a light gesture handle", bars.isAppearanceLightNavigationBars)
            }
            screenshot("settings-appearance-dark")
            compose.onNodeWithText(EnStrings.appearanceDark).performClick()
            compose.onNodeWithText(EnStrings.appearanceLight).performClick()
            back()
            category("NETWORK")
            val field = hasSetTextAction() and hasAnyAncestor(hasTestTag("settings_page_NETWORK"))
            compose.onNode(field).performTextInput("http://127.0.0.1:7890")
            back()
            category("NETWORK")
            compose.onNode(field).assertTextContains("http://127.0.0.1:7890")
            compose.onNodeWithText(EnStrings.applyProxy).performClick()
            compose.waitForIdle()
            assertEquals("http://127.0.0.1:7890", prefs.getString("key_proxy_url", ""))
            nav("Home")
            compose.onNodeWithTag("home_link_input").assertTextContains("https://example.org/retained")
            nav("Settings")
            compose.onNodeWithTag("settings_page_NETWORK").assertIsDisplayed()
            back()
            category("RELAY")
            compose.onNodeWithTag("settings_page_RELAY").performScrollToNode(hasText("Relay 38"))
            compose.onNodeWithText("Relay 38").assertIsDisplayed()
            back()
            category("RELAY")
            compose.onNodeWithText("Relay 38").assertIsDisplayed()
            back()
            for (page in listOf("DOWNLOADS", "ACCOUNTS", "ENGINE", "ABOUT")) {
                category(page)
                if (page == "ABOUT") {
                    compose.onNodeWithTag("settings-github").assertIsDisplayed().assertHasClickAction()
                    compose.onNodeWithText("github.com/baishu136/OmniDownloader").assertIsDisplayed()
                    screenshot("settings-about-github")
                }
                back()
            }
            category("APPEARANCE")
            compose.onNodeWithText(EnStrings.languageEn).performClick()
            compose.onNodeWithText(EnStrings.languageZhCn).performClick()
            compose.waitForIdle()
            assertEquals("zh-CN", prefs.getString("key_app_language", ""))
            screenshot("settings-appearance-zh")
            back()
            screenshot("settings-root-zh")
            nav(ZhHansStrings.navHome)
            compose.onNodeWithContentDescription(ZhHansStrings.clear, useUnmergedTree = true).performClick()
            screenshot("home-zh")
        } finally { scenario.close() }
    }
}
