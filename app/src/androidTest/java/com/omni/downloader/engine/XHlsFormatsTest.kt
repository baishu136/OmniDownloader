package com.omni.downloader.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XHlsFormatsTest {
    @Test fun keepsCompanionAudioAlongsideExactSilentVideoWithoutMutatingInput() {
        val hls = JSONArray("""[
          {"format_id":"audio-128000-Audio","url":"https://video.twimg.com/audio.m3u8","vcodec":"none"},
          {"format_id":"771","url":"https://video.twimg.com/video.m3u8","width":720,"height":1280,"vcodec":"avc1.64001f","acodec":"none"}
        ]""")
        val snapshot = JSONArray()
        XPublicExtractor.appendHlsFormats(snapshot, hls)
        assertEquals(2, snapshot.length())
        assertEquals("hls-audio-128000-Audio", snapshot.getJSONObject(0).getString("format_id"))
        assertEquals("unknown", snapshot.getJSONObject(0).getString("acodec"))
        assertEquals("hls-771", snapshot.getJSONObject(1).getString("format_id"))
        assertEquals("none", snapshot.getJSONObject(1).getString("acodec"))
        assertEquals("audio-128000-Audio", hls.getJSONObject(0).getString("format_id"))
        assertFalse(hls.getJSONObject(0).has("acodec"))
    }

    @Test fun keepsKnownAudioCodecAndMuxedVariants() {
        val snapshot = JSONArray()
        XPublicExtractor.appendHlsFormats(snapshot, JSONArray().put(JSONObject()
            .put("format_id", "audio").put("vcodec", "none").put("acodec", "mp4a.40.2"))
            .put(JSONObject().put("format_id", "muxed").put("vcodec", "avc1").put("acodec", "mp4a.40.2")))
        assertEquals(2, snapshot.length())
        assertEquals("mp4a.40.2", snapshot.getJSONObject(0).getString("acodec"))
        assertEquals("avc1", snapshot.getJSONObject(1).getString("vcodec"))
        assertEquals("mp4a.40.2", snapshot.getJSONObject(1).getString("acodec"))
    }
}
