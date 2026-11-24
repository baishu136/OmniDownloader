package com.omni.downloader.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProgressStringsTest {
    @Test fun processingStepsFollowSelectedLanguage() {
        assertEquals("正在转换 GIF…", ZhHansStrings.progressText("正在转换 GIF…"))
        assertEquals("正在轉換 GIF…", ZhHantStrings.progressText("正在转换 GIF…"))
        assertEquals("Converting GIF…", EnStrings.progressText("正在转换 GIF…"))
        assertEquals("GIF を変換中…", JaStrings.progressText("正在转换 GIF…"))
    }

    @Test fun transferTimesAndUnknownOriginalDetailsRemainUnchanged() {
        assertEquals("00:05", EnStrings.progressText("00:05"))
        assertEquals("opaque diagnostic", JaStrings.progressText("opaque diagnostic"))
    }

    @Test fun platformSpecificDownloadStepsHaveEnglishTranslations() {
        for (source in listOf("正在音画无损合成 (FFmpeg)...", "正在导出成品…", "正在保存原图")) {
            assertFalse(Regex("[\\u3400-\\u9fff]").containsMatchIn(EnStrings.progressText(source)))
        }
    }
}
