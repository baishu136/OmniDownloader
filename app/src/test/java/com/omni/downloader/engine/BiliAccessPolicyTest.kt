package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliAccessPolicyTest {
    @Test fun paidAccessNeedsVerifiedAccountAndFullStreamEvidence() {
        assertEquals("DENIED", BiliAccessPolicy.state(true, false, true, false, 60000, 60000))
        assertEquals("FULL", BiliAccessPolicy.state(true, true, true, false, 60000, 60000))
        assertEquals("PREVIEW", BiliAccessPolicy.state(true, true, true, true, 60000, 60000))
        assertEquals("PREVIEW", BiliAccessPolicy.state(true, true, true, false, 60000, 10000))
    }
    @Test fun unknownDoesNotBecomeFullFromDurationAlone() {
        assertEquals("UNKNOWN", BiliAccessPolicy.state(null, false, true, false, 60000, 60000))
        assertEquals("UNKNOWN", BiliAccessPolicy.state(true, true, false, false, 60000, 60000))
        assertEquals("FULL", BiliAccessPolicy.state(false, false, true, false, 60000, 60000))
    }
}
