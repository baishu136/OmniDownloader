package com.omni.downloader.engine

import com.omni.downloader.data.model.ImagePost
import com.omni.downloader.data.model.ImageResource
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class XProfileInputTest {
    private fun image(post: Int, asset: String) = ImageResource("$post:$asset", "https://pbs.twimg.com/media/$asset?format=jpg&name=orig", 2048,3072,true)
    private fun page(cursor: String = "next", images: List<ImageResource> = listOf(image(101,"one")), user: String = "42") = ImagePost(
        LinkNormalizer.PLATFORM_X,"https://x.com/hazzi_808","x-profile-$user","images","hazzi_808",images,"x_profile",user,cursor,cursor.isBlank())
    @Test fun profilesRouteToImagesWithoutTryingTheVideoExtractor(): Unit = runTest {
        var videos = 0
        val router = MediaAnalysisRouter(video = { _: String -> videos++; "video" }, images = { "images" })
        assertEquals(ParsedContent.Images("images"),router.analyze("https://x.com/hazzi_808",MediaPage.VIDEO))
        assertEquals(0,videos)
        assertEquals("hazzi_808",XProfileInput.handle("https://twitter.com/hazzi_808/media"))
        for (url in listOf("https://x.com/home", "https://x.com/hazzi_808/status/1", "https://x.com.evil.test/hazzi_808", "https://x.com@evil.test/hazzi_808", "https://x.com:443/hazzi_808")) assertNull(url,XProfileInput.handle(url))
    }
    @Test fun pagesDeduplicateStableIdsAndSourcesWithoutLosingOrderOrCompletion() {
        val first = page()
        val merged = XProfileInput.merge(first,page("",listOf(image(101,"one"),image(102,"two"),image(103,"one"))))
        assertEquals(listOf("101:one","102:two"),merged.resources.map { it.id })
        assertTrue(merged.profileComplete)
        assertFalse(XProfileInput.merge(null,page(images=emptyList())).profileComplete)
    }
    @Test fun wrongUsersCursorLoopsAndNonImageSourcesAreRejected() {
        assertTrue(runCatching { XProfileInput.merge(page(),page("other",user="99")) }.isFailure)
        assertTrue(runCatching { XProfileInput.merge(page(),page()) }.isFailure)
        assertTrue(runCatching { XProfileInput.merge(null,page(images=listOf(image(1,"one").copy(url="https://video.twimg.com/video.mp4")))) }.isFailure)
        assertTrue(runCatching { XProfileInput.merge(null,page().copy(profileCursor="",profileComplete=false)) }.isFailure)
    }
    @Test fun downloadsAreRegularPerPostTasksAndCanRefreshWithoutAProfileScan() {
        val posts = XProfileInput.posts(page(images=listOf(image(101,"one"),image(101,"two"),image(102,"three"))))
        assertEquals(listOf("https://x.com/hazzi_808/status/101","https://x.com/hazzi_808/status/102"),posts.map { it.url })
        assertEquals(listOf("101:one","101:two"),posts.first().resources.map { it.id })
        assertEquals("https://x.com/",posts.first().resources.first().headers["Referer"])
        assertEquals("101",posts.first().contentId)
        assertEquals("x_profile_post",posts.first().route)
    }
    @Test fun paginationContinuesAcrossEmptyPhotoPagesAndPreservesSelectedSources(): Unit = runTest {
        val requests = mutableListOf<Pair<String,String>>()
        val pages = listOf(page("a"),page("b",emptyList()),page("",listOf(image(102,"two"))))
        var count = 0
        val result = XProfileInput.collect { cursor,user -> requests += cursor to user; pages[count++] }
        assertEquals(listOf("" to "", "a" to "42", "b" to "42"),requests)
        assertEquals(2,result.resources.size); assertTrue(result.profileComplete)
    }
    @Test fun failureKeepsConfirmedPagesAndResumeRequestsOnlyTheRemainingCursor(): Unit = runTest {
        var saved: ImagePost? = null
        var calls = 0
        val error = runCatching { XProfileInput.collect(onPage={saved=it}) { _,_ ->
            if (calls++ == 0) page("a") else error("[RATE_LIMITED] fixture")
        } }.exceptionOrNull()
        assertNotNull(error); assertEquals("a",saved!!.profileCursor); assertFalse(saved!!.profileComplete)
        val resumed = XProfileInput.collect(saved) { cursor,user ->
            assertEquals("a",cursor); assertEquals("42",user); page("",listOf(image(102,"two")))
        }
        assertEquals(2,resumed.resources.size); assertTrue(resumed.profileComplete)
    }
    @Test fun boundedRunRetainsContinuationAndNeverClaimsCompletion(): Unit = runTest {
        val result = XProfileInput.collect(pageLimit=1) { _,_ -> page("a") }
        assertEquals("a",result.profileCursor); assertFalse(result.profileComplete)
    }
    @Test fun cancelledPageCannotPublishLateResultsOrStartAnotherPage(): Unit = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var published = 0; var calls = 0
        val work = async { XProfileInput.collect(onPage={published++}) { _,_ -> calls++; started.complete(Unit); release.await(); page() } }
        started.await(); work.cancel(); release.complete(Unit)
        assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0,published); assertEquals(1,calls)
    }
}
