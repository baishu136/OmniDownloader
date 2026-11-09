package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageInputPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pasteClearAndParseUseCurrentInput() {
        var parsed = ""
        compose.setContent {
            var input by remember { mutableStateOf("") }
            MaterialTheme { Column { ImageInputPanel(input, false, "默认下载目录", { input = it },
                { input = "https://x.com/u/status/123" }, { parsed = input }, {}, {}) } }
        }
        compose.onNodeWithTag("images_analyze").assertIsNotEnabled()
        compose.onNodeWithTag("images_paste").performClick()
        compose.onNodeWithTag("images_link_input").assertTextContains("https://x.com/u/status/123")
        compose.onNodeWithTag("images_analyze").performClick()
        compose.runOnIdle { assertEquals("https://x.com/u/status/123", parsed) }
        compose.onNodeWithTag("images_clear").performClick()
        compose.onNodeWithTag("images_paste").assertIsDisplayed()
        compose.onNodeWithTag("images_analyze").assertIsNotEnabled()
    }

    @Test fun pathMenuChoosesAndResetsWithoutAnalyzing() {
        var selected = 0; var reset = 0; var parsed = 0
        compose.setContent { MaterialTheme { Column { ImageInputPanel("link", false, "Pictures", {}, {}, { parsed++ }, { selected++ }, { reset++ }) } } }
        compose.onNodeWithTag("images_save_path").performClick()
        compose.onNodeWithText("选择文件夹").performClick()
        compose.onNodeWithTag("images_save_path").performClick()
        compose.onNodeWithText("恢复默认目录").performClick()
        compose.runOnIdle { assertEquals(1, selected); assertEquals(1, reset); assertEquals(0, parsed) }
    }

    @Test fun busyCanBeClearedAndNarrowLargeFontControlsStayOrdered() {
        var cleared = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme { Column(Modifier.width(320.dp)) {
                    ImageInputPanel("current", true, "a-very-long-selected-folder-name", { cleared = it.isEmpty() }, {}, {}, {}, {})
                } }
            }
        }
        compose.onNodeWithTag("images_analyze").assertIsNotEnabled()
        val path = compose.onNodeWithTag("images_save_path").fetchSemanticsNode().boundsInRoot
        val analyze = compose.onNodeWithTag("images_analyze").fetchSemanticsNode().boundsInRoot
        assertTrue(path.right <= analyze.left)
        compose.onNodeWithTag("images_clear").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }
}
