package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.omni.downloader.data.model.ImagePost
import com.omni.downloader.data.model.ImageResource
import com.omni.downloader.ui.screens.ImagePostGrid
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageResultsLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val post = ImagePost("Pinterest", "https://www.pinterest.com/pin/123/", "123", "作品标题",
        resources = List(30) { ImageResource("p$it", "", 1200, 1500, original = true) })

    @Test fun headerScrollsWithImagesAndSelectionSurvivesScrolling() {
        var chosen = post.resources.map { it.id }.toSet()
        var preview = -1
        compose.setContent {
            var selected by remember { mutableStateOf(chosen) }
            MaterialTheme {
                ImagePostGrid(post, selected, { selected = it; chosen = it }, { preview = it }, {},
                    imageModel = { android.R.drawable.ic_menu_gallery }) {
                    Text("图文下载")
                    ImageInputPanel("link", false, "Download/OmniDownloader", {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("图文下载").assertIsDisplayed()
        compose.onNodeWithText("平台原图").assertDoesNotExist()
        compose.onNodeWithText("来源待确认").assertDoesNotExist()
        compose.onNodeWithTag("image_info_0", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(29, chosen.size); assertFalse("p0" in chosen) }
        compose.onNodeWithTag("images_grid").performScrollToNode(hasTestTag("image_card_29"))
        compose.onNodeWithText("图文下载").assertDoesNotExist()
        compose.onNodeWithTag("image_preview_29", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(29, preview); assertEquals(29, chosen.size) }
        compose.onNodeWithTag("images_grid").performScrollToIndex(0)
        compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 30 / 29")
    }

    @Test fun actionsStayPinnedAndKeepLiveSelectionAtEndOfList() {
        var chosen = post.resources.map { it.id }.toSet()
        compose.setContent {
            var selected by remember { mutableStateOf(chosen) }
            MaterialTheme {
                ImagePostGrid(post, selected, { selected = it; chosen = it }, {}, {},
                    imageModel = { android.R.drawable.ic_menu_gallery }) { Text("图文下载") }
            }
        }
        compose.onNodeWithTag("images_grid").performScrollToNode(hasTestTag("image_card_15"))
        val middleTop = compose.onNodeWithTag("images_actions").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("images_grid").performScrollToNode(hasTestTag("image_card_29"))
        compose.onNodeWithText("图文下载").assertDoesNotExist()
        compose.onAllNodesWithTag("images_actions").assertCountEquals(1)
        for (label in listOf("全选", "清空选择", "打包 ZIP", "保存")) compose.onNodeWithText(label).assertIsDisplayed()
        val endTop = compose.onNodeWithTag("images_actions").fetchSemanticsNode().boundsInRoot.top
        assertEquals(middleTop, endTop, 1f)
        compose.onNodeWithText("清空选择").performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        compose.onNodeWithTag("images_save").assertIsNotEnabled()
        compose.onNodeWithTag("images_zip").assertIsNotEnabled()
        compose.onNodeWithTag("image_card_29").assertIsDisplayed()
        compose.onNodeWithText("全选").performClick()
        compose.runOnIdle { assertEquals(30, chosen.size) }
        compose.onNodeWithTag("images_save").assertIsEnabled()
    }

    @Test fun imagePreviewsButLetterboxInformationAndCheckboxToggleExactlyOnce() {
        var chosen = setOf("p0")
        var previews = 0
        compose.setContent {
            var selected by remember { mutableStateOf(chosen) }
            MaterialTheme {
                ImagePostGrid(post, selected, { selected = it; chosen = it }, { previews++ }, {},
                    imageModel = { android.R.drawable.ic_menu_gallery }) { Text("图文下载") }
            }
        }
        compose.onNodeWithTag("image_info_0", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(0, previews) }
        compose.onNodeWithTag("image_checkbox_0", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(setOf("p0"), chosen); assertEquals(0, previews) }
        compose.onNodeWithTag("image_area_0", useUnmergedTree = true).performTouchInput { click(Offset(1f, centerY)) }
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(0, previews) }
        compose.onNodeWithTag("image_preview_0", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(1, previews) }
        compose.onNodeWithTag("image_card_0").assertIsOff()
    }

    @Test fun saveAndZipKeepTheirActionsAfterReorderingAndDisableForEmptySelection() {
        val saves = mutableListOf<Pair<Boolean, Set<String>>>()
        compose.setContent {
            var selected by remember { mutableStateOf(post.resources.map { it.id }.toSet()) }
            MaterialTheme {
                ImagePostGrid(post, selected, { selected = it }, {}, { saves.add(it to selected) },
                    imageModel = { android.R.drawable.ic_menu_gallery }) { Text("图文下载") }
            }
        }
        val zip = compose.onNodeWithTag("images_zip").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("images_save").fetchSemanticsNode().boundsInRoot
        assertTrue(zip.right <= save.left)
        compose.onNodeWithTag("images_zip").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, false), saves.map { it.first })
            assertTrue(saves.all { it.second.size == 30 })
        }
        compose.onNodeWithText("清空选择").performClick()
        compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 30 / 0")
        compose.onNodeWithTag("images_save").assertIsNotEnabled()
        compose.onNodeWithTag("images_zip").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("images_selection_count").assertTextEquals("已选 30 / 30")
    }

    @Test fun narrowLargeFontActionsWrapAndStayAccessible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme { Column(Modifier.width(320.dp)) {
                    ImagePostGrid(post, setOf("p0"), {}, {}, {}, imageModel = { android.R.drawable.ic_menu_gallery }) {
                        Text("图文下载")
                        ImageInputPanel("link", false, "Download/OmniDownloader", {}, {}, {}, {}, {})
                    }
                } }
            }
        }
        compose.onNodeWithTag("images_save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("images_zip").assertIsDisplayed()
        val clear = compose.onNodeWithText("清空选择").fetchSemanticsNode().boundsInRoot
        val zip = compose.onNodeWithTag("images_zip").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("images_save").fetchSemanticsNode().boundsInRoot
        assertTrue(clear.bottom <= zip.top || clear.right <= zip.left)
        assertTrue(zip.right <= save.left)
        compose.onNodeWithTag("images_grid").performScrollToNode(hasTestTag("image_card_29"))
        for (label in listOf("全选", "清空选择", "打包 ZIP", "保存")) compose.onNodeWithText(label).assertIsDisplayed()
    }
}
