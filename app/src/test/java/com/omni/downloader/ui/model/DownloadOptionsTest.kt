package com.omni.downloader.ui.model

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import org.junit.Assert.*
import org.junit.Test

class DownloadOptionsTest {
    private val muxed = FormatOption("http-720", "720p", width = 720, height = 1280, acodec = "aac")
    private fun metadata(vararg formats: FormatOption) = VideoMetadata("https://example.test/video", "sample", availableVideoFormats = formats.toList())

    @Test fun muxedAudioDoesNotRequireAnAudioOnlyList() {
        assertTrue(metadata(muxed).supportsAudioExtraction())
        assertTrue(metadata(muxed.copy(acodec = "unknown")).supportsAudioExtraction())
        assertFalse(metadata(muxed.copy(acodec = "none")).supportsAudioExtraction())
        assertFalse(metadata(muxed.copy(isVideoOnly = true)).supportsAudioExtraction())
    }
    @Test fun separateAudioIsAvailableButUnconfirmedOrGeneratedSourcesAreNotAudio() {
        val silent = metadata(muxed.copy(acodec = ""))
        assertFalse(silent.supportsAudioExtraction())
        assertTrue(silent.copy(availableAudioFormats = listOf(FormatOption("audio-128", "128kbps", isAudioOnly = true))).supportsAudioExtraction())
        assertFalse(metadata(muxed.copy(isSynthetic = true)).supportsAudioExtraction())
    }
    @Test fun currentChildHasItsOwnAudioCapability() {
        val silent = metadata(muxed.copy(acodec = "none"))
        val withAudio = metadata(muxed)
        val root = silent.copy(multiMediaList = listOf(silent, withAudio))
        assertFalse(root.multiMediaList[0].supportsAudioExtraction())
        assertTrue(root.multiMediaList[1].supportsAudioExtraction())
    }
    @Test fun rowContainsOnlyDimensionsContainerAndKnownSize() {
        val format = muxed.copy(resolutionLabel = "720p · H.264", fps = 60, note = "codec details", approximateSize = "14.81 GiB")
        assertEquals("720×1280 · MP4 · 14.81 GiB", formatDisplaySummary(format))
    }
    @Test fun unknownInformationIsNotInventedAndOriginalFrameArchiveRemainsDistinct() {
        assertEquals("分辨率待确认 · MP4 · 大小待确认", formatDisplaySummary(FormatOption("source", "source")))
        assertEquals("720×1280 · ZIP + JSON · 大小待确认", formatDisplaySummary(muxed.copy(formatId = "ugoira_zip", ext = "zip")))
    }
    @Test fun sourceQualityPolicyMustNotReplaceUgoiraExportChoice() {
        assertFalse(metadata(muxed.copy(formatId = "ugoira_mp4"), muxed.copy(formatId = "ugoira_gif")).hasSourceQualityChoices())
        assertTrue(metadata(muxed).hasSourceQualityChoices())
    }
    @Test fun adaptiveHighestUsesKnownTopDimensionsAndSizeButRealUnknownUrlDoesNot() {
        val adaptive = FormatOption("best", "自适应最高画质", isSynthetic = true)
        val high = muxed.copy(width = 1920, height = 1080, approximateSize = "78.9 MB")
        assertEquals("1920×1080 · MP4 · 78.9 MB", formatDisplaySummary(adaptive, listOf(adaptive, muxed, high)))
        assertEquals("分辨率待确认 · MP4 · 大小待确认", formatDisplaySummary(adaptive.copy(isSynthetic = false), listOf(high)))
    }
}
