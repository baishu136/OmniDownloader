package com.omni.downloader.engine

import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchPreparationTest {
    @Test fun addingPreparesWithoutSubmittingAndAwaitSharesTheInFlightWork() = runTest {
        var calls = 0
        val finish = CompletableDeferred<String>()
        val cache = BatchPreparation(this, { _: String, _: String -> calls++; finish.await() }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf("https://x.com/a/status/1")); runCurrent()
            assertEquals(1, calls)
            val first = async { cache.await("https://x.com/a/status/1") }
            val second = async { cache.await("https://x.com/a/status/1") }
            runCurrent(); assertEquals(1, calls)
            finish.complete("metadata"); runCurrent()
            assertEquals(first.await(), second.await())
            assertEquals(BatchPreparationStatus.READY, cache.states.value.values.single().status)
        } finally { cache.close() }
    }
    @Test fun removingAndReaddingDiscardsLateUncooperativeResultAndUsesFreshToken() = runTest {
        val url = "https://x.com/a/status/1"
        val oldFinish = CompletableDeferred<Unit>()
        val tokens = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val cache = BatchPreparation(this, { _: String, token: String ->
            tokens += token
            if (tokens.size == 1) withContext(NonCancellable) { oldFinish.await(); "old" } else "new"
        }, cancelProcess = { cancelled += it }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf(url)); runCurrent()
            val waiting = async { runCatching { cache.await(url) } }; runCurrent()
            cache.remove(setOf(url)); assertTrue(cache.states.value.isEmpty())
            cache.sync(listOf(url)); runCurrent()
            oldFinish.complete(Unit); runCurrent()
            assertTrue(waiting.await().exceptionOrNull() is BatchLinkRemoved)
            assertEquals("new", cache.await(url).value)
            assertEquals(2, tokens.size); assertNotEquals(tokens[0], tokens[1])
            assertTrue(tokens.first() in cancelled)
            assertFalse(cache.isCurrent(url, tokens.first()))
        } finally { cache.close(); oldFinish.complete(Unit) }
    }
    @Test fun removalAfterReadyRejectsThePreviouslyPreparedAdmission() = runTest {
        val url = "https://x.com/a/status/1"
        val cache = BatchPreparation(this, { _: String, _: String -> "ready" }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf(url)); runCurrent()
            val prepared = cache.await(url)
            cache.remove(setOf(url)); cache.sync(listOf(url)); runCurrent()
            var submitted = false
            assertTrue(runCatching { cache.withCurrent(prepared) { submitted = true } }.exceptionOrNull() is BatchLinkRemoved)
            assertFalse(submitted)
        } finally { cache.close() }
    }
    @Test fun accountProxyChangeAndExpiredResultsRequireNewResolution() = runTest {
        var key = "account-a:proxy-a"; var calls = 0; var now = 0L
        val url = "https://x.com/a/status/1"
        val cache = BatchPreparation(this, { _: String, _: String -> ++calls }, scopeKey = { key },
            clock = { now }, ttlMillis = 30, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf(url)); runCurrent()
            val original = cache.await(url); assertEquals(1, original.value)
            key = "account-b:proxy-b"
            assertFalse(cache.isCurrent(url, original.token))
            cache.sync(listOf(url)); runCurrent(); assertEquals(2, cache.await(url).value)
            now = 30
            val refreshed = async { cache.await(url) }; runCurrent(); assertEquals(3, refreshed.await().value)
        } finally { cache.close() }
    }
    @Test fun totalParallelismIsTwoAndWaitingSamePlatformDoesNotStarveAnotherPlatform() = runTest {
        val finish = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>(); var active = 0; var peak = 0
        val cache = BatchPreparation(this, { url: String, _: String ->
            entered += url; active++; peak = maxOf(peak, active)
            try { finish.await(); url } finally { active-- }
        }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val urls = listOf("https://x.com/a/status/1", "https://x.com/a/status/2", "https://b23.tv/a", "https://youtu.be/b")
            cache.sync(urls); runCurrent()
            assertEquals(listOf(urls[0], urls[2]), entered)
            finish.complete(Unit); advanceUntilIdle()
            assertEquals(4, entered.size); assertEquals(2, peak)
        } finally { cache.close() }
    }
    @Test fun failureStaysVisibleWithoutRetryStormAndExplicitAwaitRetries() = runTest {
        var calls = 0; val url = "https://x.com/a/status/1"
        val cache = BatchPreparation(this, { _: String, _: String -> if (++calls == 1) error("offline") else "ready" }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf(url)); runCurrent()
            assertEquals(BatchPreparationStatus.FAILED, cache.states.value[url]?.status)
            repeat(3) { cache.sync(listOf(url)); runCurrent() }; assertEquals(1, calls)
            val retry = async { cache.await(url) }; runCurrent(); assertEquals("ready", retry.await().value)
        } finally { cache.close() }
    }
    @Test fun assignedLinksDoNotPrefetchAndStopDoesNotRestartOnUnchangedDraft() = runTest {
        var calls = 0; val url = "https://x.com/a/status/1"
        val cache = BatchPreparation(this, { _: String, _: String -> calls++; awaitCancellation() }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            cache.sync(listOf(url), setOf(url)); runCurrent(); assertEquals(0, calls)
            cache.sync(listOf(url)); runCurrent(); assertEquals(1, calls)
            cache.stop(); runCurrent(); cache.sync(listOf(url)); runCurrent()
            assertEquals(1, calls)
            assertEquals(BatchPreparationStatus.CANCELLED, cache.states.value[url]?.status)
            cache.sync(emptyList()); assertTrue(cache.states.value.isEmpty())
        } finally { cache.close() }
    }
    @Test fun completionRequiresEveryEpisodeAndSurvivesIndependentHistoryClearing() {
        val bindings = mapOf("collection" to BatchLinkTasks("row", listOf("1", "2")), "empty" to BatchLinkTasks("empty", emptyList()))
        for (status in TaskStatus.entries.filter { it != TaskStatus.COMPLETED }) {
            assertTrue(BatchCompletion.completed(listOf("collection", "empty"), bindings) { if (it == "1") TaskStatus.COMPLETED else status }.isEmpty())
        }
        assertTrue(BatchCompletion.completed(listOf("collection"), bindings) { if (it == "1") TaskStatus.COMPLETED else null }.isEmpty())
        assertEquals(setOf("collection"), BatchCompletion.completed(listOf("collection", "empty", "unbound"), bindings) { TaskStatus.COMPLETED })
        // The durable bindings carry completion independently of a cleared BatchPlan.
        assertEquals(setOf("collection"), BatchCompletion.completed(listOf("collection"), bindings) { TaskStatus.COMPLETED })
    }
    @Test fun retiredLinkCanBeReaddedAsNewWorkInsteadOfReusingCompletedRow() {
        val old = BatchItem("old", "https://example.org/a", BatchStatus.QUEUED, taskIds = listOf("completed"), inputRetired = true)
        val incoming = BatchItem("new", old.url)
        assertEquals(listOf(incoming), BatchInput.preservePending(BatchPlan(listOf(old)), BatchPlan(listOf(incoming))).items)
    }
    @Test fun removingQueuedAndWaitingRowsDuringRunDoesNotRestoreThem() = runTest {
        val finish = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
        val queue = BatchQueue({ row, _ -> calls += row.id; if (row.id == "a") finish.await(); listOf(row.id) }, parallelism = 1)
        val work = launch { queue.run(BatchPlan(listOf(BatchItem("a", "https://example.org/a"), BatchItem("b", "https://example.org/b")))) }
        runCurrent(); queue.removeInputs(setOf("https://example.org/a", "https://example.org/b"))
        finish.complete(Unit); work.join()
        assertEquals(listOf("a"), calls); assertTrue(queue.plan.value.items.isEmpty())
    }
    @Test fun oldBatchCannotSubmitALinkRemovedAndReaddedBeforeTheBatchStarts() = runTest {
        val url = "https://example.org/a"
        val old = BatchItem("old-generation", url)
        var calls = 0
        val queue = BatchQueue({ _, _ -> calls++; listOf("task") },
            include = { BatchInput.current(it, listOf(url), mapOf(url to "new-generation")) })
        queue.run(BatchPlan(listOf(old)))
        assertEquals(0, calls); assertTrue(queue.plan.value.items.isEmpty())
        queue.run(BatchPlan(listOf(old.copy(id = "new-generation"))))
        assertEquals(1, calls)
    }
}
