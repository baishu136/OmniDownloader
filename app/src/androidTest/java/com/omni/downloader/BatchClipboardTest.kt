package com.omni.downloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.*
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BatchClipboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pasteAddsOnceClearKeepsListAndInvalidInputIsRetained(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation();val context = instrument.targetContext
        val drafts = InputDraftRepository.getInstance(context);drafts.awaitLoaded();drafts.update { InputDraft(batchManual = true) }
        val settings = SettingsRepository.getInstance(context)
        settings.setHasPromptedBilibiliLogin(true);settings.setAppLanguage("zh-CN");settings.setThemeMode("light")
        val repository = TaskRepository.getInstance(context);repository.awaitLoaded()
        val tasksBefore = repository.tasks.value.map { it.id }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun clip(value: String) { instrument.runOnMainSync { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("test",value)) } }
        try {
            compose.onNodeWithContentDescription("批量", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("batch_paste_icon").assertIsDisplayed()
            clip("分享 https://example.org/one")
            compose.onNodeWithTag("batch_paste").performClick()
            compose.runOnIdle { assertEquals(listOf("https://example.org/one"), drafts.draft.value.batchLinks);assertEquals("", drafts.draft.value.batchEntry) }
            compose.onNodeWithTag("batch_paste_icon").performClick()
            compose.runOnIdle { assertEquals(1, drafts.draft.value.batchLinks.size) }
            compose.onNodeWithTag("batch_input").performTextReplacement("https://example.org/pending")
            compose.onNodeWithTag("batch_clear_icon").performClick()
            compose.runOnIdle { assertEquals("", drafts.draft.value.batchEntry);assertEquals(1, drafts.draft.value.batchLinks.size) }
            clip("https://example.org/two\nhttps://example.org/three")
            compose.onNodeWithTag("batch_paste").performClick()
            compose.runOnIdle { assertEquals(1, drafts.draft.value.batchLinks.size);assertTrue(drafts.draft.value.batchEntry.contains("three")) }
            compose.onNodeWithTag("batch_smart_mode").performClick()
            compose.onNodeWithTag("batch_paste_icon").performClick()
            compose.runOnIdle { assertEquals(3, drafts.draft.value.batchLinks.size);assertEquals("", drafts.draft.value.batchText) }
            clip("invalid text")
            compose.onNodeWithTag("batch_paste_icon").performClick()
            compose.onNodeWithTag("batch_input").assertTextContains("invalid text")
            compose.runOnIdle { assertEquals(3, drafts.draft.value.batchLinks.size) }
            compose.onNodeWithTag("batch_clear_icon").performClick()
            scenario.onActivity { assertFalse(ViewModelProvider(it)[MainViewModel::class.java].batchDownloads.queue.running.value) }
            assertEquals(tasksBefore, repository.tasks.value.map { it.id })
            drafts.awaitSaved()
            compose.waitForIdle()
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "batch-clipboard.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } finally { scenario.close() }
    }
}
