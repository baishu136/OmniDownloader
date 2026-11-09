package com.omni.downloader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.ui.localization.ZhHansStrings
import com.omni.downloader.ui.screens.HomePlatformChips
import com.omni.downloader.ui.screens.SettingsScreen
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DownloadPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val format = FormatOption("http-720", "720p codec details", width = 720, height = 1280, approximateSize = "12 MiB", acodec = "aac", note = "not shown")

    @Test fun platformListExpandsToPixivAndHeyboxAndRestoresState() {
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { HomePlatformChips() } }
        compose.onNodeWithText("Pixiv").assertDoesNotExist()
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.onNodeWithText("Pixiv").assertIsDisplayed()
        compose.onNodeWithText("小黑盒").assertIsDisplayed()
        compose.onNodeWithText("Instagram").assertIsDisplayed()
        savePresentationScreenshot("platforms-expanded.png")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Pixiv").assertIsDisplayed()
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.onNodeWithText("Pixiv").assertDoesNotExist()
    }

    @Test fun muxedVideoKeepsAudioExportAndRowsHaveOnlyThreeFields() {
        var audio = AudioFormat.MP3
        compose.setContent {
            var type by remember { mutableStateOf(DownloadType.VIDEO_WITH_AUDIO) }
            var pickedAudio by remember { mutableStateOf(AudioFormat.MP3) }
            MaterialTheme { FormatSelectorSheet(
                metadata = VideoMetadata("https://example.test/video", "sample", availableVideoFormats = listOf(format)),
                selectedType = type, selectedVideoFormat = format, selectedAudioFormat = pickedAudio,
                onSelectType = { type = it }, onSelectVideoFormat = {}, onSelectAudioFormat = { pickedAudio = it; audio = it },
                onConfirmDownload = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("最高源画质（包含 4K / 8K）").assertDoesNotExist()
        compose.onNodeWithText("指定格式不可用时会报错；不会自动改下较低画质。").assertDoesNotExist()
        compose.onNodeWithText("720×1280 · MP4 · 12 MiB").assertIsDisplayed()
        compose.onNodeWithText("not shown").assertDoesNotExist()
        savePresentationScreenshot("download-video.png")
        compose.onNodeWithText(ZhHansStrings.tabAudio).performClick()
        compose.onNodeWithText(AudioFormat.FLAC.label).performScrollTo().performClick()
        savePresentationScreenshot("download-audio.png")
        compose.runOnIdle { assertEquals(AudioFormat.FLAC, audio) }
    }

    @Test fun ugoiraKeepsThreeExportChoicesWithoutAnAudioTab() {
        val formats = listOf(format.copy(formatId = "ugoira_mp4", resolutionLabel = "Ugoira MP4 (H.264)", acodec = "", approximateSize = ""),
            format.copy(formatId = "ugoira_gif", resolutionLabel = "Ugoira GIF", ext = "gif", acodec = "", approximateSize = ""),
            format.copy(formatId = "ugoira_zip", resolutionLabel = "原始帧资源 (ZIP + JSON)", ext = "zip", acodec = "", approximateSize = ""))
        var chosen = ""
        compose.setContent { MaterialTheme { FormatSelectorSheet(
            metadata = VideoMetadata("https://www.pixiv.net/artworks/123", "sample", siteName = "Pixiv", availableVideoFormats = formats),
            selectedType = DownloadType.VIDEO_WITH_AUDIO, selectedVideoFormat = formats[0], selectedAudioFormat = AudioFormat.MP3,
            onSelectType = {}, onSelectVideoFormat = { chosen = it.formatId }, onSelectAudioFormat = {},
            onConfirmDownload = {}, onDismiss = {}) } }
        compose.onNodeWithText(ZhHansStrings.tabAudio).assertDoesNotExist()
        compose.onNodeWithText("720×1280 · ZIP + JSON · 大小待确认").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("ugoira_zip", chosen) }
        savePresentationScreenshot("download-ugoira.png")
    }

    @Test fun accountsKeepGenericYoutubeImportAndDedicatedBilibiliEntry() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        instrument.runOnMainSync {
            model = MainViewModel(instrument.targetContext.applicationContext as android.app.Application)
            store.put("qa-presentation", model)
            model.requestedSessionPlatform.value = "YouTube"
        }
        try {
            compose.setContent { MaterialTheme { SettingsScreen(model) } }
            compose.onNodeWithTag("settings_page_ACCOUNTS").assertIsDisplayed()
            compose.onNodeWithText("YouTube Cookie").assertDoesNotExist()
            compose.onNode(hasText("YouTube") and hasClickAction()).performClick()
            compose.onNodeWithText("哔哩哔哩").assertDoesNotExist()
            savePresentationScreenshot("accounts-platform-menu.png")
            compose.onNodeWithText("Pixiv").assertIsDisplayed().performClick()
            compose.onNodeWithText(ZhHansStrings.sessionImport).assertIsDisplayed()
            compose.onNodeWithText(ZhHansStrings.bilibiliCookieSection).performScrollTo().assertIsDisplayed()
        } finally { instrument.runOnMainSync { store.clear() } }
    }
}

internal fun savePresentationScreenshot(name: String) {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val folder = java.io.File(instrument.targetContext.cacheDir, "presentation-qa").apply { mkdirs() }
    instrument.waitForIdleSync()
    instrument.uiAutomation.waitForIdle(200, 3000)
    val bitmap = instrument.uiAutomation.takeScreenshot() ?: error("Could not capture test screen")
    java.io.File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
