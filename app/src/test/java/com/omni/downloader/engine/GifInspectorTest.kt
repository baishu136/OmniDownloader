package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class GifInspectorTest {
    private val gif = Base64.getDecoder().decode("R0lGODlhEAAQAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAEKAAEALAAAAAAQABAAgQAA/wAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==")
    private fun check(bytes: ByteArray, action: (File) -> Unit) {
        val file = File.createTempFile("gif-inspector-", ".data")
        try { file.writeBytes(bytes); action(file) } finally { file.delete() }
    }
    @Test fun acceptsSmallAnimatedGifIndependentOfExtension() = check(gif) {
        val info = GifInspector.inspect(it)!!
        assertEquals(16, info.width); assertEquals(16, info.height)
        assertEquals(2, info.frames); assertEquals(200L, info.durationMs)
        assertTrue(it.length() < 1024)
    }
    @Test fun rejectsMissingTrailerAndTruncatedImageData() {
        check(gif.copyOf(gif.size - 1)) { assertNull(GifInspector.inspect(it)) }
        check(gif.copyOf(gif.size - 8) + byteArrayOf(0x3b)) { assertNull(GifInspector.inspect(it)) }
    }
    @Test fun headerAloneCannotBeReportedAsAGif() = check(gif.copyOf(13) + byteArrayOf(0x3b)) {
        assertNull(GifInspector.inspect(it))
    }
    @Test fun rejectsVideoRenamedToGif() = check(byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray()) {
        assertNull(GifInspector.inspect(it))
    }
}
