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
        val queue = BatchQueue({ row, _ -> if (row.id == "slow") { entered.complete(Unit); awaitCancellation() }; listOf(row.id) })
        val job = launch { queue.run(BatchPlan(listOf(BatchItem("one", "a"), BatchItem("slow", "b"), BatchItem("later", "c")))) }
        entered.await();job.cancelAndJoin()
        assertEquals(listOf(BatchStatus.QUEUED, BatchStatus.CANCELLED, BatchStatus.CANCELLED), queue.plan.value.items.map { it.status })
        assertFalse(queue.running.value)
        val recovered = BatchQueue({ row, _ -> listOf(row.id) })
        recovered.restore(queue.plan.value);recovered.run()
        assertTrue(recovered.plan.value.items.all { it.status == BatchStatus.QUEUED })
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
