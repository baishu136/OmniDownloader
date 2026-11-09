package com.omni.downloader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaAnalysisRouterTest {
    @Test fun ambiguousTargetCanBeIndependentlyVerifiedByOtherExtractor() = runTest {
        val calls = mutableListOf<String>(); val hints = mutableListOf<MediaPage>()
        val photo = MediaAnalysisRouter(video = { _: String -> calls.add("video"); error("[MEDIA_ID_MISMATCH] no video Apollo entity") }, images = { _: String -> calls.add("images"); "target-atlas" })
        assertEquals(ParsedContent.Images("target-atlas"), photo.analyze("https://www.kuaishou.com/short-video/123", MediaPage.VIDEO, hints::add))
        assertEquals(listOf("video", "images"), calls); assertTrue(hints.isEmpty())
        calls.clear()
        val video = MediaAnalysisRouter(video = { _: String -> calls.add("video"); "target-video" }, images = { _: String -> calls.add("images"); error("[NO_MEDIA] target is video") })
        assertEquals(ParsedContent.Video("target-video"), video.analyze("https://www.kuaishou.com/short-video/123", MediaPage.IMAGES, hints::add))
        assertEquals(listOf("images", "video"), calls); assertTrue(hints.isEmpty())
    }

    @Test fun knownTypeMismatchAndUnverifiedAlternativesDoNotPretendSuccess() = runTest {
        val mismatch = IllegalStateException("[MEDIA_ID_MISMATCH] target missing")
        var alternatives = 0
        val known = MediaAnalysisRouter(video = { _: String -> throw mismatch }, images = { _: String -> alternatives++; "wrong" })
        assertSame(mismatch, runCatching { known.analyze("https://www.douyin.com/video/123", MediaPage.IMAGES) }.exceptionOrNull())
        assertEquals(0, alternatives)
        val unknown = MediaAnalysisRouter<String, String>(video = { throw mismatch }, images = { error("[NO_MEDIA] no verified images") })
        assertSame(mismatch, runCatching { unknown.analyze("https://www.kuaishou.com/short-video/123", MediaPage.VIDEO) }.exceptionOrNull())
    }

    @Test fun photoExtractorRetainsOriginalShareHostAndContext() {
        val original = "https://www.chenzhongtech.com/fw/photo/123?shareObjectId=456&shareToken=context"
        assertEquals(original, MediaAnalysisRouter.imageInput(original, "https://www.kuaishou.com/short-video/123"))
        assertEquals("https://v.kuaishou.com/abc/", MediaAnalysisRouter.imageInput("https://v.kuaishou.com/abc/", "https://www.kuaishou.com/short-video/123"))
        assertEquals("https://www.bilibili.com/opus/123", MediaAnalysisRouter.imageInput("https://redirect.example/path", "https://www.bilibili.com/opus/123"))
        assertEquals("https://www.bilibili.com/opus/123", MediaAnalysisRouter.imageInput("https://x.com/u/status/123", "https://www.bilibili.com/opus/123"))
    }

    @Test fun clearTypeUsesOnlyMatchingExtractor() = runTest {
        val calls = mutableListOf<String>(); val hints = mutableListOf<MediaPage>()
        val router = MediaAnalysisRouter(video = { _: String -> calls.add("video"); "video" }, images = { _: String -> calls.add("images"); "images" })
        assertTrue(router.analyze("https://youtu.be/real-id", MediaPage.IMAGES, hints::add) is ParsedContent.Video)
        assertEquals(listOf("video"), calls); assertEquals(listOf(MediaPage.VIDEO), hints)
        calls.clear(); hints.clear()
        assertTrue(router.analyze("https://www.pixiv.net/artworks/123", MediaPage.VIDEO, hints::add) is ParsedContent.Images)
        assertEquals(listOf("images"), calls); assertEquals(listOf(MediaPage.IMAGES), hints)
    }

    @Test fun ambiguousPhotoMovesFromVideoOnlyAfterSuccessfulPhotoResolution() = runTest {
        val calls = mutableListOf<String>(); val hints = mutableListOf<MediaPage>()
        val router = MediaAnalysisRouter(video = { _: String -> calls.add("video"); error("[NO_MEDIA] photo post") }, images = { _: String -> calls.add("images"); "actual-photo-manifest" })
        val result = router.analyze("https://x.com/user/status/123", MediaPage.VIDEO, hints::add)
        assertEquals(ParsedContent.Images("actual-photo-manifest"), result)
        assertEquals(listOf("video", "images"), calls); assertTrue(hints.isEmpty())
    }

    @Test fun unconfirmedVideoCanResolveRealImagesWithoutPretendingVideoSucceeded() = runTest {
        val router = MediaAnalysisRouter<String, String>(video = { error("[MEDIA_UNCONFIRMED] no video could be found") }, images = { "real-images" })
        assertEquals(ParsedContent.Images("real-images"), router.analyze("https://x.com/user/status/123", MediaPage.VIDEO))
    }

    @Test fun videoPostedInPhotoPageAndUgoiraUseVideoWorkflow() = runTest {
        for (url in listOf("https://www.xiaohongshu.com/explore/123", "https://www.pixiv.net/artworks/123")) {
            val calls = mutableListOf<String>()
            val router = MediaAnalysisRouter(video = { _: String -> calls.add("video"); "metadata" }, images = { _: String -> calls.add("images"); error("[NO_MEDIA] video") })
            assertEquals(ParsedContent.Video("metadata"), router.analyze(url, MediaPage.IMAGES))
            assertEquals(listOf("images", "video"), calls)
        }
    }

    @Test fun networkOrAccessErrorsDoNotTriggerOtherExtractor() = runTest {
        for (code in listOf("NETWORK_ERROR", "AUTH_REQUIRED", "CHALLENGE_REQUIRED", "ACCESS_DENIED", "RATE_LIMITED")) {
            var alternatives = 0
            val error = IllegalStateException("[$code] original error")
            val router = MediaAnalysisRouter(video = { _: String -> throw error }, images = { _: String -> alternatives++; "wrong" })
            assertSame(error, runCatching { router.analyze("https://x.com/user/status/123", MediaPage.VIDEO) }.exceptionOrNull())
            assertEquals(0, alternatives)
        }
    }

    @Test fun noMediaNeverLoopsAndKeepsActionableFallbackError() = runTest {
        var calls = 0
        val router = MediaAnalysisRouter<String, String>(video = { calls++; error("[NO_MEDIA] none") }, images = { calls++; error("[NO_MEDIA] none") })
        assertEquals(MediaErrorCode.NO_MEDIA, MediaErrors.codeOf(runCatching { router.analyze("https://x.com/user/status/123", MediaPage.VIDEO) }.exceptionOrNull()!!))
        assertEquals(2, calls)
        val auth = IllegalStateException("[AUTH_REQUIRED] login needed")
        val restricted = MediaAnalysisRouter<String, String>(video = { error("[NO_MEDIA] none") }, images = { throw auth })
        assertSame(auth, runCatching { restricted.analyze("https://x.com/user/status/123", MediaPage.VIDEO) }.exceptionOrNull())
    }

    @Test fun cancelledAnalysisDoesNotStartFallbackOrPublishLateSuccess() = runTest {
        val started = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>(); var fallback = false
        val router = MediaAnalysisRouter(video = { _: String -> started.complete(Unit); wait.await(); "late" }, images = { _: String -> fallback = true; "wrong" })
        val request = async { router.analyze("https://x.com/user/status/123", MediaPage.VIDEO) }
        started.await(); request.cancel(); wait.complete(Unit)
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException); assertFalse(fallback)
    }

    @Test fun hintsAreHostSafeAndDoNotGuessAmbiguousSharePaths() {
        assertNull(MediaAnalysisRouter.knownPage("https://youtube.com.example.org/video/123"))
        assertNull(MediaAnalysisRouter.knownPage("https://www.kuaishou.com/short-video/123"))
        assertNull(MediaAnalysisRouter.knownPage("https://www.xiaohongshu.com/explore/123"))
        assertNull(MediaAnalysisRouter.knownPage("https://v.douyin.com/abc/"))
        assertEquals(MediaPage.IMAGES, MediaAnalysisRouter.knownPage("https://www.douyin.com/note/123"))
        assertEquals(MediaPage.VIDEO, MediaAnalysisRouter.knownPage("BV1xx411c7mD"))
        assertEquals(MediaPage.IMAGES, MediaAnalysisRouter.knownPage("https://www.bilibili.com/opus/123"))
    }
}
