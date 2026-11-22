package com.omni.downloader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.engine.XProfileInput
import com.omni.downloader.ui.MainActivity
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class XProfileUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pageUpdatesKeepDeselectionAndStoppedResultsCanContinue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsRepository.getInstance(context).setHasPromptedBilibiliLogin(true)
        fun image(id: String) = ImageResource("101:$id","https://pbs.twimg.com/media/$id?format=jpg&name=orig",2048,3072,true)
        val first = ImagePost("X (Twitter)","https://x.com/hazzi_808","x-profile-42","@hazzi_808 的图片","hazzi_808",
            listOf(image("one"),image("two")),"x_profile","42","a",false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: MainViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java];model.updateInputUrl(first.url);model.imagePost.value = first }
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("image_card_0"))
            // The fitted image opens preview; the lower information area toggles selection.
            compose.onNodeWithTag("image_card_0").performTouchInput { click(androidx.compose.ui.geometry.Offset(width-4f,height-4f)) }
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("images_selection_count"))
            compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 1 / 2")
            scenario.onActivity { model.imagePost.value = XProfileInput.merge(first,first.copy(resources=listOf(image("three")),profileCursor="b")) }
            compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 2 / 3")
            compose.onNodeWithTag("x_profile_continue").assertIsDisplayed()
            scenario.onActivity { model.imageBusy.value = true }
            compose.onNodeWithTag("x_profile_stop").performClick()
            compose.onNodeWithTag("x_profile_continue").assertIsDisplayed()
            scenario.onActivity {
                assertEquals(3,model.imagePost.value!!.resources.size)
                assertFalse(model.imagePost.value!!.profileComplete)
                assertEquals("https://x.com/hazzi_808/status/101",XProfileInput.posts(model.imagePost.value!!).single().url)
            }
        } finally { scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].imagePost.value = null };scenario.close() }
    }
}
