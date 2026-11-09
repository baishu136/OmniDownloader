package com.omni.downloader.ui.components

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.engine.LinkNormalizer
import java.security.MessageDigest

/** A separate WebView cookie/storage profile for each account-policy generation. */
internal class DouyinBrowserProfile private constructor(val name: String, val cookies: CookieManager,
    private val sessions: SessionRepository) {
    fun bind(web: WebView) { WebViewCompat.setProfile(web, name) }
    fun restore(onReady: () -> Unit) {
        // Keep newer verification cookies in this exact isolated session.
        if (!cookies.getCookie("https://www.douyin.com/").isNullOrBlank()) onReady()
        else sessions.restoreBrowserCookies(LinkNormalizer.PLATFORM_DOUYIN, cookies, onReady)
    }

    companion object {
        fun current(context: Context): DouyinBrowserProfile? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
            val sessions = SessionRepository.getInstance(context)
            val key = sessions.requestGeneration(LinkNormalizer.PLATFORM_DOUYIN) + ":" +
                (sessions.info(LinkNormalizer.PLATFORM_DOUYIN)?.importedAt ?: 0)
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            val name = "omni_douyin_$hash"
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith("omni_douyin_") && it != name }.forEach {
                // Loaded profiles cannot be deleted until their WebViews/process
                // are released. They are never reused by another generation.
                runCatching { store.deleteProfile(it) }
            }
            return DouyinBrowserProfile(name, store.getOrCreateProfile(name).cookieManager, sessions)
        }
    }
}
