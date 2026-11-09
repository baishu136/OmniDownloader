package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class ProxyConfigurationTest {
    @Test fun normalizesCommonHttpAndRemoteDnsSocksInputs() {
        assertNull(ProxyConfiguration.parse("  "))
        assertEquals("http://localhost:7890", ProxyConfiguration.parse(" localhost:7890 ")!!.url)
        assertEquals("http://proxy.test:8080", ProxyConfiguration.parse("HTTP://Proxy.Test/")!!.url)
        assertEquals("https://proxy.test:443", ProxyConfiguration.parse("HTTPS://Proxy.Test/")!!.url)
        assertEquals("socks5h://127.0.0.1:1080", ProxyConfiguration.parse("socks5://127.0.0.1")!!.url)
        assertEquals("socks5h://[::1]:1080", ProxyConfiguration.parse("socks5h://[::1]")!!.url)
        assertTrue((ProxyConfiguration.parse("http://proxy.test")!!.toProxy().address() as java.net.InetSocketAddress).isUnresolved)
    }
    @Test fun rejectsMalformedOrUnsupportedConfigurationWithoutEchoingSecrets() {
        for (url in listOf("ftp://proxy.test", "http://proxy.test:0", "http://proxy.test:65536", "http://proxy.test/path", "http://proxy.test?token=private", "bad host:7890", "http://user:secret@proxy.test:8080")) {
            val error = runCatching { ProxyConfiguration.parse(url) }.exceptionOrNull()
            assertTrue(url.substringBefore('@') + " should fail", error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("token=private"))
        }
    }
}
