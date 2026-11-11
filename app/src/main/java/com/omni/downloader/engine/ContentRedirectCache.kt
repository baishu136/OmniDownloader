package com.omni.downloader.engine

/** Short-lived public content identities only; never cache auth failures or media streams. */
internal class ContentRedirectCache(private val clock: () -> Long = System::nanoTime) {
    private val entries = linkedMapOf<Pair<String, String>, Pair<Long, String>>()
    @Synchronized fun get(url: String, proxy: String): String? {
        val key = url to proxy
        val entry = entries[key] ?: return null
        if (clock() - entry.first >= 30_000_000_000L) { entries.remove(key); return null }
        return entry.second
    }
    @Synchronized fun put(url: String, proxy: String, content: String) {
        if (entries.size >= 64) entries.remove(entries.keys.first())
        entries[url to proxy] = clock() to content
    }
}
