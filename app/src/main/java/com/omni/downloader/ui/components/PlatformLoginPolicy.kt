package com.omni.downloader.ui.components

import com.omni.downloader.data.repository.SessionPlatforms
import com.omni.downloader.data.repository.CookieHeaderParser

data class PlatformLoginConfig(
    val startUrl: String,
    val cookieUrl: String,
    val loginMarkers: List<String>,
    val automaticCapture: Boolean = true
)

fun loginConfigFor(platform: String): PlatformLoginConfig = when (platform) {
    SessionPlatforms.YOUTUBE -> PlatformLoginConfig(
        startUrl = "https://accounts.google.com/ServiceLogin?service=youtube",
        cookieUrl = "https://www.youtube.com/",
        loginMarkers = listOf("SID=", "SAPISID=")
    )
    SessionPlatforms.X -> PlatformLoginConfig(
        startUrl = "https://x.com/i/flow/login",
        cookieUrl = "https://x.com/",
        loginMarkers = listOf("auth_token=")
    )
    SessionPlatforms.XHS -> PlatformLoginConfig(
        startUrl = "https://www.xiaohongshu.com/explore",
        cookieUrl = "https://www.xiaohongshu.com/",
        loginMarkers = listOf("web_session="),
        automaticCapture = false
    )
    SessionPlatforms.HEYBOX -> PlatformLoginConfig(
        startUrl = "https://www.xiaoheihe.cn/",
        cookieUrl = "https://www.xiaoheihe.cn/",
        loginMarkers = listOf("heybox_id=", "user_id=", "sessionid=")
    )
    SessionPlatforms.PIXIV -> PlatformLoginConfig(
        startUrl = "https://accounts.pixiv.net/login",
        cookieUrl = "https://www.pixiv.net/",
        // PHPSESSID 匿名访问也存在，不能作为登录成功的依据，交给用户明确保存会话。
        loginMarkers = emptyList(),
        automaticCapture = false
    )
    "哔哩哔哩" -> PlatformLoginConfig("https://passport.bilibili.com/login", "https://www.bilibili.com/", listOf("SESSDATA="))
    "抖音" -> PlatformLoginConfig("https://www.douyin.com/", "https://www.douyin.com/", emptyList(), automaticCapture = false)
    "Pinterest" -> PlatformLoginConfig("https://www.pinterest.com/login/", "https://www.pinterest.com/", listOf("_pinterest_sess="))
    "Instagram" -> PlatformLoginConfig("https://www.instagram.com/accounts/login/", "https://www.instagram.com/", listOf("sessionid="))
    "Facebook" -> PlatformLoginConfig("https://www.facebook.com/login/", "https://www.facebook.com/", listOf("c_user="))
    "TikTok" -> PlatformLoginConfig("https://www.tiktok.com/login", "https://www.tiktok.com/", listOf("sessionid="))
    "快手" -> PlatformLoginConfig("https://www.kuaishou.com/new-reco", "https://www.kuaishou.com/", emptyList(), automaticCapture = false)
    else -> PlatformLoginConfig(
        startUrl = "https://www.xiaoheihe.cn/",
        cookieUrl = "https://www.xiaoheihe.cn/",
        loginMarkers = emptyList()
    )
}


internal fun canCaptureLoginCookie(config: PlatformLoginConfig, header: String, manual: Boolean): Boolean {
    val pairs = CookieHeaderParser.parse(header).toMap()
    if (pairs.isEmpty()) return false
    if (manual) return true // User explicitly saves an imported session; no account verification is implied.
    if (!config.automaticCapture) return false
    return config.loginMarkers.any { marker -> !pairs[marker.removeSuffix("=")].isNullOrBlank() }
}
