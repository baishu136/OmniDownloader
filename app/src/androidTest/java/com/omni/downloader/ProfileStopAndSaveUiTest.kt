package com.omni.downloader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ProfileStopAndSaveUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun stoppingEitherProfileKeepsPartialImagesAndSaveFollowsActualTaskState() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        SettingsRepository.getInstance(context).setHasPromptedBilibiliLogin(true)
        val repository=TaskRepository.getInstance(context);val scenario=ActivityScenario.launch(MainActivity::class.java)
        val ids=mutableListOf<String>();var worker:Job?=null
        try{
            lateinit var model:MainViewModel
            scenario.onActivity{model=ViewModelProvider(it)[MainViewModel::class.java]}
            for(platform in listOf("X (Twitter)","Pixiv")) {
                val post=if(platform=="Pixiv")ImagePost(platform,"https://www.pixiv.net/users/42","pixiv-user-42","Partial artist",resources=listOf(ImageResource("102:p0","android.resource://android/${android.R.drawable.ic_menu_gallery}",64,64)),route="pixiv_artist",artistUserId="42",artistArtworkIds=listOf("102","101"),artistOffset=1,artistComplete=false)
                else ImagePost(platform,"https://x.com/fixture","x-profile-fixture","Partial X",resources=listOf(ImageResource("123:photo","android.resource://android/${android.R.drawable.ic_menu_gallery}",64,64)),route="x_profile",profileUserId="42",profileCursor="next",profileComplete=false)
                scenario.onActivity{
                    model.updateInputUrl(post.url);model.imagePost.value=post;model.imageBusy.value=true
                    worker=CoroutineScope(SupervisorJob()+Dispatchers.Default).launch{awaitCancellation()}
                    MainViewModel::class.java.getDeclaredField("analysisJob").apply{isAccessible=true}.set(model,worker)
                }
                compose.onNodeWithTag("home_list").performScrollToNode(hasText("停止解析"));compose.onNodeWithText("停止解析").performClick()
                compose.waitUntil(5000){!model.imageBusy.value && worker?.isCancelled==true}
                assertEquals(post,model.imagePost.value)
                compose.onNodeWithText("继续解析").assertExists()
                val id="save-ui-${UUID.randomUUID()}";ids+=id
                val task=DownloadTask(id,post.url,"Controlled pending image",downloadType=DownloadType.IMAGE,imagePlatform=platform,selectedImageIds=listOf(post.resources.first().id))
                repository.addTasks(listOf(task))
                compose.waitUntil(5000){model.imageSaving.value}
                compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("images_save"))
                compose.onNodeWithTag("images_save").assertIsNotEnabled();compose.onNodeWithTag("images_zip").assertIsEnabled()
                compose.onNodeWithText("保存中…").assertExists()
                val before=repository.taskStructure.value.size
                scenario.onActivity{model.downloadImages(post.resources.map{it.id}.toSet(),false)}
                assertEquals(before,repository.taskStructure.value.size)
                compose.onNodeWithTag("images_zip").performClick()
                assertEquals(before,repository.taskStructure.value.size)
                compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("image_preview_0"))
                compose.onNodeWithTag("image_preview_0",useUnmergedTree=true).performClick()
                compose.onNodeWithText("保存此图").assertIsEnabled().performClick()
                assertEquals(before,repository.taskStructure.value.size)
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("images_save"))
                repository.updateTaskProgress(id,50f,"","",TaskStatus.DOWNLOADING)
                compose.onNodeWithTag("images_save").assertIsNotEnabled()
                if(platform=="Pixiv")repository.markFailed(id,"controlled failure") else repository.markCompleted(id,"fixture.png")
                compose.waitUntil(5000){!model.imageSaving.value};compose.onNodeWithTag("images_save").assertIsEnabled()
                repository.removeTask(id)
            }
        }finally{worker?.cancel();ids.forEach{repository.removeTask(it)};scenario.close()}
    }
}
