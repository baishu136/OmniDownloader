package com.omni.downloader.ui

import android.view.View
import android.view.ViewTreeObserver
import android.os.Build

/** Queue optional work after an actual draw, including when pre-draw is blocked by the splash. */
internal fun afterFirstDraw(view: View, action: () -> Unit): () -> Unit {
    var cancelled = false
    var queued = false
    lateinit var listener: ViewTreeObserver.OnDrawListener
    val work = Runnable {
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        if (!cancelled && view.isAttachedToWindow) action()
    }
    listener = ViewTreeObserver.OnDrawListener {
        if (!queued && !cancelled) {
            queued = true
            // Removing a draw listener during onDraw is forbidden. Post to the next loop.
            view.post(work)
        }
    }
    view.viewTreeObserver.addOnDrawListener(listener)
    view.invalidate()
    return {
        cancelled = true
        view.removeCallbacks(work)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
    }
}

/** Start animation clocks only after the prepared frame reaches the render pipeline. */
internal fun afterCommittedFrame(view: View, action: () -> Unit): () -> Unit {
    var cancelled = false
    val work = Runnable { if (!cancelled && view.isAttachedToWindow) action() }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !view.isHardwareAccelerated) {
        val cancelDraw = afterFirstDraw(view) { view.postOnAnimation(work) }
        return {
            cancelled = true
            cancelDraw()
            view.removeCallbacks(work)
        }
    }
    val observer = view.viewTreeObserver
    val committed = Runnable { view.postOnAnimation(work) }
    observer.registerFrameCommitCallback(committed)
    view.invalidate()
    return {
        cancelled = true
        view.removeCallbacks(work)
        if (observer.isAlive) observer.unregisterFrameCommitCallback(committed)
    }
}
