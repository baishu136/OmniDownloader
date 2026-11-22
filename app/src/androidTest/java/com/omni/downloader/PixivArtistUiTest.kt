package com.omni.downloader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.engine.PixivArtistInput
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PixivArtistUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun artistSelectsAllPagesAndAnimationFormatWithoutSavingTheCover() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        SettingsRepository.getInstance(context).setHasPromptedBilibiliLogin(true)
        val resources=listOf(ImageResource("102:p0","android.resource://android/${android.R.drawable.ic_menu_gallery}",3000,4000,true),
            ImageResource("102:p1","android.resource://android/${android.R.drawable.ic_menu_gallery}",3000,4000,true),
            ImageResource("101:ugoira","android.resource://android/${android.R.drawable.ic_menu_gallery}",101,99,kind="UGOIRA"))
        val post=ImagePost("Pixiv","https://www.pixiv.net/users/42","pixiv-user-42","Artist",resources=resources,route="pixiv_artist",artistUserId="42",artistArtworkIds=listOf("102","101"),artistOffset=2)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model:MainViewModel
            scenario.onActivity { model=ViewModelProvider(it)[MainViewModel::class.java];model.updateInputUrl(post.url);model.imagePost.value=post }
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("pixiv_artist_status"))
            compose.onNodeWithTag("pixiv_artist_status").assertTextContains("2 张原图 · 1 个动图",substring=true)
            compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 3 / 3")
            compose.onNodeWithTag("pixiv_artist_format").performClick();compose.onNodeWithText("MP4").performClick()
            compose.waitUntil(5000){model.artistAnimationFormat.value=="ugoira_mp4"}
            scenario.onActivity {
                val tasks=PixivArtistInput.tasks(model.imagePost.value!!,resources.map { it.id }.toSet(),false,model.artistAnimationFormat.value)
                assertEquals(2,tasks.count { it.downloadType==DownloadType.IMAGE });assertEquals("ugoira_mp4",tasks.single { it.mediaId=="101" }.selectedVideoFormatId)
                assertTrue(tasks.single { it.mediaId=="101" }.selectedImageIds.isEmpty())
            }
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("images_save"));compose.onNodeWithText("清空选择").performClick();compose.onNodeWithTag("images_save").assertIsNotEnabled()
            compose.onNodeWithText("全选").performClick();compose.onNodeWithTag("images_save").assertIsEnabled()
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("image_preview_2"))
            compose.onNodeWithTag("image_preview_2",useUnmergedTree=true).performClick()
            compose.onNodeWithText("下载动图").assertIsDisplayed();compose.onNodeWithText("关闭").performClick()
        } finally {scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].imagePost.value=null };scenario.close()}
    }
}
