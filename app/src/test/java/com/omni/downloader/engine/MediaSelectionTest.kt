package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class MediaSelectionTest {
    @Test fun genericEntriesKeepRealUrlsInsteadOfSyntheticVideoPaths() {
        assertEquals("https://www.youtube.com/watch?v=Target_2", MediaSelection.childUrl("https://youtube.com/playlist?list=PL", "YouTube", 1, "", "", "Target_2", "Target_2"))
        assertEquals("https://example.org/watch?id=2", MediaSelection.childUrl("https://example.org/list", "网络视频", 1, "https://example.org/watch?id=2", "", "", "2"))
        assertNull(MediaSelection.childUrl("https://example.org/list", "网络视频", 1, "", "", "opaque-id", "2"))
    }
    @Test fun twitterEntriesUseTheirOwnVideoIndex() {
        assertEquals("https://x.com/user/status/123/video/3", MediaSelection.childUrl("https://x.com/user/status/123", "X (Twitter)", 2, "https://x.com/user/status/123", "", "", ""))
    }
    @Test fun bilibiliCollectionRetainsSharedEpisode() {
        val children = listOf("https://www.bilibili.com/video/BV1111111111", "https://www.bilibili.com/video/BV2222222222")
        assertEquals(1, MediaSelection.initialIndex(children[1] + "?share_source=copy", children[1], children))
    }
    @Test fun bilibiliPartIsNotLost() {
        val url = "https://www.bilibili.com/video/BV1111111111"
        assertEquals(2, MediaSelection.initialIndex("$url?p=3&share_source=copy", "$url?p=3", (1..4).map { "$url?p=$it" }))
    }
    @Test fun resolvedShortLinkSelectsTheActualEpisode() {
        val children = listOf("https://www.bilibili.com/video/BV1111111111", "https://www.bilibili.com/video/BV2222222222")
        assertEquals(1, MediaSelection.initialIndex("https://b23.tv/short", children[1], children))
    }
    @Test fun youtubeVideoIdentitySurvivesPlaylistAndShortLink() {
        val children = listOf("https://www.youtube.com/watch?v=first", "https://www.youtube.com/watch?v=Target_2")
        assertEquals(1, MediaSelection.initialIndex("https://youtu.be/Target_2?list=PL123&index=2", children[1], children))
    }
    @Test fun twitterVideoIndexIsPreserved() {
        val children = (1..3).map { "https://x.com/user/status/123/video/$it" }
        assertEquals(2, MediaSelection.initialIndex("https://twitter.com/user/status/123/video/3", children[0], children))
    }
    @Test fun missingEntryDoesNotFallBackToAnUnrelatedFirstVideo() {
        assertEquals(-1, MediaSelection.initialIndex("https://youtu.be/missing", "https://www.youtube.com/watch?v=missing", listOf("https://youtu.be/other")))
    }
}
