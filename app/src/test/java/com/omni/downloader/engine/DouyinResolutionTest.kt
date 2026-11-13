package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DouyinResolutionTest {
    private val id = "7699370307513319141"
    private val url = "https://www.douyin.com/video/$id"
    private fun metadata(w: Int = 720, reference: Int = 1080) = VideoMetadata(url, "fixture", siteName = "抖音", mediaId = id,
        referenceWidth = reference, referenceHeight = reference * 16 / 9,
        availableVideoFormats = listOf(FormatOption("$w", "${w}p", w, w * 16 / 9, sourceUrl = "https://cdn.test/$w.mp4")))
    private fun TestScope.budget(millis: Long = 15_000) =
        DouyinResolutionBudget((testScheduler.currentTime + millis) * 1_000_000) { testScheduler.currentTime * 1_000_000 }

    @Test fun native720WithMatchingUploadNeedsNoWebPage() = runTest {
        var captures = 0; var probes = 0
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata(reference = 720)) },
            capture = { captures++; error("not needed") }, confirm = { data, _ -> probes++; data })
        assertTrue(result.isSuccess); assertEquals(0, captures); assertEquals(1, probes)
        assertEquals("", result.getOrThrow().qualityWarning)
    }

    @Test fun partialResultsAppearBeforeSlowEnhancementAndSurviveItsDeadline() = runTest {
        val previews = mutableListOf<Long>(); var probeCalled = false
        val result = DouyinResolution.resolve(url, budget(1000), Mutex(),
            native = { delay(100); Result.success(metadata()) },
            capture = { delay(10_000); null }, confirm = { data, _ -> probeCalled = true; data },
            onPreview = { previews += testScheduler.currentTime })
        assertEquals(listOf(100L), previews); assertEquals(1000L, testScheduler.currentTime)
        assertEquals(720, result.getOrThrow().availableVideoFormats.first().width)
        assertTrue(result.getOrThrow().qualityWarning.contains("补全未完成")); assertFalse(probeCalled)
    }

    @Test fun queueWaitCountsAgainstTheSameBudget() = runTest {
        val gate = Mutex(locked = true); var nativeCalled = false
        val result = DouyinResolution.resolve(url, budget(500), gate,
            native = { nativeCalled = true; Result.success(metadata()) }, capture = { null }, confirm = { data, _ -> data })
        assertEquals(500L, testScheduler.currentTime); assertTrue(result.isFailure); assertFalse(nativeCalled)
        gate.unlock()
    }

    @Test fun nativeTimeoutLeavesTimeForBrowserAndCancelsTheNativeWork() = runTest {
        var cancelled = false
        val result = DouyinResolution.resolve(url, budget(), Mutex(),
            native = { try { delay(30_000); Result.success(metadata()) } finally { cancelled = true } },
            capture = { candidate -> candidate(metadata(1080)); DouyinPageResult(DouyinPageStatus.FOUND) },
            confirm = { data, _ -> data })
        assertTrue(cancelled); assertEquals(3500L, testScheduler.currentTime)
        assertEquals(1080, result.getOrThrow().availableVideoFormats.first().width)
    }

    @Test fun userCancellationNeverTurnsAPreviewIntoSuccess() = runTest {
        val visible = CompletableDeferred<Unit>(); var stopped = false
        val job = async {
            DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata()) },
                capture = { try { awaitCancellation() } finally { stopped = true } }, confirm = { data, _ -> data },
                onPreview = { visible.complete(Unit) })
        }
        visible.await(); runCurrent(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(stopped)
    }

    @Test fun lateHigherAndLowerResponsesNeverDiscardTheHighestRendition() = runTest {
        val previews = mutableListOf<Int>()
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata()) },
            capture = { candidate -> candidate(metadata(1080)); candidate(metadata()); DouyinPageResult(DouyinPageStatus.FOUND) },
            confirm = { data, _ -> data }, onPreview = { previews += it.availableVideoFormats.first().width })
        assertEquals(listOf(720, 1080, 1080), previews)
        assertEquals(1080, result.getOrThrow().availableVideoFormats.first().width)
        assertEquals(2, result.getOrThrow().availableVideoFormats.size)
    }

    @Test fun changedAccountCancelsBeforePublishingALateSource() = runTest {
        var valid = true; var previews = 0
        val result = runCatching {
            DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata()) },
                capture = { candidate -> valid = false; candidate(metadata(1080)); null }, confirm = { data, _ -> data },
                validate = { if (!valid) throw CancellationException("changed") }, onPreview = { previews++ })
        }
        assertTrue(result.exceptionOrNull() is CancellationException); assertEquals(1, previews)
    }

    @Test fun missingTargetNeverPublishesRecommendationMetadata() = runTest {
        val result = runCatching {
            DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata().copy(mediaId = "other")) },
                capture = { null }, confirm = { data, _ -> data }, onPreview = { fail("wrong target") })
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("MEDIA_ID_MISMATCH"))
    }

    @Test fun trueLoginAndCaptchaFailuresRetainTheirDistinctCodes() = runTest {
        for ((status, code) in listOf(DouyinPageStatus.AUTH_REQUIRED to MediaErrorCode.AUTH_REQUIRED,
                DouyinPageStatus.CHALLENGE_REQUIRED to MediaErrorCode.CHALLENGE_REQUIRED)) {
            val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.failure(Exception("missing")) },
                capture = { DouyinPageResult(status) }, confirm = { data, _ -> data })
            assertEquals(code, MediaErrors.codeOf(result.exceptionOrNull()!!))
        }
    }

    @Test fun noMediaAndUnavailableTargetsDoNotLoadAnotherPage() = runTest {
        for (code in listOf("NO_MEDIA", "CONTENT_UNAVAILABLE", "RATE_LIMITED")) {
            val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.failure(Exception("[$code] fixture")) },
                capture = { fail("fatal target must not be retried"); null }, confirm = { data, _ -> data })
            assertEquals(code, MediaErrors.codeOf(result.exceptionOrNull()!!).code)
        }
    }

    @Test fun aSlowDimensionProbeDoesNotHideThePlayableSource() = runTest {
        var probeCancelled = false
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata(1080)) },
            capture = { null }, confirm = { data, _ -> try { delay(20_000); data } finally { probeCancelled = true } })
        assertTrue(probeCancelled); assertTrue(result.isSuccess); assertEquals(1500L, testScheduler.currentTime)
    }

    @Test fun aMeasuredLowerRenditionTriggersEnhancementInsteadOfClaimingUploadQuality() = runTest {
        var captures = 0
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata(1080)) },
            capture = { candidate -> captures++; candidate(metadata(1080)); DouyinPageResult(DouyinPageStatus.FOUND) },
            confirm = { data, _ -> if (captures == 0) metadata() else data })
        assertEquals(1, captures); assertEquals(1080, result.getOrThrow().availableVideoFormats.first().width)
    }

    @Test fun detailEnhancementRunsAfterThePreviewAndCanExposeAHigherSource() = runTest {
        val previews = mutableListOf<Pair<Int, Long>>()
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata(1080)) },
            capture = { null }, confirm = { data, _ -> data },
            onPreview = { previews += it.availableVideoFormats.first().width to testScheduler.currentTime },
            enrich = { delay(900); metadata(2160, reference = 2160) })
        assertEquals(listOf(1080 to 0L, 2160 to 900L), previews)
        assertEquals(2160, result.getOrThrow().availableVideoFormats.first().width)
    }

    @Test fun detailTimeoutDoesNotConsumeThePageBudgetOrEraseThePreview() = runTest {
        val result = DouyinResolution.resolve(url, budget(), Mutex(), native = { Result.success(metadata()) },
            capture = { candidate -> candidate(metadata(1080)); DouyinPageResult(DouyinPageStatus.FOUND) },
            confirm = { data, _ -> data }, enrich = { delay(60_000); null })
        assertEquals(1200L, testScheduler.currentTime)
        assertEquals(1080, result.getOrThrow().availableVideoFormats.first().width)
    }

    @Test fun anUnresolvedOrTimedOutShareIsANetworkErrorNotAWrongWork() {
        for (result in listOf(LinkNormalizer.RedirectResult.Content("https://v.douyin.com/share/"),
                LinkNormalizer.RedirectResult.Unavailable("https://v.douyin.com/share/", null))) {
            assertEquals(MediaErrorCode.NETWORK_ERROR, MediaErrors.codeOf(runCatching { DouyinResolution.redirectTarget(result) }.exceptionOrNull()!!))
        }
    }

    @Test fun resolvedSharesKeepTargetIdentityAndExplicitAccessFailures() {
        assertEquals(url, DouyinResolution.redirectTarget(LinkNormalizer.RedirectResult.Content(url)))
        assertEquals(url, DouyinResolution.redirectTarget(LinkNormalizer.RedirectResult.LoginRequired("https://www.douyin.com/login", url)))
        assertEquals(MediaErrorCode.AUTH_REQUIRED, MediaErrors.codeOf(runCatching {
            DouyinResolution.redirectTarget(LinkNormalizer.RedirectResult.LoginRequired("https://www.douyin.com/login", null))
        }.exceptionOrNull()!!))
        assertEquals(MediaErrorCode.CHALLENGE_REQUIRED, MediaErrors.codeOf(runCatching {
            DouyinResolution.redirectTarget(LinkNormalizer.RedirectResult.ChallengeRequired("https://www.douyin.com/verify", null))
        }.exceptionOrNull()!!))
        assertEquals(MediaErrorCode.MEDIA_ID_MISMATCH, MediaErrors.codeOf(runCatching {
            DouyinResolution.redirectTarget(LinkNormalizer.RedirectResult.Content("https://other.test/video/$id"))
        }.exceptionOrNull()!!))
    }
}
