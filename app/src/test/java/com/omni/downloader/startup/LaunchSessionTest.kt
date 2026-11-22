package com.omni.downloader.startup

import org.junit.Assert.*
import org.junit.Test

class LaunchSessionTest {
    @Test fun freshProcessGetsOnlyOneMinimumHold() {
        val session = LaunchSession()
        assertTrue(session.claimColdLaunch(false))
        assertFalse(session.claimColdLaunch(false))
    }
    @Test fun processRestorationDoesNotReplayMinimumHold() {
        val session = LaunchSession()
        assertFalse(session.claimColdLaunch(true))
        assertFalse(session.claimColdLaunch(false))
    }
    @Test fun aNewProcessGetsItsOwnLaunchHold() {
        assertTrue(LaunchSession().claimColdLaunch(false))
        assertTrue(LaunchSession().claimColdLaunch(false))
    }
}
