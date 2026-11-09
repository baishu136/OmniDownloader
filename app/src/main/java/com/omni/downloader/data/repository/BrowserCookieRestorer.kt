package com.omni.downloader.data.repository

import android.webkit.CookieManager
import java.util.concurrent.atomic.AtomicInteger

/** CookieManager.setCookie is asynchronous: navigation waits for every row's callback. */
internal object BrowserCookieRestorer {
    fun restore(rows: List<NetscapeCookies.CookieRow>, onRestored: () -> Unit) = restore(rows, CookieManager.getInstance(), onRestored)

    fun restore(rows: List<NetscapeCookies.CookieRow>, manager: CookieManager, onRestored: () -> Unit) {
        if (rows.isEmpty()) { onRestored(); return }
        manager.setAcceptCookie(true)
        val pending = AtomicInteger(rows.size)
        rows.forEach { row ->
            val cookie = "${row.name}=${row.value}; Path=${row.path}" +
                (if (row.includeSubdomains) "; Domain=.${row.domain}" else "") +
                (if (row.secure) "; Secure" else "") + (if (row.httpOnly) "; HttpOnly" else "")
            manager.setCookie("${if (row.secure) "https" else "http"}://${row.domain}${row.path}", cookie) {
                if (pending.decrementAndGet() == 0) { manager.flush(); onRestored() }
            }
        }
    }
}
