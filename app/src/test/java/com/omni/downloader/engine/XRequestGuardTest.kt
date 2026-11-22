package com.omni.downloader.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class XRequestGuardTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun hostBudgetsAreExactAndVideoFragmentsHaveTheirOwnCadence() {
        assertEquals("api",XNetwork.kind("api.x.com"));assertEquals("api",XNetwork.kind("cdn.syndication.twimg.com"))
        assertEquals("image",XNetwork.kind("pbs.twimg.com"));assertEquals("media",XNetwork.kind("video.twimg.com"))
        assertNull(XNetwork.kind("x.com.evil.test"));assertNull(XNetwork.kind("pbs.twimg.com.evil.test"))
        var clock=10000L;val directory=temp.newFolder()
        fun guard()=PixivRequestGuard(directory,{clock},{clock+=it},XNetwork.policy)
        guard().acquire("api");guard().acquire("media");assertEquals(10250L,clock)
        guard().acquire("image");guard().acquire("image");assertEquals(11500L,clock)
        guard().acquire("api");assertEquals(12000L,clock)
    }
    @Test fun serverResetPersistsAcrossInstancesAndDoesNotPausePixiv() {
        var clock=1_000_000L;val directory=temp.newFolder()
        val x=PixivRequestGuard(directory,{clock},{clock+=it},XNetwork.policy)
        x.block(429,"20",resetAt="5000")
        assertEquals(5_000_000L,JSONObject(File(directory,"x.json").readText()).getLong("until"))
        for(lane in listOf("api","image","media")) assertEquals(MediaErrorCode.RATE_LIMITED,
            MediaErrors.codeOf(runCatching{PixivRequestGuard(directory,{clock},policy=XNetwork.policy).acquire(lane)}.exceptionOrNull()!!))
        PixivRequestGuard(directory,{clock},{clock+=it}).acquire("api")
        assertTrue(File(directory,"pixiv.json").exists());assertEquals(1_000_000L,clock)
    }
    @Test fun rateLimitsNeverSelectAnotherPublicTransportOrAccount() {
        for(code in listOf("RATE_LIMITED","AUTH_REQUIRED","CHALLENGE_REQUIRED")) {
            assertFalse(XPublicPolicy.shouldTryPublic("[$code] refused; 503"))
            assertFalse(XPublicPolicy.canTrySecondary("[$code] refused"))
        }
    }
}
