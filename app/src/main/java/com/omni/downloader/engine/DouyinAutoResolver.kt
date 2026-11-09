package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        native: suspend () -> Result<VideoMetadata>): Result<VideoMetadata> = gate.withLock {
        val identity = LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN)
            ?: return@withLock Result.failure(Exception("[MEDIA_ID_MISMATCH] 无法确认目标作品"))
        val scope = scope(context, proxy)
        val domesticScope = DomesticDirectExtractor.metadataScope(LinkNormalizer.PLATFORM_DOUYIN)
        val key = "$identity|$scope"
        if (!forceRefresh) synchronized(cache) { cache[key] }?.takeIf { System.nanoTime() - it.at < it.ttl }?.let {
            return@withLock Result.success(it.metadata)
        }
        onPhase("正在读取视频信息…")
        val nativeResult = native()
        var metadata = nativeResult.getOrElse { error ->
            if (error is CancellationException) throw error
            if (MediaErrors.codeOf(error) in setOf(MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_ID_MISMATCH,
                    MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.RATE_LIMITED)) return@withLock Result.failure(error)
            null
        }
        currentCoroutineContext().ensureActive()
        var status = DouyinPageStatus.UNAVAILABLE
        val needsPage = DouyinQualityPolicy.needsPage(metadata)
        if (needsPage) {
            onPhase("正在自动补全画质…")
            val result = try { DouyinPageCapture.loader?.capture(url, proxy, scope) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { DouyinPageResult(DouyinPageStatus.UNAVAILABLE) }
            if (result != null) {
                status = result.status
                if (result.status == DouyinPageStatus.FOUND && result.item != null) {
                    val parsed = DouyinVideoParser.parseItem(result.item, url)
                    metadata = DouyinQualityPolicy.merge(metadata, parsed)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (scope != scope(context, proxy)) throw CancellationException("解析会话已改变")
        if (metadata == null) {
            val code = when (status) {
                DouyinPageStatus.AUTH_REQUIRED -> "AUTH_REQUIRED"
                DouyinPageStatus.CHALLENGE_REQUIRED -> "CHALLENGE_REQUIRED"
                else -> when (MediaErrors.codeOf(nativeResult.exceptionOrNull() ?: Exception())) {
                    MediaErrorCode.AUTH_REQUIRED -> "AUTH_REQUIRED"
                    MediaErrorCode.CHALLENGE_REQUIRED -> "CHALLENGE_REQUIRED"
                    else -> "BROWSER_ASSIST_REQUIRED"
                }
            }
            val detail = if (code == "BROWSER_ASSIST_REQUIRED") "自动提取未取得目标视频，可在应用前台重试或打开浏览器验证" else "平台要求验证，请在内置浏览器完成后重试"
            return@withLock Result.failure(Exception("[$code] $detail"))
        }
        onPhase("正在核对视频尺寸…")
        var confirmed = DomesticDirectExtractor.confirmDouyinMetadata(metadata!!, verifyHighest = true, budgetMillis = 12_000, remember = false)
        currentCoroutineContext().ensureActive()
        if (scope != scope(context, proxy)) throw CancellationException("解析会话已改变")
        val warning = when (status) {
            DouyinPageStatus.AUTH_REQUIRED, DouyinPageStatus.CHALLENGE_REQUIRED -> "平台要求登录或验证，已保留当前可用源；更高画质尚未确认。"
            DouyinPageStatus.TIMEOUT -> "自动画质补全超时，已保留当前可用源；可稍后重试。"
            DouyinPageStatus.UNAVAILABLE -> if (DouyinQualityPolicy.needsPage(confirmed)) "已保留当前可用源；自动页面补全需在应用前台、允许本站 Cookie，并使用兼容的系统 WebView。" else ""
            else -> if (DouyinQualityPolicy.needsPage(confirmed)) "已自动检查当前可用源；平台可能仍有未返回的画质，本次结果不代表上传原片。" else ""
        }
        val best = confirmed.availableVideoFormats.firstOrNull()
        val evidence = "本次最高规格：${best?.resolutionLabel.orEmpty()} · " +
            if (best?.dimensionsVerified == true) "尺寸已实测" else "平台标注，尺寸暂未实测"
        confirmed = confirmed.copy(qualityWarning = warning, qualityEvidence = evidence,
            accessEvidence = confirmed.accessEvidence + "DOUYIN_PAGE_${if (needsPage) status.name else "NOT_NEEDED"}")
        DomesticDirectExtractor.rememberMetadata(confirmed, expectedScope = domesticScope)
        if (status == DouyinPageStatus.FOUND || !DouyinQualityPolicy.needsPage(confirmed)) synchronized(cache) {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[key] = Entry(confirmed, System.nanoTime(), TimeUnit.SECONDS.toNanos(if (warning.isBlank() && best?.dimensionsVerified == true) 300 else 45))
        }
        Result.success(confirmed)
    }
}
