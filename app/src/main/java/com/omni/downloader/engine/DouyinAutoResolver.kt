package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** One bounded enhancement attempt, scoped to the account policy and route. */
internal object DouyinAutoResolver {
    private data class Entry(val metadata: VideoMetadata, val at: Long, val ttl: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val gate = Mutex()
    fun scope(context: Context, proxy: String) = "$proxy|${NetworkProxy.configuredUrl}|${SessionRepository.getInstance(context).requestGeneration(LinkNormalizer.PLATFORM_DOUYIN)}"

    fun invalidate(url: String) {
        val id = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN) ?: return
        synchronized(cache) { cache.keys.removeAll { it.startsWith("$id|") } }
    }

    suspend fun resolve(context: Context, url: String, proxy: String, forceRefresh: Boolean = false,
        onPhase: (String) -> Unit = {},
        budget: DouyinResolutionBudget = DouyinResolutionBudget(),
        onPreview: suspend (VideoMetadata) -> Unit = {},
        enrich: suspend (VideoMetadata) -> VideoMetadata? = { DomesticDirectExtractor.enrichDouyinMetadata(it) },
        native: suspend () -> Result<VideoMetadata>): Result<VideoMetadata> {
        val identity = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN)
            ?: return Result.failure(Exception("[MEDIA_ID_MISMATCH] 无法确认目标作品"))
        val scope = scope(context, proxy)
        val domesticScope = DomesticDirectExtractor.metadataScope(LinkNormalizer.PLATFORM_DOUYIN)
        fun validate() {
            if (scope != scope(context, proxy)) throw CancellationException("解析会话已改变")
        }
        val key = "$identity|$scope"
        if (!forceRefresh) synchronized(cache) { cache[key] }?.takeIf { System.nanoTime() - it.at < it.ttl }?.let {
            validate()
            return Result.success(it.metadata)
        }
        val result = DouyinResolution.resolve(url, budget, gate, native,
            capture = { candidate -> DouyinPageCapture.loader?.captureProgressive(url, proxy, scope) { item ->
                candidate(DouyinVideoParser.parseItem(item, url))
            } },
            confirm = { metadata, millis -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DomesticDirectExtractor.confirmDouyinMetadata(metadata, verifyHighest = true, budgetMillis = millis, remember = false)
            } },
            validate = ::validate, onPhase = onPhase, onPreview = onPreview,
            enrich = enrich)
        currentCoroutineContext().ensureActive(); validate()
        result.getOrNull()?.let { confirmed ->
            DomesticDirectExtractor.rememberMetadata(confirmed, expectedScope = domesticScope)
            synchronized(cache) {
                if (cache.size >= 32) cache.remove(cache.keys.first())
                // Briefly reuse incomplete-but-playable sources too; never cache a failure.
                val ttl = if (confirmed.qualityWarning.isBlank()) 300L else 20L
                cache[key] = Entry(confirmed, System.nanoTime(), TimeUnit.SECONDS.toNanos(ttl))
            }
        }
        return result
    }
}
