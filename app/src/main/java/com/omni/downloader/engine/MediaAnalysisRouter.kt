package com.omni.downloader.engine

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MediaPage { VIDEO, IMAGES }

data class MediaNavigationRequest(val generation: Long, val destination: MediaPage)

sealed interface ParsedContent<out V, out I> {
    data class Video<V>(val metadata: V) : ParsedContent<V, Nothing>
    data class Images<I>(val post: I) : ParsedContent<Nothing, I>
}

/** An ambiguous link switches pages only after the other extractor returns
 * verified content. Login, access and network failures never trigger a retry. */
class MediaAnalysisRouter<V, I>(
    private val video: suspend (String) -> V,
    private val images: suspend (String) -> I,
) {
    suspend fun analyze(url: String, origin: MediaPage, onHint: (MediaPage) -> Unit = {}): ParsedContent<V, I> {
        val hint = knownPage(url)
        val first = hint ?: if (origin == MediaPage.IMAGES && !supportsImages(url)) MediaPage.VIDEO else origin
        if (hint != null && hint != origin) onHint(hint)
        return try {
            resolve(first, url)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val code = MediaErrors.codeOf(error)
            // On shared video/photo paths one extractor may not find the
            // target in its own state schema. The alternative must independently
            // verify the same target; no mismatched response data is reused.
            val uncertainIdentity = hint == null && supportsImages(url) && code == MediaErrorCode.MEDIA_ID_MISMATCH
            if (error is CancellationException || (code !in setOf(MediaErrorCode.NO_MEDIA, MediaErrorCode.MEDIA_UNCONFIRMED) && !uncertainIdentity)) throw error
            val other = if (first == MediaPage.VIDEO) MediaPage.IMAGES else MediaPage.VIDEO
            if (other == MediaPage.IMAGES && !supportsImages(url)) throw error
            try {
                resolve(other, url)
            } catch (second: Exception) {
                currentCoroutineContext().ensureActive()
                if (second is CancellationException || MediaErrors.codeOf(second) != MediaErrorCode.NO_MEDIA) throw second
                if (uncertainIdentity) throw error
                throw IllegalStateException("[NO_MEDIA] 未找到该作品可解析的视频或图文内容", second)
            }
        }
    }

    private suspend fun resolve(page: MediaPage, url: String): ParsedContent<V, I> {
        val content = when (page) {
            MediaPage.VIDEO -> ParsedContent.Video(video(url))
            MediaPage.IMAGES -> ParsedContent.Images(images(url))
        }
        currentCoroutineContext().ensureActive()
        return content
    }

    companion object {
        private val imagePlatforms = setOf(
            LinkNormalizer.PLATFORM_DOUYIN, LinkNormalizer.PLATFORM_KUAISHOU,
            LinkNormalizer.PLATFORM_BILIBILI, LinkNormalizer.PLATFORM_PIXIV,
            LinkNormalizer.PLATFORM_XHS, LinkNormalizer.PLATFORM_X, LinkNormalizer.PLATFORM_PINTEREST,
        )

        fun supportsImages(url: String) = LinkNormalizer.platformOf(url) in imagePlatforms

        /** Keep the working photo extractor's share URL, including host and
         * query context. Canonical video URLs can lose photo share metadata. */
        fun imageInput(originalUrl: String, resolvedUrl: String): String {
            val platform = LinkNormalizer.platformOf(originalUrl)
            return if (platform in imagePlatforms && platform == LinkNormalizer.platformOf(resolvedUrl)) originalUrl else resolvedUrl
        }

        fun knownPage(input: String): MediaPage? {
            val link = LinkNormalizer.normalize(input)
            val uri = runCatching { URI(link.contentUrl) }.getOrNull() ?: return null
            val path = uri.path.orEmpty().lowercase()
            return when (link.platform) {
                LinkNormalizer.PLATFORM_YOUTUBE -> MediaPage.VIDEO
                LinkNormalizer.PLATFORM_TIKTOK -> if (path.contains("/video/")) MediaPage.VIDEO else null
                LinkNormalizer.PLATFORM_DOUYIN -> when {
                    path.contains("/note/") -> MediaPage.IMAGES
                    path.contains("/video/") -> MediaPage.VIDEO
                    else -> null
                }
                LinkNormalizer.PLATFORM_BILIBILI -> when {
                    path.contains("/video/") || path.contains("/bangumi/") -> MediaPage.VIDEO
                    uri.host?.equals("t.bilibili.com", true) == true || path.contains("/opus/") -> MediaPage.IMAGES
                    else -> null
                }
                // Ugoira is confirmed by the image extractor and then handed to
                // the existing video/GIF workflow through the bounded fallback.
                LinkNormalizer.PLATFORM_PIXIV -> if (path.contains("/artworks/")) MediaPage.IMAGES else null
                LinkNormalizer.PLATFORM_X -> when {
                    Regex("/status/\\d+/photo/\\d+").containsMatchIn(path) -> MediaPage.IMAGES
                    Regex("/status/\\d+/video/\\d+").containsMatchIn(path) -> MediaPage.VIDEO
                    else -> null
                }
                else -> null
            }
        }
    }
}
