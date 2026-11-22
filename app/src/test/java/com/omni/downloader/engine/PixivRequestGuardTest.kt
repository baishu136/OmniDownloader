package com.omni.downloader.engine

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PixivRequestGuardTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun denialWithoutServerDeadlineMustNotInventFifteenMinuteWait() {
        val clock=1_000_000L;val directory=temp.newFolder()
        PixivRequestGuard(directory,{clock}).block(401)
        assertTrue("A local warning must not invent a 15-minute deadline",JSONObject(java.io.File(directory,"pixiv.json").readText()).getLong("until")<=clock)
    }
    @Test fun separateClientsSharePacingAcrossApiAndMedia() {
        var clock=10000L;val directory=temp.newFolder()
        fun guard()=PixivRequestGuard(directory,{clock},{clock+=it})
        guard().acquire("api");assertEquals(10000L,clock)
        guard().acquire("api");assertEquals(12000L,clock)
        guard().acquire("media");assertEquals(12500L,clock)
        guard().acquire("media");assertEquals(13500L,clock)
        guard().acquire("api");assertEquals(14000L,clock)
    }
    @Test fun cooldownSurvivesRestartAndHonorsLongerServerDeadlineWithoutWaiting() {
        var clock=1_000_000L;val directory=temp.newFolder()
        val first=PixivRequestGuard(directory,{clock},{fail("Cooldown should fail immediately")})
        first.block(429,"3600")
        val state=JSONObject(java.io.File(directory,"pixiv.json").readText())
        assertEquals(clock+3_600_000,state.getLong("until"))
        val restarted=PixivRequestGuard(directory,{clock},{fail("No sleep during cooldown")})
        for(lane in listOf("api","media")) assertEquals(MediaErrorCode.RATE_LIMITED,MediaErrors.codeOf(runCatching {restarted.acquire(lane)}.exceptionOrNull()!!))
        restarted.acknowledge(first.warning()!!.id)
        assertTrue(runCatching{restarted.acquire("api")}.isFailure) // Acknowledgment cannot erase server Retry-After.
        clock+=3_600_001;restarted.acquire("api")
        restarted.block(429);assertEquals(clock,JSONObject(java.io.File(directory,"pixiv.json").readText()).getLong("until"))
    }
    @Test fun warningNeedsExplicitAcknowledgmentAndCannotAcknowledgeNewerDenial() {
        var clock=1_000_000L;val guard=PixivRequestGuard(temp.newFolder(),{clock},{clock+=it})
        guard.block(401);val old=guard.warning()!!
        assertTrue(runCatching{guard.acquire("api")}.isFailure)
        guard.block(429);val newer=guard.warning()!!
        guard.acknowledge(old.id);assertEquals(newer,guard.warning())
        guard.acknowledge(newer.id);assertNull(guard.warning());guard.acquire("api")
    }
    @Test fun oldLocalCooldownBecomesWarningButLongerServerWaitRemains() {
        for(duration in listOf(900_000L,3_600_000L)) {
            val directory=temp.newFolder();val clock=1_000_000L
            java.io.File(directory,"pixiv.json").writeText(JSONObject().put("until",clock+duration).put("lastFailure",clock).put("strikes",1).put("reason","RATE_LIMITED").toString())
            val guard=PixivRequestGuard(directory,{clock})
            val warning=guard.warning()!!
            assertEquals(if(duration==900_000L)0L else clock+duration,warning.until)
            guard.acknowledge(warning.id)
            assertEquals(duration==900_000L,runCatching{guard.acquire("api")}.isSuccess)
        }
    }
    @Test fun cancelledWaitDoesNotReserveSlotsOrEraseCooldown() {
        var clock=10000L;var cancelled=false;val directory=temp.newFolder()
        val guard=PixivRequestGuard(directory,{clock},{clock+=it;cancelled=true})
        guard.acquire("api")
        val before=java.io.File(directory,"pixiv.json").readText()
        assertTrue(runCatching {guard.acquire("api"){cancelled}}.exceptionOrNull() is IOException)
        assertEquals(before,java.io.File(directory,"pixiv.json").readText())
    }
    @Test fun corruptStateFailsClosedAndPersistsOneCooldown() {
        var clock=10000L;val directory=temp.newFolder();val file=java.io.File(directory,"pixiv.json")
        file.writeText("broken")
        assertTrue(runCatching {PixivRequestGuard(directory,{clock}).acquire("api")}.isFailure)
        val until=JSONObject(file.readText()).getLong("until")
        clock+=1000
        assertTrue(runCatching {PixivRequestGuard(directory,{clock}).acquire("api")}.isFailure)
        assertEquals(until,JSONObject(file.readText()).getLong("until"))
    }
    @Test fun retryAfterSupportsSecondsAndHttpDateAndHostMatchingIsExact() {
        assertEquals(120_000,PixivRequestGuard.retryAfter("120",0))
        assertEquals(120_000,PixivRequestGuard.retryAfter("Thu, 01 Jan 1970 00:02:00 GMT",0))
        for(bad in listOf("invalid","-2","1.5")) assertEquals(0,PixivRequestGuard.retryAfter(bad,0))
        assertEquals("api",PixivRequestGuard.hostKind("www.pixiv.net"));assertEquals("media",PixivRequestGuard.hostKind("i.pximg.net"))
        assertNull(PixivRequestGuard.hostKind("pixiv.net.evil.test"))
    }
    private fun response(request:Request,status:Int=200,body:String="{\"error\":false,\"body\":{\"id\":1}}",type:String="application/json")=Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type",type).body(body.toResponseBody(type.toMediaType())).build()
    @Test fun rateLimitAndVerificationStopEveryLaneBeforeAnotherNetworkAttempt() {
        for(status in listOf(401,403,429)) {
            val guard=PixivRequestGuard(temp.newFolder(),{10000L})
            val error=runCatching {PixivNetwork.inspect(response(Request.Builder().url("https://www.pixiv.net/ajax/test").build(),status),guard)}.exceptionOrNull()!!
            assertEquals(if(status==429) MediaErrorCode.RATE_LIMITED else MediaErrorCode.AUTH_REQUIRED,MediaErrors.codeOf(error))
            assertTrue(runCatching {guard.acquire("media")}.isFailure)
        }
        val guard=PixivRequestGuard(temp.newFolder(),{10000L})
        val error=runCatching {PixivNetwork.inspect(response(Request.Builder().url("https://i.pximg.net/test").build(),200,"<html>captcha</html>","text/html"),guard)}.exceptionOrNull()!!
        assertEquals(MediaErrorCode.CHALLENGE_REQUIRED,MediaErrors.codeOf(error))
    }
    @Test fun metadataCacheAvoidsDuplicateCallsAndSeparatesCookiesAndErrors() {
        var calls=0;var clock=10000L
        val client=OkHttpClient.Builder().addInterceptor(PixivMetadataCache{clock}).addInterceptor { chain ->calls++;response(chain.request())}.build()
        fun get(cookie:String)=client.newCall(Request.Builder().url("https://www.pixiv.net/ajax/illust/42").header("Cookie",cookie).build()).execute().use{it.body!!.string()}
        assertEquals(get("account-a"),get("account-a"));assertEquals(1,calls)
        get("account-b");assertEquals(2,calls)
        clock+=180001;get("account-a");assertEquals(3,calls)
        val oldProxy=NetworkProxy.configuredUrl
        try {
            NetworkProxy.configuredUrl="http://127.0.0.1:9"
            get("account-a");assertEquals(4,calls)
        } finally {NetworkProxy.configuredUrl=oldProxy}
        var rejectedCalls=0
        val rejected=OkHttpClient.Builder().addInterceptor(PixivMetadataCache()).addInterceptor {chain->rejectedCalls++;response(chain.request(),body="{\"error\":true}")}.build()
        repeat(2) {rejected.newCall(Request.Builder().url("https://www.pixiv.net/ajax/test").build()).execute().close()}
        assertEquals(2,rejectedCalls)
    }
}
