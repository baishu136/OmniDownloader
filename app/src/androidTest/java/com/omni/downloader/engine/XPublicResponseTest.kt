package com.omni.downloader.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XPublicResponseTest {
    private val id = "2119233448888217846"
    @Test fun emptyResponseThenConfirmedTargetRecoversOnSecondRequest() {
        val attempts = mutableListOf<Int>()
        val value = XPublicExtractor.confirmedStatus(id) { attempt ->
            attempts += attempt
            if (attempt == 0) JSONObject() else JSONObject().put("id_str", id).put("text", "target")
        }
        assertEquals(id, value.getString("id_str")); assertEquals(listOf(0, 1), attempts)
    }
    @Test fun numericLongIdAndNullStringIdKeepAllSnowflakeDigits() {
        val value = XPublicExtractor.confirmedStatus(id) { JSONObject("""{"id_str":null,"id":2119233448888217846}""") }
        assertEquals(id, value.getLong("id").toString())
        XPublicExtractor.confirmedStatus(id) { JSONObject().put("id_str", "").put("id", id) }
    }
    @Test fun actualOtherPostNeverUsesAQuotedTargetOrRetries() {
        var calls = 0
        val error = runCatching { XPublicExtractor.confirmedStatus(id) {
            calls++; JSONObject().put("id_str", "999").put("quoted_tweet", JSONObject().put("id_str", id))
        } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrors.codeOf(error)); assertEquals(1, calls)
    }
    @Test fun nestedTargetIsNotRootIdentityAndRepeatedEmptyResponsesStayUnconfirmed() {
        var calls = 0
        val error = runCatching { XPublicExtractor.confirmedStatus(id) {
            calls++; JSONObject().put("quoted_tweet", JSONObject().put("id_str", id))
        } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrors.codeOf(error)); assertEquals(2, calls)
    }
    @Test fun floatingPointSnowflakeCannotBeRoundedIntoAConfirmedTarget() {
        val error = runCatching { XPublicExtractor.confirmedStatus(id) {
            JSONObject("""{"id":2.1192334488882178E18}""")
        } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrors.codeOf(error))
    }
    @Test fun explicitDenialIsNotRetriedAsAnEmptyResponse() {
        var calls = 0
        val error = runCatching { XPublicExtractor.confirmedStatus(id) { calls++; error("[AUTH_REQUIRED] denied") } }.exceptionOrNull()!!
        assertEquals(MediaErrorCode.AUTH_REQUIRED, MediaErrors.codeOf(error)); assertEquals(1, calls)
    }
}
