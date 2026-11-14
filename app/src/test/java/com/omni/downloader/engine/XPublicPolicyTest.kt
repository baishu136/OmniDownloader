package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class XPublicPolicyTest {
    private val id = "2119233448888217846"
    @Test fun emptyResponseIsUnconfirmedRatherThanProofOfAnotherPost() {
        val error = runCatching { XPublicPolicy.verifyRoot(id, null, null) }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrors.codeOf(error))
        assertTrue(XPublicPolicy.canTrySecondary(error.message.orEmpty()))
    }
    @Test fun transientEmptyResponseGetsOneBoundedRetry() {
        var calls = 0
        val value = XPublicPolicy.confirmed({ if (++calls == 1) null else id }) { XPublicPolicy.verifyRoot(id, it) }
        assertEquals(id, value); assertEquals(2, calls)
    }
    @Test fun persistentlyMissingIdCannotBeAcceptedOrRetriedForever() {
        var calls = 0
        val error = runCatching { XPublicPolicy.confirmed({ calls++; "" }) { XPublicPolicy.verifyRoot(id, it) } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrors.codeOf(error)); assertEquals(2, calls)
    }
    @Test fun explicitOtherPostStopsImmediatelyAndCannotUseAnAlternateRoute() {
        var calls = 0
        val error = runCatching { XPublicPolicy.confirmed({ calls++; "999" }) { XPublicPolicy.verifyRoot(id, it) } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrors.codeOf(error)); assertEquals(1, calls)
        assertFalse(XPublicPolicy.canTrySecondary(error.message.orEmpty()))
    }
    @Test fun blankStringIdMayUseAnExactNumericIdButAnExplicitConflictMustNot() {
        XPublicPolicy.verifyRoot(id, "", id)
        assertEquals(MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrors.codeOf(runCatching { XPublicPolicy.verifyRoot(id, "999", id) }.exceptionOrNull()!!))
    }
    @Test fun upstreamEmptySyndicationFailureCanUseTheIndependentPublicTransport() {
        assertTrue(XPublicPolicy.shouldTryPublic("Syndication endpoint returned empty JSON response"))
        assertTrue(XPublicPolicy.shouldTryPublic("[MEDIA_UNCONFIRMED] no response"))
        assertTrue(XPublicPolicy.shouldTryPublic("Unable to extract media data"))
    }
    @Test fun authLimitsAndMismatchRemainHardStops() {
        for (code in listOf("AUTH_REQUIRED", "CHALLENGE_REQUIRED", "ACCESS_DENIED", "CONTENT_UNAVAILABLE", "RATE_LIMITED", "MEDIA_ID_MISMATCH")) {
            assertFalse(code, XPublicPolicy.canTrySecondary("[$code] refused"))
        }
        assertFalse(XPublicPolicy.shouldTryPublic("[AUTH_REQUIRED] no video could be found; login required"))
    }
    @Test fun networkAndRuntimeFailuresCanTryASeparatelyVerifiedResponse() {
        assertTrue(XPublicPolicy.shouldTryPublic("Connection reset by peer"))
        assertTrue(XPublicPolicy.canTrySecondary("[ENGINE_UNAVAILABLE] JS unavailable"))
    }
}
