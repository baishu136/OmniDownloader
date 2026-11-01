package com.omni.downloader.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SegmentedTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidSelectionAndCountUpdatesKeepOneContinuousIndicator() {
        var selected by mutableIntStateOf(0)
        var count by mutableIntStateOf(0)
        compose.setContent { MaterialTheme { OmniSegmentedTabs(listOf("A ($count)", "B ($count)", "C ($count)"), selected, { selected = it }) } }
        compose.waitForIdle()
        val indicator = compose.onNodeWithTag("segmented-tab-indicator", useUnmergedTree = true)
        val id = indicator.fetchSemanticsNode().id
        val start = indicator.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 2 }
        var previous = start
        repeat(18) { frame ->
            if (frame == 2) compose.runOnIdle { selected = 1 }
            if (frame == 4) compose.runOnIdle { selected = 2 }
            compose.runOnIdle { count++ }
            compose.mainClock.advanceTimeByFrame()
            val node = indicator.fetchSemanticsNode()
            assertEquals("Count updates must not recreate the indicator", id, node.id)
            assertTrue("Indicator moved back toward an old selection", node.boundsInRoot.left + .6f >= previous)
            previous = node.boundsInRoot.left
            compose.onAllNodesWithTag("segmented-tab-indicator", useUnmergedTree = true).assertCountEquals(1)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("C ($count)").assertIsSelected()
        assertTrue(indicator.fetchSemanticsNode().boundsInRoot.left > start)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("B ($count)").performClick().assertIsSelected()
    }
}
