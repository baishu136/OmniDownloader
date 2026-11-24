package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun clearDirectoryContentsPreservesRootDirectoryAndAllowsTempCreation() {
        val cache = File(tempFolder.root, "cache").apply { mkdirs() }
        val stale = File(cache, "old/sub.tmp").apply { parentFile!!.mkdirs(); writeText("stale") }
        assertTrue(stale.exists())

        SafeFiles.clearDirectoryContents(cache)
        assertTrue("cache 目录本身必须保留", cache.exists() && cache.isDirectory)
        assertFalse(stale.exists())

        val recreated = SafeFiles.createTempFile(File(cache, "missing/nested"), "media-info-", ".json")
        assertTrue(recreated.exists())
        recreated.delete()
    }

    @Test
    fun safeCopyDoesNotDeleteSourceWhenSourceEqualsDestination() {
        val file = File(tempFolder.root, "video.mp4").apply { writeText("payload") }
        val copied = SafeFiles.safeCopy(file, File(tempFolder.root.canonicalFile, "video.mp4"), overwrite = true)
        assertTrue(copied.exists())
        assertEquals("payload", copied.readText())
    }

    @Test
    fun safeCopyAndOpenOutputStreamCreateMissingParentDirectories() {
        val source = File(tempFolder.root, "staged/demo.mp4").apply {
            parentFile!!.mkdirs()
            writeText("media-bytes")
        }
        val nestedTarget = File(tempFolder.root, "public/OmniDownloader/demo.mp4")
        SafeFiles.safeCopy(source, nestedTarget, overwrite = true)
        assertEquals("media-bytes", nestedTarget.readText())

        val streamedTarget = File(tempFolder.root, "deep/sub/cover.jpg")
        SafeFiles.openOutputStream(streamedTarget).use { it.write("img".toByteArray()) }
        assertEquals("img", streamedTarget.readText())
    }

    @Test
    fun sanitizeFileNameHandlesDotsNullBytesAndUtf8ByteLimit() {
        assertEquals("fallback", SafeFiles.sanitizeFileName("...", fallback = "fallback"))
        assertEquals("fallback", SafeFiles.sanitizeFileName("..", fallback = "fallback"))
        assertEquals("fallback", SafeFiles.sanitizeFileName("   ", fallback = "fallback"))
        assertEquals("a_b", SafeFiles.sanitizeFileName("a\u0000b"))

        val longMixed = "测试标题😀".repeat(40)
        val cleaned = SafeFiles.sanitizeFileName(longMixed, maxUtf8Bytes = 60)
        assertTrue(cleaned.toByteArray(Charsets.UTF_8).size <= 60)
        assertFalse(Character.isHighSurrogate(cleaned.last()))
    }
}
