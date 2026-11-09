package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyboxExtractorTest {

    @Test
    fun extractsMp4AndHlsFromEscapedPageData() {
        val html = """
            <meta property="og:video" content="https://video.max-c.com/a/b/clip.mp4?sign=1&amp;t=2">
            <script>window.data={"fallback":"https:\/\/cdn.max-c.com\/hls\/master.m3u8?token=x"}</script>
            <source src="https://cdn.max-c.com/a/clip.mp4">
        """.trimIndent()

        val urls = HeyboxExtractor.extractMediaUrls(html)
        assertEquals(3, urls.size)
        assertTrue(urls.any { it.contains("clip.mp4") && it.contains("sign=1&t=2") })
        assertTrue(urls.any { it.contains("master.m3u8") })
        assertTrue(urls.all { it.startsWith("https://") })
    }

    @Test
    fun ignoresNonMediaAndDuplicates() {
        val html = """
            <img src="https://cdn.max-c.com/cover.jpg">
            <a href="javascript:void(0)">x</a>
            <video src="https://cdn.max-c.com/v.mp4"></video>
            <source src="https://cdn.max-c.com/v.mp4">
        """.trimIndent()
        val urls = HeyboxExtractor.extractMediaUrls(html)
        assertEquals(listOf("https://cdn.max-c.com/v.mp4"), urls)
    }

    @Test
    fun emptyPageYieldsNoMedia() {
        assertTrue(HeyboxExtractor.extractMediaUrls("").isEmpty())
        assertTrue(HeyboxExtractor.extractMediaUrls("<html><body>no media</body></html>").isEmpty())
    }
}
