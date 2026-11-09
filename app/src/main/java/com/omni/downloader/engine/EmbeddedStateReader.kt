package com.omni.downloader.engine

import kotlinx.serialization.json.*

internal object EmbeddedStateReader {
    /** Extract one balanced object; replacing JS undefined must never alter quoted titles/URLs. */
    fun read(html: String, assignment: Regex): JsonObject? {
        val start = assignment.find(html)?.range?.last?.plus(1) ?: return null
        var cursor = start
        while (cursor < html.length && html[cursor].isWhitespace()) cursor++
        if (html.getOrNull(cursor) != '{') return null
        val output = StringBuilder()
        var depth = 0
        var quoted = false
        var escaped = false
        while (cursor < html.length && output.length < 4_000_000) {
            val ch = html[cursor]
            if (quoted) {
                output.append(ch)
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else {
                if (html.startsWith("undefined", cursor) &&
                    html.getOrNull(cursor - 1).let { it == ':' || it == ',' || it?.isWhitespace() == true } &&
                    html.getOrNull(cursor + 9).let { it == ',' || it == '}' || it == ']' || it?.isWhitespace() == true }) {
                    output.append("null"); cursor += 9; continue
                }
                output.append(ch)
                when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) return runCatching { Json.parseToJsonElement(output.toString()) as? JsonObject }.getOrNull() }
                }
            }
            cursor++
        }
        return null
    }

}
