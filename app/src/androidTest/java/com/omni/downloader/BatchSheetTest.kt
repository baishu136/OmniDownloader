package com.omni.downloader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

class BatchSheetTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun homeEntryShowsOptionsAndKeepsInputAcrossRecreation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("key_has_prompted_bilibili_login", true)
            .putBoolean("key_has_shown_relay_compat_tip", true).commit()
        SettingsRepository.getInstance(context).apply {
            setAppLanguage("zh-CN"); setThemeMode("light"); setProxyUrl("")
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithTag("batch_entry").performScrollTo().performClick()
            compose.onNodeWithTag("batch_sheet").assertIsDisplayed()
            compose.onNodeWithTag("batch_start").assertIsNotEnabled()
            compose.onNodeWithTag("batch_input").performTextReplacement(
                "分享 https://example.org/one\nhttps://example.org/two\nhttps://example.org/one")
            scenario.recreate()
            compose.onNodeWithTag("batch_input").assertTextContains("https://example.org/two", substring = true)
            compose.onNodeWithTag("batch_sheet").performScrollToNode(hasTestTag("batch_start"))
            compose.onNodeWithTag("batch_start").assertIsEnabled()
            compose.onNodeWithText("下载合集 / 多视频的全部分集").assertIsDisplayed()
            compose.waitForIdle()
            Thread.sleep(300)
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "batch-download.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        } finally { scenario.close() }
    }
}
