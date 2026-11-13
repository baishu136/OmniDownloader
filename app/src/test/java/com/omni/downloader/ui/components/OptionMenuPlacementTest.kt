package com.omni.downloader.ui.components

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class OptionMenuPlacementTest {
    @Test fun alignsWithButtonEndAndLeavesEightPixelGap() {
        val p = OptionMenuPlacement.position(IntRect(240, 100, 340, 140), IntSize(400, 800), IntSize(208, 248), 16, 8)
        assertEquals(IntOffset(132, 148), p.offset); assertFalse(p.above)
    }
    @Test fun bottomButtonOpensUpInsteadOfClippingTheFinalOptions() {
        val p = OptionMenuPlacement.position(IntRect(240, 700, 340, 740), IntSize(400, 800), IntSize(208, 248), 16, 8)
        assertEquals(IntOffset(132, 444), p.offset); assertTrue(p.above)
    }
    @Test fun leftAndRightScreenEdgesKeepSafeMargins() {
        assertEquals(16, OptionMenuPlacement.position(IntRect(0, 20, 40, 60), IntSize(400, 800), IntSize(208, 248), 16, 8).offset.x)
        assertEquals(176, OptionMenuPlacement.position(IntRect(380, 20, 420, 60), IntSize(400, 800), IntSize(208, 248), 16, 8).offset.x)
    }
    @Test fun nearlyFullScreenPanelClampsAtSafeTop() {
        val p = OptionMenuPlacement.position(IntRect(0, 150, 40, 180), IntSize(240, 320), IntSize(208, 288), 16, 8)
        assertEquals(IntOffset(16, 16), p.offset)
    }
}
