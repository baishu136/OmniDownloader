package com.omni.downloader.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ImageViewerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchesImagesAndClosesWithinApplication() {
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible.value) ImageViewer(2, model = { android.R.drawable.ic_menu_gallery }, onDismiss = { visible.value = false }) } }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("下一张").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("上一张").performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
}
