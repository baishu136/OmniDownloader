package com.omni.downloader

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.*
import com.omni.downloader.data.model.*
import com.omni.downloader.engine.*
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BatchWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun inputsAndPendingBatchSurviveActivityAndProcessRestart() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val restore = InstrumentationRegistry.getArguments().getString("draftPhase") == "read"
        val drafts = InputDraftRepository.getInstance(context);drafts.awaitLoaded()
        if (!restore) {
            drafts.update { InputDraft() };drafts.awaitSaved()
            context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE).edit().putString("plan", SessionCrypto.encrypt(Json.encodeToString(BatchPlan(listOf(
                BatchItem("saved-pending", "https://example.org/pending", BatchStatus.PENDING),
                BatchItem("saved-resolving", "https://example.org/resolving", BatchStatus.RESOLVING)
            ))))).commit()
        } else {
            assertEquals("https://example.org/home-retained", drafts.draft.value.home)
            assertEquals("https://example.org/images-retained", drafts.draft.value.images)
            assertEquals(3, drafts.draft.value.batchLinks.size)
            assertEquals("https://example.org/not-yet-added", drafts.draft.value.batchEntry)
            assertEquals(AudioFormat.OPUS, drafts.draft.value.batchOptions.audio)
            assertTrue(drafts.draft.value.batchOptions.allEpisodes)
        }
        context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("key_has_prompted_bilibili_login", true).putBoolean("key_has_shown_relay_compat_tip", true).commit()
        SettingsRepository.getInstance(context).apply { setAppLanguage("zh-CN");setThemeMode("light");setProxyUrl("") }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun hideKeyboard() { scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) };compose.waitForIdle() }
        fun screenshot(name: String) {
            hideKeyboard();Thread.sleep(250)
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) };bitmap.recycle()
        }
        try {
            compose.onNodeWithTag("images_fab").assertIsDisplayed()
            compose.onNodeWithTag("batch_entry").assertDoesNotExist()
            if (!restore) {
                compose.onNodeWithTag("home_link_input").performTextReplacement("https://example.org/home-retained")
                hideKeyboard()
                compose.onNodeWithTag("images_fab").performClick()
                compose.onNodeWithTag("images_link_input").performTextReplacement("https://example.org/images-retained")
                hideKeyboard()
                compose.onNodeWithContentDescription("返回首页").performClick()
            } else compose.onNodeWithTag("home_link_input").assertTextContains("https://example.org/home-retained")
            screenshot("home-1724.png")
            compose.onNodeWithContentDescription("批量", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("batch_screen").assertIsDisplayed()
            if (!restore) {
                compose.onNodeWithTag("batch_start").assertIsNotEnabled()
                compose.onNodeWithTag("batch_input").performTextReplacement("分享 https://example.org/one\nhttps://example.org/two\nhttps://example.org/one")
                compose.onNodeWithTag("batch_add").performClick()
                compose.runOnIdle { assertEquals(2, drafts.draft.value.batchLinks.size) }
                compose.onNodeWithTag("batch_manual_mode").performClick()
                compose.onNodeWithTag("batch_input").performTextReplacement("https://example.org/three")
                compose.onNodeWithTag("batch_add").performClick()
                compose.runOnIdle { assertEquals(3, drafts.draft.value.batchLinks.size) }
                compose.onNodeWithTag("batch_input").performTextReplacement("https://example.org/not-yet-added")
                hideKeyboard()
                compose.onNodeWithText(DownloadType.VIDEO_WITH_AUDIO.label).performClick();compose.onNodeWithText(DownloadType.AUDIO_ONLY.label).performClick()
                compose.onNodeWithText("MP3").performClick();compose.onNodeWithText("OPUS").performClick()
                compose.onNode(isToggleable()).performClick()
                drafts.awaitSaved()
                assertFalse(context.getSharedPreferences("omni_input_drafts", Context.MODE_PRIVATE).getString("draft", "")!!.contains("example.org"))
                val fresh = InputDraftRepository(context);fresh.awaitLoaded();assertEquals(drafts.draft.value, fresh.draft.value)
                scenario.recreate()
            }
            compose.onNodeWithTag("batch_input").assertTextContains("https://example.org/not-yet-added")
            compose.onNodeWithTag("batch_screen").performScrollToNode(hasTestTag("batch_start"))
            compose.onNodeWithTag("batch_start").assertIsEnabled()
            screenshot("batch-1724.png")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("首页", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("batch_screen").performScrollToNode(hasTestTag("batch_start"))
            screenshot("batch-landscape-1724.png")
            compose.onNodeWithTag("batch_start").assertIsDisplayed()
            assertEquals("https://example.org/not-yet-added", drafts.draft.value.batchEntry)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val recovered = BatchDownloads(context, scope)
                withTimeout(5000) { while (recovered.queue.plan.value.items.isEmpty()) delay(20) }
                assertEquals(listOf("saved-pending", "saved-resolving"), recovered.queue.plan.value.items.map { it.id })
                assertTrue(recovered.queue.plan.value.items.all { it.status == BatchStatus.CANCELLED })
            } finally { scope.cancel() }
        } finally { scenario.close() }
    }
}
