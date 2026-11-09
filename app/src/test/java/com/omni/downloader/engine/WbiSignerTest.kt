package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class WbiSignerTest {
    @Test fun matchesPinnedUpstreamVector() {
        val key = WbiSigner.key("https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png", "https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png")
        assertEquals("1022a87ffdaf532cb45ee953dce8c96d", key)
        assertEquals("bvid=BV1234567890&cid=42&foo=a+b~&wts=1700000000&w_rid=f7d142412f8c097d5da023f5dc9d530b", WbiSigner.query(mapOf("bvid" to "BV1234567890", "cid" to "42", "foo" to "a b~!'()*"), key, 1700000000))
    }
    @Test fun refusesMalformedKeyMaterial() {
        assertThrows(IllegalArgumentException::class.java) { WbiSigner.key("https://example.com/short.png", "https://example.com/short.png") }
    }
}
