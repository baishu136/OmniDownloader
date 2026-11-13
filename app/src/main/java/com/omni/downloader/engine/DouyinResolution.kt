package com.omni.downloader.engine

import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One monotonic deadline follows the request through redirects, queueing and enhancement. */
internal class DouyinResolutionBudget(
    val deadlineNanos: Long = System.nanoTime() + TOTAL_MILLIS * 1_000_000,
    private val clock: () -> Long = System::nanoTime
) {
    fun remainingMillis() = ((deadlineNanos - clock()) / 1_000_000).coerceAtLeast(0)
    companion object {
        const val TOTAL_MILLIS = 15_000L
        const val NATIVE_MILLIS = 3_500L
        const val DETAIL_MILLIS = 1_200L
        const val PROBE_MILLIS = 1_500L
    }
}

/** Platform-independent orchestration; partial results survive our deadline, never user cancellation. */
internal object DouyinResolution {
    fun redirectTarget(result: LinkNormalizer.RedirectResult): String {
        val target = when (result) {
            is LinkNormalizer.RedirectResult.Content -> result.url
            is LinkNormalizer.RedirectResult.LoginRequired -> result.contentUrl
                ?: error("[AUTH_REQUIRED] 抖音分享页要求登录，请完成后重试")
            is LinkNormalizer.RedirectResult.ChallengeRequired -> result.contentUrl
                ?: error("[CHALLENGE_REQUIRED] 抖音分享页要求验证，请完成后重试")
            is LinkNormalizer.RedirectResult.Unavailable -> if (result.statusCode in setOf(404, 410))
                error("[CONTENT_UNAVAILABLE] 抖音分享链接已不可用")
                else error("[NETWORK_ERROR] 抖音分享链接未能在时间预算内展开，请检查网络后重试")
        }
        check(LinkNormalizer.platformOf(target) == LinkNormalizer.PLATFORM_DOUYIN) { "[MEDIA_ID_MISMATCH] 分享链接跳转到了其他平台" }
        check(LinkNormalizer.contentId(target, LinkNormalizer.PLATFORM_DOUYIN) != null) {
            "[NETWORK_ERROR] 抖音分享链接尚未返回目标作品地址，请重试"
        }
        return LinkNormalizer.normalize(target).contentUrl
    }

