package com.omni.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.omni.downloader.ui.screens.HomePlatformChips
import com.omni.downloader.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeAndPlatformMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeInterpolatesAndRapidReverseKeepsContentState() {
        val dark = mutableStateOf(false)
        val markers = mutableSetOf<Any>()
        var background = Color.Unspecified
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OmniDownloaderTheme(darkTheme = dark.value) {
                val marker = remember { Any() }
                val color = MaterialTheme.colorScheme.background
                SideEffect { background = color; markers += marker }
                Box(Modifier.fillMaxSize().background(color).testTag("theme_surface"))
            }
        }
        compose.runOnIdle { assertEquals(LightBackground, background); dark.value = true }
        compose.mainClock.advanceTimeBy(130)
        compose.runOnIdle {
            assertNotEquals(LightBackground, background); assertNotEquals(DarkBackground, background)
            assertTrue(background.luminance() in DarkBackground.luminance()..LightBackground.luminance())
            dark.value = false
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(LightBackground, background); assertEquals(1, markers.size) }
        compose.runOnIdle { dark.value = true }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(DarkBackground, background); assertEquals(1, markers.size) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        savePresentationScreenshot("theme-dark.png")
    }

    @Test fun narrowPlatformToggleStaysOnSecondRowAndExpansionChangesHeightSmoothly() {
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { HomePlatformChips() } } }
        val before = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("home_platforms_toggle").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("哔哩哔哩").fetchSemanticsNode().boundsInRoot
        assertTrue(toggle.top > first.top)
        assertTrue(toggle.right <= before.right + 1)
        savePresentationScreenshot("platforms-narrow-collapsed.png")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.mainClock.advanceTimeBy(100)
        val midway = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(500)
        val end = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot.height
        assertTrue(midway > before.height); assertTrue(midway < end)
        compose.onNodeWithText("Pixiv").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        savePresentationScreenshot("platforms-narrow-expanded.png")
    }

    @Test fun wideLayoutShowsAllNamedPlatformsWithoutAnUnnecessaryThirdRow() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.8f, 1f)) {
                MaterialTheme { HomePlatformChips() }
            }
        }
        compose.onNodeWithText("Pixiv").assertIsDisplayed()
        compose.onNodeWithText("小黑盒").assertIsDisplayed()
        compose.onNodeWithTag("home_platforms_toggle").assertDoesNotExist()
        savePresentationScreenshot("platforms-wide.png")
    }
}
