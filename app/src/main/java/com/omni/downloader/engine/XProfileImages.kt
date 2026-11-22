package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.ImagePost
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.json.JSONObject

internal object XProfileImages {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun manifest(post: ImagePost) = JSONObject(json.encodeToString(post))
    fun scope(context: Context, proxy: String) = "${SessionRepository.getInstance(context).requestGeneration(LinkNormalizer.PLATFORM_X)}|$proxy|${NetworkProxy.configuredUrl}"
    suspend fun fetch(context: Context, url: String, proxy: String, processId: String,
        previous: ImagePost? = null, onPage: suspend (ImagePost) -> Unit = {}): ImagePost {
        val handle = requireNotNull(XProfileInput.handle(url))
        val capturedScope = scope(context, proxy)
        // One run is bounded. A large archive may continue from the last confirmed page.
        return XProfileInput.collect(previous, onPage = onPage) { cursor, user ->
            currentCoroutineContext().ensureActive()
            val raw = SharedPython.run(context, "x-profile-page", "https://x.com/$handle", LinkNormalizer.PLATFORM_X, proxy,
                processId, listOf("--cursor", cursor, "--expected-user", user))
            currentCoroutineContext().ensureActive()
            check(scope(context, proxy) == capturedScope) { "[AUTH_REQUIRED] X 会话已改变，请重新解析主页" }
            json.decodeFromString<ImagePost>(raw.toString())
        }
    }
}
