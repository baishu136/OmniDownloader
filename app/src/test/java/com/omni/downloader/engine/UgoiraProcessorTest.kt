package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UgoiraProcessorTest {

    @Test
    fun totalDurationUsesEveryFrameDelay() {
        val frames = listOf(
            UgoiraProcessor.Frame("000000.jpg", 80),
            UgoiraProcessor.Frame("000001.jpg", 120),
            UgoiraProcessor.Frame("000002.jpg", 40)
        )
        assertEquals(240L, UgoiraProcessor.totalDurationMs(frames))
    }

    @Test
    fun concatKeepsOrderAndRepeatsLastFrame() {
        val dir = File("frames")
        val frames = listOf(
            UgoiraProcessor.Frame("000000.jpg", 80),
            UgoiraProcessor.Frame("000001.jpg", 120)
        )
        val content = UgoiraProcessor.buildConcatContent(frames, dir)
        val lines = content.trim().lines()

        assertEquals("ffconcat version 1.0", lines.first())
        assertTrue(content.contains("000000.jpg"))
        assertTrue(content.contains("duration 0.080"))
        assertTrue(content.contains("duration 0.120"))
        // 末帧出现两次，确保 concat 识别最后一帧时长
        assertEquals(2, lines.count { it.contains("000001.jpg") })
        assertTrue(lines[lines.lastIndex - 1].contains("000001.jpg"))
        assertEquals("option framerate 1000", lines.last())
    }

    @Test
    fun unsafeZipEntriesAreRejected() {
        assertFalse(UgoiraProcessor.isSafeEntryName("../evil.jpg"))
        assertFalse(UgoiraProcessor.isSafeEntryName("/etc/passwd"))
        assertFalse(UgoiraProcessor.isSafeEntryName("C:/windows/system32/a.jpg"))
        assertFalse(UgoiraProcessor.isSafeEntryName("a/../../b.jpg"))
        assertTrue(UgoiraProcessor.isSafeEntryName("000000.jpg"))
        assertTrue(UgoiraProcessor.isSafeEntryName("ugoira/000001.jpg"))
    }

    @Test
    fun emptyFramesProduceEmptyConcat() {
        assertEquals("", UgoiraProcessor.buildConcatContent(emptyList(), File("frames")))
    }
}
