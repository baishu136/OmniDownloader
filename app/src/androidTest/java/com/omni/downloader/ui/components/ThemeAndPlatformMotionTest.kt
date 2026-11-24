package com.omni.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.omni.downloader.ui.screens.HomePlatformChips
import com.omni.downloader.ui.screens.SettingsPage
import com.omni.downloader.ui.screens.SettingsToolbar
import com.omni.downloader.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeAndPlatformMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolbarAndPageShareEveryThemeFrameIncludingReversal() {
        val dark = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OmniDownloaderTheme(darkTheme = dark.value) {
                Column {
                    Box(Modifier.testTag("toolbar_frame")) {
                        SettingsToolbar(SettingsPage.ROOT, onBack = {})
                    }
                    Box(Modifier.fillMaxWidth().height(120.dp)
                        .background(MaterialTheme.colorScheme.background).testTag("page_frame"))
                }
            }
        }
        fun assertSameFrame() {
            val toolbar = compose.onNodeWithTag("toolbar_frame").captureToImage().toPixelMap()
            val page = compose.onNodeWithTag("page_frame").captureToImage().toPixelMap()
            val actual = toolbar[toolbar.width - 4, toolbar.height / 2]
            val expected = page[page.width - 4, page.height / 2]
            assertEquals("toolbar red", expected.red, actual.red, .012f)
            assertEquals("toolbar green", expected.green, actual.green, .012f)
            assertEquals("toolbar blue", expected.blue, actual.blue, .012f)
        }
        assertSameFrame()
        compose.runOnIdle { dark.value = true }
        repeat(5) {
            compose.mainClock.advanceTimeBy(48); assertSameFrame()
            if (it == 2) savePresentationScreenshot("theme-toolbar-midway.png")
        }
        compose.runOnIdle { dark.value = false }
        repeat(8) { compose.mainClock.advanceTimeBy(48); assertSameFrame() }
        compose.mainClock.advanceTimeBy(500)
        assertSameFrame()
    }

    @Test fun themeInterpolatesAndRapidReverseKeepsContentState() {
        val dark = mutableStateOf(false)
        val markers = mutableSetOf<Any>()
        var background = Color.Unspecified
        var primary = Color.Unspecified
        var statusInfo = Color.Unspecified
        var surfaces = emptyList<Color>()
        var lightSurfaces = emptyList<Color>()
        var midwaySurfaces = emptyList<Color>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OmniDownloaderTheme(darkTheme = dark.value) {
                val marker = remember { Any() }
                val color = MaterialTheme.colorScheme.background
                val accent = MaterialTheme.colorScheme.primary
                val info = LocalStatusColors.current.info
                val scheme = MaterialTheme.colorScheme
                val containers = listOf(scheme.surfaceBright, scheme.surfaceDim, scheme.surfaceContainer,
                    scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.surfaceContainerLow,
                    scheme.surfaceContainerLowest)
                SideEffect { background = color; primary = accent; statusInfo = info; surfaces = containers; markers += marker }
                Box(Modifier.fillMaxSize().background(color).testTag("theme_surface"))
            }
        }
        compose.runOnIdle { assertEquals(LightBackground, background); lightSurfaces = surfaces; dark.value = true }
        compose.mainClock.advanceTimeBy(130)
        compose.runOnIdle {
            assertNotEquals(LightBackground, background); assertNotEquals(DarkBackground, background)
            assertEquals(primary, statusInfo)
            midwaySurfaces = surfaces
            assertTrue(surfaces.zip(lightSurfaces).all { (mid, light) -> mid != light })
            assertTrue(background.luminance() in DarkBackground.luminance()..LightBackground.luminance())
            dark.value = false
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(LightBackground, background); assertEquals(primary, statusInfo); assertEquals(1, markers.size) }
        compose.runOnIdle { dark.value = true }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(DarkBackground, background); assertEquals(1, markers.size)
            assertTrue(surfaces.zip(midwaySurfaces).all { (end, mid) -> end != mid })
        }
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
        compose.onNodeWithText("西瓜视频").assertIsDisplayed()
        compose.onNodeWithTag("ytdlp_platforms_open").assertDoesNotExist()
        savePresentationScreenshot("platforms-narrow-collapsed.png")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("home_platforms").performTouchInput {
            click(Offset(toggle.center.x - before.left, toggle.top - before.top - 2f))
        }
        compose.mainClock.advanceTimeBy(100)
        val midway = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot.height
        val expandingToggle = compose.onNodeWithTag("home_platforms_toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(expandingToggle.top > toggle.top)
        compose.mainClock.advanceTimeBy(500)
        val end = compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot.height
        assertTrue(midway > before.height); assertTrue(midway < end)
        compose.onNodeWithText("Reddit").assertIsDisplayed()
        compose.onNodeWithTag("ytdlp_platforms_open").assertIsDisplayed()
        val expandedToggle = compose.onNodeWithTag("home_platforms_toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(expandedToggle.top > toggle.top)
        assertTrue(expandedToggle.right <= before.right + 1)
        assertEvenPlatformRows()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        savePresentationScreenshot("platforms-narrow-expanded.png")
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.onNodeWithTag("ytdlp_platforms_open").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("home_platforms").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun wideLayoutKeepsCatalogAccessibleEvenWhenTheChipsFit() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.8f, 1f)) {
                MaterialTheme { HomePlatformChips() }
            }
        }
        compose.onNodeWithText("Pixiv").assertIsDisplayed()
        compose.onNodeWithText("小黑盒").assertIsDisplayed()
        compose.onNodeWithTag("ytdlp_platforms_open").assertDoesNotExist()
        compose.onNodeWithTag("home_platforms_toggle").performClick()
        compose.onNodeWithTag("ytdlp_platforms_open").assertIsDisplayed()
        savePresentationScreenshot("platforms-wide.png")
    }

    @Test fun largeTextAndRapidReversalLeaveTheToggleInPlace() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                OmniDownloaderTheme(darkTheme = true) { Box(Modifier.width(320.dp)) { HomePlatformChips() } }
            }
        }
        val toggle = compose.onNodeWithTag("home_platforms_toggle")
        val bounds = toggle.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        toggle.performClick()
        compose.mainClock.advanceTimeBy(96)
        toggle.performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("ytdlp_platforms_open").assertDoesNotExist()
        assertEquals(bounds, toggle.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
        toggle.performClick()
        compose.onNodeWithTag("ytdlp_platforms_open").assertIsDisplayed()
        assertEvenPlatformRows()
        savePresentationScreenshot("platforms-dark-large-text.png")
    }

    private fun assertEvenPlatformRows() {
        val chips = compose.onAllNodesWithTag("home_platform_chip").fetchSemanticsNodes().map { it.boundsInRoot }
        val rows = chips.distinctBy { it.top }.sortedBy { it.top }
        assertTrue(rows.size >= 3)
        val heights = chips.map { it.height }
        assertEquals(heights.first(), heights.last(), 1f)
        assertTrue(heights.all { kotlin.math.abs(it - heights.first()) <= 1f })
        val gaps = rows.zipWithNext { earlier, later -> later.top - earlier.bottom }
        assertTrue(gaps.first() > 0f)
        assertTrue("Uneven platform row gaps: $gaps", gaps.all { kotlin.math.abs(it - gaps.first()) <= 1f })
        val toggleHeight = compose.onNodeWithTag("home_platforms_toggle").fetchSemanticsNode().boundsInRoot.height
        assertEquals(heights.first(), toggleHeight, 1f)
    }
}
