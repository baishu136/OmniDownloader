package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YoutubeCookieOptionsTest {
    @Test fun metadataAndDownloadUseScopedCookieFile() {
        val cookie = File.createTempFile("youtube-fixture-", ".txt")
        try {
            val metadata = YtDlpOptionsFactory.metadataRequest("https://www.youtube.com/watch?v=fixture", "YouTube", "", false, cookie)
            val download = YtDlpOptionsFactory.downloadRequest("https://www.youtube.com/watch?v=fixture", "YouTube", "",
                YtDlpOptionsFactory.DownloadFormatPlan(DownloadType.VIDEO_WITH_AUDIO), "fixture.%(ext)s", cookieFile = cookie)
            assertEquals(cookie.path, metadata.getOption("--cookies"))
            assertEquals(cookie.path, download.getOption("--cookies"))
            assertFalse(metadata.hasOption("--cookies-from-browser"))
        } finally { cookie.delete() }
    }
    @Test fun snapshotDownloadDoesNotAlsoReextractPage() {
        val info = File.createTempFile("info-fixture-", ".json")
        try {
            val url = "https://x.com/test/status/1"
            val request = YtDlpOptionsFactory.downloadRequest(url, "X (Twitter)", "",
                YtDlpOptionsFactory.DownloadFormatPlan(DownloadType.VIDEO_WITH_AUDIO), "fixture.%(ext)s", infoFile = info)
            assertEquals(info.path, request.getOption("--load-info-json"))
            assertFalse(request.buildCommand().contains(url))
        } finally { info.delete() }
    }
}
