package com.omni.downloader.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.UUID

enum class BatchPreparationStatus { WAITING, RESOLVING, READY, FAILED, CANCELLED }
data class BatchPreparationInfo(val status: BatchPreparationStatus, val note: String)
class BatchLinkRemoved : IllegalStateException("链接已移除，解析结果已丢弃")

/** Memory-only preflight. Removed/re-added URLs always have different request identities. */
internal class BatchPreparation<T>(
    scope: CoroutineScope,
    private val resolve: suspend (String, String) -> T,
    private val scopeKey: (String) -> String = { "" },
    private val cancelProcess: (String) -> Unit = {},
    private val onActive: (String, Boolean) -> Unit = { _, _ -> },
    private val describe: (T) -> String = { "已解析" },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 30_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    data class Prepared<T>(val url: String, val token: String, val value: T)
    private class Entry<T>(val key: String, val token: String) {
        lateinit var work: Deferred<T>
        var readyAt: Long? = null
    }
    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry<T>>()
    private var desired = emptySet<String>()
    private val permits = Semaphore(2)
    private val platforms = mutableMapOf<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, BatchPreparationInfo>>(emptyMap())
    val states = _states.asStateFlow()

    private fun group(url: String) = LinkNormalizer.platformOf(url).let {
        if (it != LinkNormalizer.PLATFORM_GENERIC) it
        else runCatching { URI(url).host.lowercase(java.util.Locale.ROOT) }.getOrDefault(url)
    }
    private fun state(url: String, entry: Entry<T>, value: BatchPreparationInfo) = synchronized(lock) {
        if (entries[url] === entry && url in desired) _states.value = _states.value + (url to value)
    }
    private fun retire(url: String) {
        entries.remove(url)?.let { it.work.cancel(); cancelProcess(it.token) }
        _states.value = _states.value - url
    }
    private fun create(url: String, key: String): Entry<T> {
        val entry = Entry<T>(key, "batch-prepare-${UUID.randomUUID()}")
        val platform = platforms.getOrPut(group(url)) { Mutex() }
        entries[url] = entry
        entry.work = owner.async(dispatcher, start = CoroutineStart.LAZY) {
            try {
                // Acquire the platform first, so waiting links from one platform cannot occupy both slots.
                platform.withLock { permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(url, entry.token)) throw BatchLinkRemoved()
                    state(url, entry, BatchPreparationInfo(BatchPreparationStatus.RESOLVING, "正在提前解析…"))
                    onActive(entry.token, true)
                    try {
                        val result = resolve(url, entry.token)
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            if (!isCurrent(url, entry.token)) throw BatchLinkRemoved()
                            entry.readyAt = clock()
                            state(url, entry, BatchPreparationInfo(BatchPreparationStatus.READY, describe(result)))
                        }
                        result
                    } finally { onActive(entry.token, false) }
                } }
            } catch (cancelled: CancellationException) {
                state(url, entry, BatchPreparationInfo(BatchPreparationStatus.CANCELLED, "预解析已停止"))
                throw cancelled
            } catch (error: Exception) {
                state(url, entry, BatchPreparationInfo(BatchPreparationStatus.FAILED,
                    LogSanitizer.sanitize(error.message.orEmpty()).take(180).ifBlank { "预解析失败，可重试" }))
                throw error
            } finally { cancelProcess(entry.token) }
        }
        state(url, entry, BatchPreparationInfo(BatchPreparationStatus.WAITING, "等待提前解析"))
        entry.work.start()
        return entry
    }

    fun sync(urls: List<String>, assigned: Set<String> = emptySet()) = synchronized(lock) {
        desired = urls.toSet()
        entries.keys.filter { it !in desired || it in assigned || entries[it]?.key != scopeKey(it) }.toList().forEach(::retire)
        for (url in urls) if (url !in assigned && url !in entries) create(url, scopeKey(url))
    }

    suspend fun await(url: String): Prepared<T> {
        val entry = synchronized(lock) {
            if (url !in desired) throw BatchLinkRemoved()
            val old = entries[url]
            val expired = old?.readyAt?.let { clock() - it >= ttlMillis } == true
            if (old == null || old.key != scopeKey(url) || expired || old.work.isCancelled) {
                retire(url); create(url, scopeKey(url))
            } else old
        }
        val result = try { entry.work.await() }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(url, entry.token)) throw BatchLinkRemoved()
            throw cancelled
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent(url, entry.token)) throw BatchLinkRemoved()
        return Prepared(url, entry.token, result)
    }

    fun isCurrent(url: String, token: String): Boolean = synchronized(lock) {
        url in desired && entries[url]?.let { it.token == token && it.key == scopeKey(url) } == true
    }
    fun <R> withCurrent(prepared: Prepared<T>, action: () -> R): R = synchronized(lock) {
        if (!isCurrent(prepared.url, prepared.token)) throw BatchLinkRemoved()
        action()
    }
    fun remove(urls: Set<String>) = synchronized(lock) {
        desired = desired - urls
        urls.forEach(::retire)
    }
    fun stop() = synchronized(lock) {
        entries.values.filter { !it.work.isCompleted }.forEach { it.work.cancel(); cancelProcess(it.token) }
    }
    fun close() {
        synchronized(lock) { desired = emptySet(); entries.keys.toList().forEach(::retire) }
        owner.cancel()
    }
}
