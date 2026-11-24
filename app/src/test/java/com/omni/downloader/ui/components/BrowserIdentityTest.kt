package com.omni.downloader.ui.components

import org.junit.Assert.*
import org.junit.Test

class BrowserIdentityTest {
    @Test fun pixivUsesLiveLoginEndpointAndRequiresExplicitSessionSave() {
        val config = loginConfigFor("Pixiv")
        assertEquals("https://accounts.pixiv.net/login", config.startUrl)
        assertEquals("https://www.pixiv.net/", config.cookieUrl)
        assertFalse(config.automaticCapture)
        assertFalse(canCaptureLoginCookie(config, "PHPSESSID=anonymous; p_ab_id=tracking", false))
    }
    @Test fun desktopAgentKeepsInstalledEngineAndRemovesPhoneIdentity() {
        val result = BrowserIdentity.desktopUserAgent("Mozilla/5.0 (Linux; Android 15; V2352A; wv) AppleWebKit/537.36 Version/4.0 Chrome/145.0.7620.41 Mobile Safari/537.36")
        assertTrue(result.contains("Chrome/145.0.7620.41"))
        assertTrue(result.contains("Windows NT 10.0; Win64; x64"))
        for (marker in listOf("Android", "Mobile", "wv", "Version/4.0")) assertFalse(result.contains(marker))
    }
    @Test fun mobileAgentRemovesWebViewMarkerAndKeepsAndroidChrome() {
        val result = BrowserIdentity.mobileUserAgent("Mozilla/5.0 (Linux; Android 15; V2352A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7620.41 Mobile Safari/537.36")
        assertTrue(result.contains("Android"))
        assertTrue(result.contains("Mobile"))
        assertTrue(result.contains("Chrome/145.0.7620.41"))
        assertFalse(result.contains("; wv"))
        assertFalse(result.contains("Version/4.0"))
    }
    @Test fun websitePolicyUsesHostBoundaries() {
        assertTrue(BrowserIdentity.prefersDesktopUrl("https://www.xiaohongshu.com/explore"))
        assertTrue(BrowserIdentity.prefersDesktopUrl("https://passport.kuaishou.com/login"))
        assertTrue(BrowserIdentity.prefersDesktopUrl("https://www.douyin.com/"))
        assertTrue(BrowserIdentity.prefersDesktopUrl("https://xhslink.cn/o/fixture"))
        assertTrue(BrowserIdentity.prefersDesktopUrl("https://b23.tv/fixture"))
        assertFalse(BrowserIdentity.prefersDesktopUrl("https://xiaohongshu.com.evil.invalid/"))
        assertFalse(BrowserIdentity.prefersDesktopUrl("https://example.invalid/?next=https://douyin.com"))
        assertFalse(BrowserIdentity.prefersDesktopUrl("https://www.youtube.com/"))
        assertFalse(BrowserIdentity.prefersDesktop("YouTube"))
        assertFalse(BrowserIdentity.prefersDesktop("Instagram"))
    }
    @Test fun facebookInstagramAndWeiboLoginConfigsAvoidKnownErrorRoutes() {
        assertEquals("https://www.facebook.com/", loginConfigFor("Facebook").startUrl)
        assertEquals("https://www.instagram.com/accounts/login/", loginConfigFor("Instagram").startUrl)
        assertEquals("https://weibo.com/", loginConfigFor("微博").startUrl)
    }
    @Test fun httpErrorAndRawJsonRecoverySwitchToSafeFallbackOnce() {
        assertEquals(
            "https://www.instagram.com/",
            LoginPageRecovery.httpErrorFallbackUrl("Instagram", "https://www.instagram.com/accounts/login/", 400, 0)
        )
        assertNull(LoginPageRecovery.httpErrorFallbackUrl("Instagram", "https://www.instagram.com/accounts/login/", 400, 1))
        val kuaishouJson = """{"result":2,"error_msg":null,"request_id":"795476988331238723"}"""
        assertTrue(LoginPageRecovery.looksLikeRawErrorJsonPayload(kuaishouJson))
        assertEquals(
            LoginPageRecovery.KUAISHOU_PASSPORT_URL,
            LoginPageRecovery.jsonPayloadFallbackUrl("快手", "https://www.kuaishou.com/new-reco", kuaishouJson, 0)
        )
        assertNull(LoginPageRecovery.jsonPayloadFallbackUrl("快手", "https://www.kuaishou.com/new-reco", kuaishouJson, 1))
        assertFalse(LoginPageRecovery.looksLikeRawErrorJsonPayload("<html><body>Login</body></html>"))
    }
    @Test fun domesticLoginNeverDismissesForAnonymousOrOldCookies() {
        for (platform in listOf("小红书", "抖音", "快手")) {
            val config = loginConfigFor(platform)
            assertFalse(canCaptureLoginCookie(config, "web_session=guest; did=anonymous; sessionid=old", false))
            assertTrue(canCaptureLoginCookie(config, "web_session=user-saved", true))
            assertFalse(canCaptureLoginCookie(config, "", true))
        }
    }
    @Test fun automaticCookieDetectionUsesExactNames() {
        val config = loginConfigFor("X (Twitter)")
        assertFalse(canCaptureLoginCookie(config, "tracking=value_auth_token=not-auth", false))
        assertTrue(canCaptureLoginCookie(config, "auth_token=fixture", false))
    }
    @Test fun startPagesAreDesktopLandingPages() {
        assertEquals("https://www.xiaohongshu.com/explore", loginConfigFor("小红书").startUrl)
        assertEquals("https://www.kuaishou.com/new-reco", loginConfigFor("快手").startUrl)
        assertEquals("https://www.douyin.com/", loginConfigFor("抖音").startUrl)
    }
    @Test fun nativeAppHandoffsAreNotNavigatedInsideLogin() {
        assertTrue(BrowserIdentity.permitsNavigation("https://passport.kuaishou.com/login"))
        assertFalse(BrowserIdentity.permitsNavigation("intent://login#Intent;scheme=douyin;end"))
        assertFalse(BrowserIdentity.permitsNavigation("snssdk1128://user/login"))
        assertFalse(BrowserIdentity.permitsNavigation("javascript:alert(1)"))
    }
}
