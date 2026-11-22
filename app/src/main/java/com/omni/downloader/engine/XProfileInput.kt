package com.omni.downloader.engine

import com.omni.downloader.data.model.ImagePost
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** Profile links and collection identities are separate from status/video identities. */
object XProfileInput {
    private val hosts = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
    private val reserved = setOf("home", "explore", "search", "settings", "messages", "notifications", "i", "intent", "share", "login", "logout", "signup", "tos", "privacy", "compose", "account")
    fun handle(input: String): String? = runCatching {
        val uri = URI(input)
        if (uri.scheme !in setOf("https", "http") || uri.host?.lowercase() !in hosts || uri.userInfo != null || uri.port != -1) return null
        Regex("/([A-Za-z0-9_]{1,15})(?:/media)?/?").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            ?.takeUnless { it.lowercase() in reserved }
    }.getOrNull()

    fun merge(previous: ImagePost?, page: ImagePost): ImagePost {
        require(page.platform == LinkNormalizer.PLATFORM_X && page.route == "x_profile" && page.profileUserId.matches(Regex("[0-9]+")) &&
            page.contentId == "x-profile-${page.profileUserId}" && handle(page.url) != null) { "[MEDIA_ID_MISMATCH] 主页身份无效" }
        if (previous != null) require(previous.contentId == page.contentId && handle(previous.url)?.lowercase() == handle(page.url)?.lowercase()) { "[MEDIA_ID_MISMATCH] 分页返回了其他用户" }
        require(page.profileComplete || page.profileCursor.isNotBlank()) { "[MEDIA_UNCONFIRMED] 主页缺少下一页游标" }
        if (previous != null && !page.profileComplete) require(page.profileCursor.isNotBlank() && page.profileCursor != previous.profileCursor) { "[MEDIA_UNCONFIRMED] 主页分页未前进" }
        val resources = (previous?.resources.orEmpty() + page.resources).distinctBy { it.id }.distinctBy { it.url }
        require(resources.all { image -> image.id.matches(Regex("[0-9]+:.+")) &&
            runCatching { URI(image.url).let { it.scheme == "https" && it.host == "pbs.twimg.com" && it.path.startsWith("/media/") && it.userInfo == null && it.port == -1 } }.getOrDefault(false) }) { "[MEDIA_ID_MISMATCH] 主页返回了非图片资源" }
        return page.copy(resources = resources)
    }

    /** Persist regular per-status tasks: restart/download never requires rescanning the profile. */
    fun posts(profile: ImagePost): List<ImagePost> {
        merge(null, profile)
        val handle = requireNotNull(handle(profile.url))
        return profile.resources.groupBy { it.id.substringBefore(':') }.map { (id, images) ->
            ImagePost(profile.platform, "https://x.com/$handle/status/$id", id, "@$handle · $id", profile.author,
                images.map { it.copy(headers = mapOf("Referer" to "https://x.com/")) }, "x_profile_post")
        }
    }

    suspend fun collect(previous: ImagePost? = null, pageLimit: Int = 100,
        onPage: suspend (ImagePost) -> Unit = {}, load: suspend (String, String) -> ImagePost): ImagePost {
        require(pageLimit in 1..100)
        var result = previous
        val cursors = mutableSetOf<String>()
        repeat(pageLimit) {
            currentCoroutineContext().ensureActive()
            val cursor = result?.profileCursor.orEmpty()
            check(cursors.add(cursor)) { "[MEDIA_UNCONFIRMED] 主页分页重复，请稍后继续" }
            result = merge(result, load(cursor, result?.profileUserId.orEmpty()))
            currentCoroutineContext().ensureActive()
            onPage(result!!)
            if (result!!.profileComplete) return result!!
            delay(400)
        }
        return requireNotNull(result)
    }
}
