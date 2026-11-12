package com.omni.downloader

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.engine.StorageExporter
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UnifiedHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun imagesUseHomeInputAndStorageControlsLiveInSettings(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drafts = InputDraftRepository.getInstance(context);drafts.awaitLoaded();drafts.update { InputDraft() }
        val settings = SettingsRepository.getInstance(context)
        settings.setHasPromptedBilibiliLogin(true);settings.setAppLanguage("zh-CN");settings.setThemeMode("light")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithTag("images_fab").assertDoesNotExist()
            compose.onNodeWithTag("images_link_input").assertDoesNotExist()
            compose.onNodeWithTag("home_link_input").performTextReplacement("https://www.pixiv.net/artworks/123")
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                assertEquals(vm.inputUrl.value, vm.imageInput.value)
                vm.imagePost.value = ImagePost("Pixiv", vm.inputUrl.value, "123", "Unified image result", resources = listOf(ImageResource("one", "android.resource://com.omni.downloader/${android.R.drawable.ic_menu_gallery}")))
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("images_save"))
            compose.onNodeWithTag("images_save").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("images_zip").assertIsDisplayed()
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_link_input"))
            compose.onNodeWithTag("home_link_input").performTextReplacement("https://example.org/video")
            scenario.onActivity { assertNull(ViewModelProvider(it)[MainViewModel::class.java].imagePost.value) }
            compose.onNodeWithContentDescription("设置", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("settings_category_DOWNLOADS").performClick()
            compose.onNodeWithTag("settings_page_DOWNLOADS").performScrollToNode(hasTestTag("settings_image_path"))
            compose.onNodeWithText("选择图文文件夹").assertIsDisplayed()
            val root = File(context.getExternalFilesDir(null), "unified-storage-test").apply { mkdirs() }
            val imageDir = File(root, "images").apply { mkdirs() };val videoDir = File(root, "videos").apply { mkdirs() }
            val source = File(root, "test.png")
            val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val old = settings.downloadPath.value
            try {
                settings.setDownloadPath(videoDir.path)
                val image = File(StorageExporter.export(context, source, imageDir.path))
                val video = File(StorageExporter.export(context, source))
                assertEquals(imageDir.canonicalFile, image.parentFile!!.canonicalFile)
                assertEquals(videoDir.canonicalFile, video.parentFile!!.canonicalFile)
                image.delete();video.delete();source.delete();imageDir.delete();videoDir.delete();root.delete()
            } finally { settings.setDownloadPath(old) }
        } finally { scenario.close() }
    }
}
