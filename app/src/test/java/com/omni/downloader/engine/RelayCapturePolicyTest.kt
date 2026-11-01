package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class RelayCapturePolicyTest {
    @Test fun directFilesAndManifestsMayContainSignedQueries() {
        assertTrue(RelayCapturePolicy.accepts("https://cdn.example/api/movie.MP4?sig=AbC#part"))
        assertTrue(RelayCapturePolicy.accepts("https://cdn.example/master.m3u8?token=XYZ"))
    }
    @Test fun apiRequestsAreNotMistakenForMedia() {
        assertFalse(RelayCapturePolicy.accepts("https://greenvideo.cc/api/extract?url=https://cdn.example/video.mp4"))
        assertFalse(RelayCapturePolicy.accepts("https://cdn.example/ajax/getDownloadInfo"))
    }
    @Test fun responseMimeSupportsExtensionlessMedia() {
        assertTrue(RelayCapturePolicy.accepts("https://cdn.example/api/stream?id=1", "video/mp4; charset=binary"))
        assertTrue(RelayCapturePolicy.accepts("https://cdn.example/play?id=1", "application/vnd.apple.mpegurl"))
        assertFalse(RelayCapturePolicy.accepts("https://cdn.example/play?id=1", "application/json"))
        assertFalse(RelayCapturePolicy.accepts("https://video.twimg.com/manifest.mpd"))
        assertFalse(RelayCapturePolicy.accepts("https://cdn.example/play?id=1", "application/dash+xml"))
    }
    @Test fun cdnHostMustMatchRatherThanAppearInQueryOrUnrelatedHost() {
        assertTrue(RelayCapturePolicy.accepts("https://v16.douyinvod.com/stream?token=X"))
        assertFalse(RelayCapturePolicy.accepts("https://douyinvod.com.evil.example/stream"))
        assertFalse(RelayCapturePolicy.accepts("https://example.org/page?host=video.twimg.com"))
    }
    @Test fun executablesImagesAndBrowserOnlyUrlsAreNotDownloads() {
        for (url in listOf("https://video.twimg.com/promo.apk", "https://cdn.example/a.png", "blob:https://example.org/abc", "file:///tmp/video.mp4", "https://user:pass@cdn.example/video.mp4")) assertFalse(url, RelayCapturePolicy.accepts(url))
    }
}
