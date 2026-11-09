package com.omni.downloader.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeExpansionLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun mainHomeRemembersRelayChoiceAfterActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldExpanded = settings.homeRelayExpanded.value
        val oldPrompted = settings.hasPromptedBilibiliLogin.value
        val oldLanguage = settings.appLanguage.value
        val added = mutableListOf<String>()
        try {
            settings.setHasPromptedBilibiliLogin(true)
            settings.setAppLanguage("zh-CN")
            settings.setHomeRelayExpanded(false)
            if (settings.relaySites.value.size < 4) repeat(4 - settings.relaySites.value.size) { index ->
                val id = "presentation-qa-$index"
                added += id
                settings.addRelaySite(com.omni.downloader.data.model.RelaySite(id = id, name = "QA $index", url = "https://example.com/presentation-qa/$index"))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("home_platforms_toggle").assertIsDisplayed()
                savePresentationScreenshot("home-collapsed.png")
                compose.onNodeWithTag("home_platforms_toggle").performClick()
                compose.onNodeWithText("Pixiv").assertIsDisplayed()
                savePresentationScreenshot("home-expanded.png")
                val expand = "展开全部 ${settings.relaySites.value.size} 个中转网站"
                compose.onNodeWithTag("home_list").performScrollToNode(hasText(expand))
                compose.onNodeWithText(expand).performClick()
                compose.runOnIdle { assertTrue(settings.homeRelayExpanded.value) }
                scenario.recreate()
                compose.onNodeWithTag("home_list").performScrollToNode(hasText("收起部分中转网站"))
                compose.onNodeWithText("收起部分中转网站").assertIsDisplayed()
                savePresentationScreenshot("home-relays-restored.png")
                compose.onNodeWithText("收起部分中转网站").performClick()
                compose.runOnIdle { assertFalse(settings.homeRelayExpanded.value) }
                scenario.recreate()
                compose.onNodeWithTag("home_list").performScrollToNode(hasText(expand))
                compose.onNodeWithText(expand).assertIsDisplayed()
            }
        } finally {
            added.forEach(settings::removeRelaySite)
            settings.setHomeRelayExpanded(oldExpanded)
            settings.setHasPromptedBilibiliLogin(oldPrompted)
            settings.setAppLanguage(oldLanguage)
        }
    }
}
