package com.omni.downloader.startup

import java.util.concurrent.atomic.AtomicBoolean

/** The minimum launch hold belongs to the first fresh activity in this process. */
internal class LaunchSession {
    private val firstActivity = AtomicBoolean(true)
    fun claimColdLaunch(restoring: Boolean): Boolean = firstActivity.getAndSet(false) && !restoring
}
