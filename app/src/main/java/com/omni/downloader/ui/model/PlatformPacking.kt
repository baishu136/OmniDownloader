package com.omni.downloader.ui.model

/** Keep the catalog toggle within the first two rows, including on wide screens. */
internal fun collapsedPlatformCount(width: Int, chipWidths: List<Int>, gap: Int, toggleWidth: Int): Int {
    if (width <= 0) return 0
    fun fits(count: Int): Boolean {
        var rows = 1
        var used = 0
        for (natural in chipWidths.take(count) + toggleWidth) {
            val item = natural.coerceIn(0, width)
            if (used > 0 && used + gap + item > width) { rows++; used = item }
            else used += (if (used > 0) gap else 0) + item
            if (rows > 2) return false
        }
        return true
    }
    return (chipWidths.size downTo 0).firstOrNull(::fits) ?: 0
}
