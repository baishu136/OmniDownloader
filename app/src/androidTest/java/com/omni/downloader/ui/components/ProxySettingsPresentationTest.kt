package com.omni.downloader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.ui.screens.SettingsScreen
import com.omni.downloader.ui.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProxySettingsPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun malformedTestInputDoesNotSaveOrStartAProbe() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        instrument.runOnMainSync { model = MainViewModel(instrument.targetContext.applicationContext as android.app.Application); store.put("proxy-qa", model) }
        val saved = model.proxyUrl.value
        try {
            compose.setContent { MaterialTheme { SettingsScreen(model) } }
            compose.onNodeWithTag("settings_page_ROOT").performScrollToNode(hasTestTag("settings_category_NETWORK"))
            compose.onNodeWithTag("settings_category_NETWORK").performClick()
            compose.onNodeWithTag("proxy_input").performTextReplacement("http://bad proxy:7890")
            compose.onNodeWithTag("proxy_test").performScrollTo().performClick()
            compose.onNodeWithText("代理地址中不能包含空格").assertIsDisplayed()
            compose.onNodeWithTag("proxy_test").assertIsEnabled()
            compose.runOnIdle { assertEquals(saved, model.proxyUrl.value) }
            savePresentationScreenshot("proxy-validation.png")
        } finally { instrument.runOnMainSync { store.clear() } }
    }

    @Test fun blankInputTestsDirectConnectionAndClosesTlsOffMainThread() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        instrument.runOnMainSync { model = MainViewModel(instrument.targetContext.applicationContext as android.app.Application); store.put("proxy-direct-qa", model) }
        val saved = model.proxyUrl.value
        try {
            compose.setContent { MaterialTheme { SettingsScreen(model) } }
            compose.onNodeWithTag("settings_page_ROOT").performScrollToNode(hasTestTag("settings_category_NETWORK"))
            compose.onNodeWithTag("settings_category_NETWORK").performClick()
            compose.onNodeWithTag("proxy_input").performTextReplacement("")
            compose.onNodeWithTag("proxy_test").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("proxy_result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("proxy_test").assertIsEnabled()
            compose.onNodeWithTag("proxy_result").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(saved, model.proxyUrl.value) }
            savePresentationScreenshot("proxy-direct-no-crash.png")
        } finally { instrument.runOnMainSync { store.clear() } }
    }
}
