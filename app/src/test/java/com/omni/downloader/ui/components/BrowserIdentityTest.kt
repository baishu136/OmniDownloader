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
