package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class ContentRedirectCacheTest {
    @Test fun expiredOrDifferentProxyMustResolveAgain() {
        var now = 0L
        val cache = ContentRedirectCache { now }
        cache.put("short", "proxy-a", "target")
        assertEquals("target", cache.get("short", "proxy-a"))
        assertNull(cache.get("short", "proxy-b"))
        now = 30_000_000_000L
        assertNull(cache.get("short", "proxy-a"))
    }
    @Test fun boundedCacheEvictsOldContent() {
        val cache = ContentRedirectCache { 0L }
        repeat(65) { cache.put("$it", "", "target-$it") }
        assertNull(cache.get("0", ""))
        assertEquals("target-64", cache.get("64", ""))
    }
}