    suspend fun resolve(
        url: String,
        budget: DouyinResolutionBudget,
        gate: Mutex,
        native: suspend () -> Result<VideoMetadata>,
        capture: suspend (suspend (VideoMetadata) -> Unit) -> DouyinPageResult?,
        confirm: suspend (VideoMetadata, Long) -> VideoMetadata,
        validate: () -> Unit = {},
        onPhase: (String) -> Unit = {},
        onPreview: suspend (VideoMetadata) -> Unit = {},
        enrich: suspend (VideoMetadata) -> VideoMetadata? = { null }
    ): Result<VideoMetadata> {
        var metadata: VideoMetadata? = null
        var nativeError: Throwable? = null
        var status = DouyinPageStatus.UNAVAILABLE
        var checkedPage = false
        var probeTimedOut = false
        suspend fun accept(candidate: VideoMetadata) {
            currentCoroutineContext().ensureActive(); validate()
            check(candidate.mediaId == LinkNormalizer.contentId(url, LinkNormalizer.PLATFORM_DOUYIN) &&
                candidate.siteName == LinkNormalizer.PLATFORM_DOUYIN) { "[MEDIA_ID_MISMATCH] 解析结果不属于目标作品" }
            metadata = DouyinQualityPolicy.merge(metadata, candidate)
            onPreview(metadata!!.copy(qualityWarning = "正在补全可用画质，可先选择下载。"))
        }
        val finished = withTimeoutOrNull(budget.remainingMillis()) {
            gate.withLock {
                validate()
                onPhase("正在读取视频信息…")
                val first = withTimeoutOrNull(minOf(DouyinResolutionBudget.NATIVE_MILLIS, budget.remainingMillis())) { native() }
                nativeError = first?.exceptionOrNull()
                if (nativeError is CancellationException) throw nativeError as CancellationException
                first?.getOrNull()?.let { accept(it) }
                if (metadata == null && MediaErrors.codeOf(nativeError ?: Exception()) in setOf(
                        MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_ID_MISMATCH,
                        MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.RATE_LIMITED)) return@withLock

                // Keep the same-target detail route, but never put it before the first usable result.
                metadata?.let { initial ->
                    val extra = withTimeoutOrNull(minOf(DouyinResolutionBudget.DETAIL_MILLIS, budget.remainingMillis())) {
                        enrich(initial)
                    }
                    if (extra != null) accept(extra)
                }

                suspend fun enhance() {
                    checkedPage = true
                    onPhase("正在自动补全画质…")
                    val page = try { capture { accept(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DouyinPageResult(DouyinPageStatus.UNAVAILABLE) }
                    if (page != null) {
                        status = page.status
                        if (page.status == DouyinPageStatus.SCOPE_CHANGED) throw CancellationException("解析会话已改变")
                        page.item?.let { accept(DouyinVideoParser.parseItem(it, url)) }
                    }
                }
                if (DouyinQualityPolicy.needsPage(metadata)) enhance()
                metadata?.let {
                    onPhase("正在核对视频尺寸…")
                    val measured = withTimeoutOrNull(minOf(DouyinResolutionBudget.PROBE_MILLIS, budget.remainingMillis())) {
                        confirm(it, minOf(DouyinResolutionBudget.PROBE_MILLIS, budget.remainingMillis()))
                    }
                    if (measured != null) metadata = measured
                    else probeTimedOut = true
                    currentCoroutineContext().ensureActive(); validate()
                }
                // A measured rendition can be smaller than its advertised dimensions.
                if (!checkedPage && DouyinQualityPolicy.needsPage(metadata)) {
                    enhance()
                    metadata?.let { candidate ->
                        val measured = withTimeoutOrNull(minOf(DouyinResolutionBudget.PROBE_MILLIS, budget.remainingMillis())) {
                            confirm(candidate, minOf(DouyinResolutionBudget.PROBE_MILLIS, budget.remainingMillis()))
                        }
                        if (measured != null) metadata = measured
                        else probeTimedOut = true
                    }
                }
            }
            true
        }
        val timedOut = finished == null
        currentCoroutineContext().ensureActive(); validate()
        val best = metadata ?: return Result.failure(nativeError?.takeIf {
            MediaErrors.codeOf(it) in setOf(MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_ID_MISMATCH,
                MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.RATE_LIMITED)
        } ?: Exception(when (status) {
            DouyinPageStatus.AUTH_REQUIRED -> "[AUTH_REQUIRED] 平台要求登录，请在内置浏览器完成后重试"
            DouyinPageStatus.CHALLENGE_REQUIRED -> "[CHALLENGE_REQUIRED] 平台要求验证，请在内置浏览器完成后重试"
            else -> "[BROWSER_ASSIST_REQUIRED] 自动提取未取得目标视频，可在应用前台重试或打开浏览器验证"
        }))
        val warning = when {
            timedOut || status == DouyinPageStatus.TIMEOUT -> "高清补全未完成，已保留当前可用源；可稍后重试。"
            status in setOf(DouyinPageStatus.AUTH_REQUIRED, DouyinPageStatus.CHALLENGE_REQUIRED) ->
                "平台要求登录或验证，已保留当前可用源；更高画质尚未确认。"
            DouyinQualityPolicy.needsPage(best) -> "已保留当前可用源；更高画质尚未确认，可稍后重试。"
            probeTimedOut -> "尺寸核对未完成，已保留平台提供的视频源。"
            else -> ""
        }
        val top = best.availableVideoFormats.firstOrNull()
        return Result.success(best.copy(qualityWarning = warning,
            qualityEvidence = "本次最高规格：${top?.resolutionLabel.orEmpty()} · " +
                if (top?.dimensionsVerified == true) "尺寸已实测" else "平台标注，尺寸暂未实测",
            accessEvidence = best.accessEvidence + "DOUYIN_PAGE_${if (timedOut) "TIMEOUT" else if (checkedPage) status.name else "NOT_NEEDED"}"))
    }
}
