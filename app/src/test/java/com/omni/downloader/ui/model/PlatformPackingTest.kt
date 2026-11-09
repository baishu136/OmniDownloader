package com.omni.downloader.ui.model

import org.junit.Assert.*
import org.junit.Test

class PlatformPackingTest {
    @Test fun reservesToggleOnSecondRowInsteadOfOverflowingToThird() {
        assertEquals(5, collapsedPlatformCount(240, List(8) { 70 }, 10, 30))
        assertEquals(6, collapsedPlatformCount(300, List(8) { 70 }, 10, 30))
    }
    @Test fun wideScreensShowEverythingAndLargeTextUsesFewerItems() {
        assertEquals(8, collapsedPlatformCount(640, List(8) { 70 }, 10, 30))
        assertEquals(1, collapsedPlatformCount(100, List(8) { 70 }, 10, 30))
        assertEquals(0, collapsedPlatformCount(0, List(8) { 70 }, 10, 30))
    }
}
