package com.omni.downloader.engine

import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DomesticMediaSourceTest {
    private fun metadata(site: String = "抖音", url: String = "https://cdn.example/video.mp4") =
        VideoMetadata("https://www.douyin.com/video/123", "video", siteName = site,
            availableVideoFormats = listOf(FormatOption(url, "原画")))

    @Test fun gifTaskRetainsMediaSourceAcrossPersistence() {
        val meta = metadata()
        val task = DownloadTask("test", meta.url, meta.title, downloadType = DownloadType.GIF,
            selectedResolution = "GIF", directMediaUrl = DomesticMediaSource.url(meta),
            directMediaHeaders = DomesticMediaSource.headers(meta.siteName))
        val restored = Json.decodeFromString<DownloadTask>(Json.encodeToString(task))
        assertEquals("https://cdn.example/video.mp4", restored.directMediaUrl)
        assertEquals("https://www.douyin.com/", restored.directMediaHeaders["Referer"])
        assertEquals("GIF", restored.selectedResolution)
    }

    @Test fun oldTaskJsonRemainsReadable() {
        val old = """{"id":"old","url":"https://example.org","title":"old","downloadType":"AUDIO_ONLY"}"""
        val task = Json.decodeFromString<DownloadTask>(old)
        assertEquals("", task.directMediaUrl)
        assertTrue(task.directMediaHeaders.isEmpty())
    }

    @Test fun collectionUsesEachEpisodesOwnSource() {
        val first = metadata(url = "https://cdn.example/first.mp4")
        val second = metadata(url = "https://cdn.example/second.mp4")
        val selection = first.availableVideoFormats.first()
        assertEquals("https://cdn.example/second.mp4", DomesticMediaSource.url(second, selection))
    }

    @Test fun selectedDomesticQualityIsPreserved() {
        val selected = FormatOption("https://cdn.example/hd.mp4", "1080p")
        val meta = metadata().copy(availableVideoFormats = metadata().availableVideoFormats + selected)
        assertEquals(selected.formatId, DomesticMediaSource.url(meta, selected))
    }

    @Test fun bilibiliDashIsNotTreatedAsCompleteMedia() {
        assertEquals("", DomesticMediaSource.url(metadata(site = "哔哩哔哩")))
        assertTrue(DomesticMediaSource.headers("哔哩哔哩").isEmpty())
    }

    @Test fun extractorFormatIdsAreNotMistakenForUrls() {
        assertEquals("", DomesticMediaSource.url(metadata(url = "137")))
    }
}
