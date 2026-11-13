package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BatchDownloadTest {
    @Test fun smartInputRecognizesChineseSharePunctuationAvIdsAndInvisibleCharacters() {
        assertEquals(listOf("https://b23.tv/share", "https://www.bilibili.com/video/av123"), BatchInput.links("分享 HTTPS://b23.tv/sha\u200Bre！视频 av123。"))
    }
    @Test fun manualAndSmartInputMergeDeduplicatesAndRejectsMultipleManualLinks() {
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), BatchInput.merge(listOf("https://example.org/a"), "分享：https://example.org/a\nhttps://example.org/b"))
        assertTrue(runCatching { BatchInput.merge(emptyList(), "https://example.org/a https://example.org/b", single = true) }.isFailure)
    }
    @Test fun newInputPreservesUnfinishedRowsAndTheIdentityOfPreviouslySubmittedLinks() {
        val pending = BatchItem("pending", "https://example.org/a", BatchStatus.RESOLVING)
        val queued = BatchItem("queued", "https://example.org/b", BatchStatus.QUEUED, taskIds = listOf("saved"))
        val result = BatchInput.preservePending(BatchPlan(listOf(pending, queued)), BatchPlan(listOf(BatchItem(url = queued.url), BatchItem(url = "https://example.org/c"))))
        assertEquals(3, result.items.size);assertEquals("pending", result.items.first().id)
        assertEquals("queued", result.items[1].id);assertEquals(BatchStatus.QUEUED, result.items[1].status)
    }
    @Test fun shareTextDeduplicatesWithoutLosingEpisodesOrSignedParameters() {
        val input = "分享 https://x.com/author/status/123/video/1\nhttps://x.com/author/status/123/video/2\n" +
            "https://x.com/author/status/123/video/1\nhttps://www.xiaohongshu.com/explore/abc?xsec_token=secret&xsec_source=pc_share"
        val links = BatchInput.links(input)
        assertEquals(3, links.size)
        assertTrue(links.last().contains("xsec_token=secret"))
        assertTrue(links[1].endsWith("/video/2"))
        assertEquals(1, BatchInput.links("BV1xx411c7mD BV1xx411c7mD").size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedBatch() {
        BatchInput.links((1..101).joinToString("\n") { "https://example.org/$it" })
    }
    @Test fun failedLinkDoesNotBlockLaterLinksAndRetrySkipsQueued() = runBlocking {
        val calls = mutableListOf<String>();var fail = true
        val queue = BatchQueue({ row, _ -> calls += row.id; if (row.id == "bad" && fail) error("failed"); listOf("task-${row.id}") })
        queue.run(BatchPlan(listOf(BatchItem("one", "a"), BatchItem("bad", "b"), BatchItem("three", "c"))))
        assertEquals(listOf(BatchStatus.QUEUED, BatchStatus.FAILED, BatchStatus.QUEUED), queue.plan.value.items.map { it.status })
        fail = false;queue.run()
        assertEquals(listOf("one", "bad", "three", "bad"), calls)
        assertTrue(queue.plan.value.items.all { it.status == BatchStatus.QUEUED })
    }
    @Test fun cancellationPreservesSubmittedRowsAndRestoresUnprocessedRows() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val queue = BatchQueue({ row, _ -> if (row.id == "slow") { entered.complete(Unit); awaitCancellation() }; listOf(row.id) }, parallelism = 1)
        val job = launch { queue.run(BatchPlan(listOf(BatchItem("one", "a"), BatchItem("slow", "b"), BatchItem("later", "c")))) }
        entered.await();job.cancelAndJoin()
        assertEquals(listOf(BatchStatus.QUEUED, BatchStatus.CANCELLED, BatchStatus.CANCELLED), queue.plan.value.items.map { it.status })
        assertFalse(queue.running.value)
        val recovered = BatchQueue({ row, _ -> listOf(row.id) })
        recovered.restore(queue.plan.value);recovered.run()
        assertTrue(recovered.plan.value.items.all { it.status == BatchStatus.QUEUED })
    }
    @Test fun adjacentShareLinksSplitAndQueryRedirectsRemainIntact() {
        val joined = "https://b23.tv/5bXAquAhttps://b23.tv/WliiBxwhttps://x.com/a/status/123?a=token&s=19https://v.douyin.com/pVm2K726QIc/https://v.douyin.com/pVm2K726QIc/"
        assertEquals(4, BatchInput.links(joined).size)
        val redirect = "https://example.org/watch?next=https://example.net/video&signature=abc"
        assertEquals(listOf(redirect), BatchInput.links(redirect))
        assertTrue(runCatching { BatchInput.merge(emptyList(), joined, single = true) }.isFailure)
    }
    @Test fun independentPlatformsResolveTogetherAndSaveEveryOutOfOrderCompletion() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>();val secondEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>();val saved = mutableListOf<BatchPlan>()
        val queue = BatchQueue({ row, _ ->
            if (row.id == "first") { firstEntered.complete(Unit);finishFirst.await() }
            else { firstEntered.await();secondEntered.complete(Unit) }
            listOf(row.id)
        }, save = { saved += it })
        val rows = listOf(BatchItem("first", "https://b23.tv/one"), BatchItem("second", "https://x.com/a/status/1"))
        val job = launch { queue.run(BatchPlan(rows)) }
        withTimeout(3000) { secondEntered.await() }
        yield()
        assertEquals(listOf(BatchStatus.RESOLVING, BatchStatus.QUEUED), queue.plan.value.items.map { it.status })
        assertTrue(runCatching { queue.clearHistory() }.isFailure)
        finishFirst.complete(Unit);job.join()
        assertEquals(listOf("first", "second"), queue.plan.value.items.map { it.taskIds.single() })
        assertEquals(queue.plan.value, saved.last())
        queue.clearHistory();assertTrue(queue.plan.value.items.isEmpty());assertTrue(saved.last().items.isEmpty())
    }
    @Test fun samePlatformIsSerializedWithoutBlockingAnotherPlatform() = runBlocking {
        for ((firstUrl, secondUrl) in listOf(
            "https://b23.tv/a" to "https://www.bilibili.com/video/av12",
            "https://EXAMPLE.org/a" to "https://example.org/b"
        )) {
        val release = CompletableDeferred<Unit>();val other = CompletableDeferred<Unit>();val calls = mutableListOf<String>()
        val queue = BatchQueue({ row, _ ->
            calls += row.id
            if (row.id == "a") release.await()
            if (row.id == "c") other.complete(Unit)
            listOf(row.id)
        })
        val job = launch { queue.run(BatchPlan(listOf(
            BatchItem("a", firstUrl), BatchItem("b", secondUrl), BatchItem("c", "https://x.com/a/status/1")
        ))) }
        withTimeout(3000) { other.await() };assertEquals(listOf("a", "c"), calls)
        release.complete(Unit);job.join();assertEquals(listOf("a", "c", "b"), calls)
        }
    }
    @Test fun cancellationStopsBothActiveResolversAndLeavesQueuedTasksIntact() = runBlocking {
        var entered = 0;var stopped = 0;val both = CompletableDeferred<Unit>()
        val queue = BatchQueue({ _, _ ->
            if (++entered == 2) both.complete(Unit)
            try { awaitCancellation() } finally { stopped++ }
        })
        val job = launch { queue.run(BatchPlan(listOf(
            BatchItem("one", "https://b23.tv/a"), BatchItem("two", "https://x.com/a/status/1"), BatchItem("later", "https://youtu.be/123"),
            BatchItem("saved", "https://example.org/a", BatchStatus.QUEUED, taskIds = listOf("existing"))
        ))) }
        withTimeout(3000) { both.await() };job.cancelAndJoin()
        assertEquals(2, stopped);assertFalse(queue.running.value)
        assertEquals(listOf(BatchStatus.CANCELLED, BatchStatus.CANCELLED, BatchStatus.CANCELLED, BatchStatus.QUEUED), queue.plan.value.items.map { it.status })
    }
    @Test fun realWorkerThreadsRetainAllRowsAndRespectTheConcurrencyLimit() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val saved = mutableListOf<BatchPlan>()
        val queue = BatchQueue({ row, _ ->
            val now = active.incrementAndGet();peak.updateAndGet { maxOf(it, now) }
            try { delay(15);listOf("task-${row.id}") } finally { active.decrementAndGet() }
        }, save = { Thread.sleep(1);saved += it })
        withContext(Dispatchers.Default) {
            withTimeout(5000) { queue.run(BatchPlan((1..40).map { BatchItem("$it", "https://host$it.example/video") })) }
        }
        assertEquals(2, peak.get());assertEquals(0, active.get())
        assertEquals(40, queue.plan.value.items.count { it.status == BatchStatus.QUEUED })
        assertEquals((1..40).map { "task-$it" }, queue.plan.value.items.flatMap { it.taskIds })
        assertEquals(queue.plan.value, saved.last())
    }
    @Test fun persistenceFailureStopsBeforeSubmittingAndReleasesRunningState() = runBlocking {
        var called = false
        val queue = BatchQueue({ _, _ -> called = true;listOf("task") }, save = { error("disk full") })
        assertTrue(runCatching { queue.run(BatchPlan(listOf(BatchItem(url = "https://example.org/a")))) }.isFailure)
        assertFalse(called);assertFalse(queue.running.value);assertTrue(queue.plan.value.items.isEmpty())
    }
    private fun video(url: String, id: String) = VideoMetadata(url = url, title = id, mediaId = id,
        availableVideoFormats = listOf(FormatOption("fmt", "1080p", 1920, 1080, acodec = "aac", vcodec = "h264")))
    @Test fun restorationReconcilesMissingTasksWithoutReplayingIntactRows() = runBlocking {
        val calls = mutableListOf<String>()
        val queue = BatchQueue({ row, _ -> calls += row.id; listOf("new-${row.id}") })
        queue.restore(BatchPlan(listOf(
            BatchItem("intact", "a", BatchStatus.QUEUED, taskIds = listOf("saved")),
            BatchItem("partial", "b", BatchStatus.QUEUED, taskIds = listOf("saved", "lost")),
            BatchItem("interrupted", "c", BatchStatus.RESOLVING)
        ))) { it == "saved" }
        assertEquals(listOf(BatchStatus.QUEUED, BatchStatus.CANCELLED, BatchStatus.CANCELLED), queue.plan.value.items.map { it.status })
        queue.run()
        assertEquals(listOf("partial", "interrupted"), calls)
    }
    @Test fun defaultsToRequestedEpisodeAndExpandedJobsKeepStableIdentity() {
        val base = "https://www.bilibili.com/video/BV1xx411c7mD"
        val first = video("$base?p=1", "one");val second = video("$base?p=2", "two")
        val meta = second.copy(multiMediaList = listOf(first, second))
        val row = BatchItem("stable", "$base?p=2")
        val single = BatchTaskFactory.videos(row, meta, BatchOptions())
        assertEquals("two", single.single().mediaId)
        val all = BatchTaskFactory.videos(row, meta, BatchOptions(allEpisodes = true))
        assertEquals(listOf("one", "two"), all.map { it.mediaId })
        assertEquals(all.map { it.id }, BatchTaskFactory.videos(row, meta, BatchOptions(allEpisodes = true)).map { it.id })
        assertEquals(2, all.map { it.id }.distinct().size)
        val reordered = BatchTaskFactory.videos(row, meta.copy(multiMediaList = listOf(second, first)), BatchOptions(allEpisodes = true))
        assertEquals(all.associate { it.mediaId to it.id }, reordered.associate { it.mediaId to it.id })
        assertTrue(all.all { it.requireAudio })
    }
}
