package com.omni.downloader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

internal const val SPLASH_HOLD_MS = 250L
internal const val SPLASH_EXIT_MS = 240L
internal const val SPLASH_CONTENT_SCALE = 0.95f
internal const val SPLASH_ICON_END_SCALE = 0.85f

/** Set the initial transforms beneath the opaque launch overlay, before rendering. */
internal fun prepareSplashTransition(content: View, icon: View?) {
    content.alpha = 1f
    // Keep the View's default center pivot so it follows later size changes.
    content.scaleX = SPLASH_CONTENT_SCALE
    content.scaleY = SPLASH_CONTENT_SCALE
    icon?.apply {
        scaleX = 1f
        scaleY = 1f
    }
}

/** One clock fades the full-size overlay and cross-scales the icon and page. */
internal fun startSplashTransition(
    content: View,
    splash: View,
    icon: View?,
    animationsEnabled: Boolean,
    removeSplash: () -> Unit
): ValueAnimator? {
    var removed = false
    fun finish() {
        content.alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
        icon?.scaleX = 1f
        icon?.scaleY = 1f
        splash.alpha = 0f
        if (!removed) {
            removed = true
            removeSplash()
        }
    }
    if (!animationsEnabled) {
        finish()
        return null
    }
    prepareSplashTransition(content, icon)
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SPLASH_EXIT_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            val progress = it.animatedValue as Float
            val scaleProgress = progress * progress * (3f - 2f * progress)
            val pageScale = SPLASH_CONTENT_SCALE + (1f - SPLASH_CONTENT_SCALE) * scaleProgress
            val iconScale = 1f + (SPLASH_ICON_END_SCALE - 1f) * scaleProgress
            content.scaleX = pageScale
            content.scaleY = pageScale
            icon?.scaleX = iconScale
            icon?.scaleY = iconScale
            splash.alpha = 1f - progress
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) = finish()
            override fun onAnimationEnd(animation: Animator) = finish()
        })
        start()
    }
}
