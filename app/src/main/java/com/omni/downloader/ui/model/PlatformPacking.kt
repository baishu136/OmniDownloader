package com.omni.downloader.ui.model

/** Reserve the toggle beside the last name in at most two measured rows. */
internal fun collapsedPlatformCount(width: Int, chipWidths: List<Int>, gap: Int, toggleWidth: Int): Int {
    if (width <= 0) return 0
    fun fits(count: Int, toggle: Boolean): Boolean {
        var rows = 1; var used = 0
        val widths = chipWidths.take(count) + if (toggle) listOf(toggleWidth) else emptyList()
        for (natural in widths) {
            val item = natural.coerceIn(0, width)
            if (used > 0 && used + gap + item > width) { rows++; used = item }
            else used += (if (used > 0) gap else 0) + item
            if (rows > 2) return false
        }
        return true
    }
    if (fits(chipWidths.size, false)) return chipWidths.size
    return (chipWidths.size - 1 downTo 0).firstOrNull { fits(it, true) } ?: 0
}
