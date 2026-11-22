package com.omni.downloader.ui

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SplashTransitionTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()

    @Test fun crossScalesAndFadesWithOneClock() {
        instrument.runOnMainSync {
            val content = View(instrument.targetContext).apply { layout(0, 0, 100, 200) }
            val overlay = View(instrument.targetContext)
            val icon = View(instrument.targetContext)
            var removed = 0
            val animation = startSplashTransition(content, overlay, icon, true) { removed++ }!!
            assertEquals(240L, animation.duration)
            assertEquals(250L, SPLASH_HOLD_MS)
            assertEquals(50f, content.pivotX)
            assertEquals(100f, content.pivotY)
            animation.currentPlayTime = 60L
            assertEquals(.75f, overlay.alpha, .001f)
            assertTrue(content.scaleX > SPLASH_CONTENT_SCALE && content.scaleX < 1f)
            assertTrue(icon.scaleX < 1f && icon.scaleX > SPLASH_ICON_END_SCALE)
            animation.currentPlayTime = 120L
            assertEquals(.5f, overlay.alpha, .001f)
            assertEquals(.975f, content.scaleX, .0001f)
            assertEquals(.925f, icon.scaleX, .0001f)
            assertEquals(1f, content.alpha)
            assertEquals(0f, content.translationY)
            assertEquals(0f, icon.translationY)
            assertEquals(0, removed)
            animation.end()
            assertEquals(1f, content.scaleX)
            assertEquals(1f, content.scaleY)
            assertEquals(1f, icon.scaleX)
            assertEquals(0f, overlay.alpha)
            assertEquals(1, removed)
        }
    }

    @Test fun interruptionResetsAllTransformsAndRemovesOverlayOnce() {
        instrument.runOnMainSync {
            val content = View(instrument.targetContext)
            val icon = View(instrument.targetContext)
            val overlay = View(instrument.targetContext)
            var removed = 0
            val animation = startSplashTransition(content, overlay, icon, true) { removed++ }!!
            animation.currentPlayTime = 90L
            animation.cancel()
            animation.end()
            assertEquals(1f, content.scaleX)
            assertEquals(1f, content.scaleY)
            assertEquals(1f, icon.scaleX)
            assertEquals(1f, icon.scaleY)
            assertEquals(0f, overlay.alpha)
            assertEquals(1, removed)
        }
    }

    @Test fun disabledAnimationsRestoreFullSizeImmediately() {
        instrument.runOnMainSync {
            val content = View(instrument.targetContext).apply { scaleX = .8f; scaleY = .8f }
            val icon = View(instrument.targetContext).apply { scaleX = .9f; scaleY = .9f }
            var removed = 0
            assertNull(startSplashTransition(content, View(instrument.targetContext), icon, false) { removed++ })
            assertEquals(1f, content.scaleX)
            assertEquals(1f, content.scaleY)
            assertEquals(1f, icon.scaleX)
            assertEquals(1, removed)
        }
    }

    @Test fun warmLaunchWithoutIconCompletesThePageTransition() {
        instrument.runOnMainSync {
            val content = View(instrument.targetContext)
            var removed = 0
            val animation = startSplashTransition(content, View(instrument.targetContext), null, true) { removed++ }!!
            animation.currentPlayTime = 120L
            assertTrue(content.scaleX < 1f)
            animation.end()
            assertEquals(1f, content.scaleX)
            assertEquals(1, removed)
        }
    }
}
