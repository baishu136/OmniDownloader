package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XMultiMetadataTest {
    private val page = "https://x.com/user/status/2119233448888217846"
    private val site = LinkNormalizer.PLATFORM_X
    private fun entry(id: String) = JSONObject().put("id", id).put("title", "fixture")
        .put("webpage_url", page).put("duration", 2).put("formats", JSONArray()
            .put(JSONObject().put("format_id", "hls-video").put("url", "https://video.twimg.com/amplify_video/$id/pl/video.m3u8")
                .put("width", 720).put("height", 1280).put("vcodec", "avc1").put("acodec", "none"))
            .put(JSONObject().put("format_id", "hls-audio").put("url", "https://video.twimg.com/amplify_video/$id/pl/audio.m3u8")
                .put("vcodec", "none").put("acodec", "aac")))
    private fun playlist() = JSONObject().put("_type", "playlist").put("id", "2119233448888217846")
        .put("entries", JSONArray().put(entry("111")).put(entry("222")).put(entry("333")))

    @Test fun entriesWithoutTopLevelUrlsKeepTheirOwnMediaAndAudioFormats() {
        val metadata = DownloadEngine.parseMetadataFromJson(playlist(), page, site)
        assertEquals(listOf("111", "222", "333"), metadata.multiMediaList.map { it.mediaId })
        assertEquals(3, metadata.multiMediaList.size)
        for (child in metadata.multiMediaList) {
            assertEquals(page, child.url)
            assertTrue(child.availableVideoFormats.single().sourceUrl.contains("/${child.mediaId}/"))
            assertEquals(1, child.availableAudioFormats.size)
        }
    }

    @Test fun samePageSnapshotsDoNotOverwriteOneAnotherOrFallBackToTheLastEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MetadataSnapshots.put(context, page, site, "", playlist())
        for (id in listOf("111", "222", "333")) {
            val json = JSONObject(MetadataSnapshots.get(context, page, site, "", id)!!)
            assertEquals(id, json.getString("id"))
            assertTrue(json.getJSONArray("formats").getJSONObject(0).getString("url").contains("/$id/"))
        }
        assertNull(MetadataSnapshots.get(context, page, site, "", "not-this-media"))
        assertNull(MetadataSnapshots.get(context, page, site, "", ""))
        assertNull(MetadataSnapshots.get(context, page, site, "http://different-network:7890", "111"))
    }

    @Test fun twitterAliasAndTrackingParametersShareTheSameMediaIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = playlist()
        val children = data.getJSONArray("entries")
        for (index in 0 until children.length()) children.getJSONObject(index)
            .put("webpage_url", page.replace("x.com", "twitter.com") + "?s=19")
        MetadataSnapshots.put(context, page, site, "", data)
        assertEquals("222", JSONObject(MetadataSnapshots.get(context, page, site, "", "222")!!).getString("id"))
        assertNull(MetadataSnapshots.get(context, page, site, "", ""))
    }
}
