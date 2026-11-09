package com.omni.downloader.engine

/** Bounded MP4 box readers; the sparse paths do not require a complete moov. */
object Mp4Dimensions {
    /** Movie timescale/duration, independent of the post's content duration.
     * Some platform playback files include an outro absent from page metadata. */
    fun readDurationSparse(readAt: (Long, Int) -> ByteArray?): Long? {
        fun number(bytes: ByteArray, at: Int, count: Int): Long? {
            if (at < 0 || at + count > bytes.size || (count == 8 && bytes[at] < 0)) return null
            return (0 until count).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
        }
        var visited = 0
        fun walk(start: Long, limit: Long?, depth: Int): Long? {
            var offset = start
            while (visited++ < 128 && (limit == null || offset <= limit - 8)) {
                val header = readAt(offset, 16) ?: return null
                var size = number(header, 0, 4) ?: return null
                val kind = String(header, 4, 4, Charsets.US_ASCII)
                val headerSize = if (size == 1L) 16 else 8
                if (size == 1L) size = number(header, 8, 8) ?: return null
                if (size == 0L) size = limit?.minus(offset) ?: return null
                if (size < headerSize || offset > Long.MAX_VALUE - size) return null
                val end = offset + size
                if (limit != null && end > limit) return null
                if (kind == "moov" && depth == 0) return walk(offset + headerSize, end, 1)
                if (kind == "mvhd" && depth == 1) {
                    val version = readAt(offset + headerSize, 1)?.firstOrNull()?.toInt() ?: return null
                    val fieldOffset = when (version) { 0 -> 12; 1 -> 20; else -> return null }
                    val durationBytes = if (version == 1) 8 else 4
                    if (size - headerSize < fieldOffset + 4 + durationBytes) return null
                    val fields = readAt(offset + headerSize + fieldOffset, 4 + durationBytes) ?: return null
                    val scale = number(fields, 0, 4) ?: return null
                    val duration = number(fields, 4, durationBytes) ?: return null
                    if (scale <= 0 || duration <= 0 || duration == 0xffffffffL || duration > Long.MAX_VALUE / 1000) return null
                    return (duration * 1000 / scale).takeIf { it > 0 }
                }
                offset = end
            }
            return null
        }
        return walk(0, null, 0)
    }

    /** Follow box offsets instead of loading an entire moov. Supports 64-bit
     * mdat boxes and large metadata while bounding traversal of untrusted input. */
    fun readSparse(readAt: (Long, Int) -> ByteArray?): Pair<Int, Int>? {
        fun number(bytes: ByteArray, at: Int, count: Int): Long? {
            if (at < 0 || at + count > bytes.size || (count == 8 && bytes[at] < 0)) return null
            return (0 until count).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
        }
        var visited = 0
        fun walk(start: Long, limit: Long?, depth: Int): Pair<Int, Int>? {
            if (depth > 3) return null
            var offset = start
            while (visited++ < 128 && (limit == null || offset <= limit - 8)) {
                val header = readAt(offset, 16) ?: return null
                var size = number(header, 0, 4) ?: return null
                val kind = String(header, 4, 4, Charsets.US_ASCII)
                val headerSize = if (size == 1L) 16 else 8
                if (size == 1L) size = number(header, 8, 8) ?: return null
                if (size == 0L) size = limit?.minus(offset) ?: return null
                if (size < headerSize || offset > Long.MAX_VALUE - size) return null
                val end = offset + size
                if (limit != null && end > limit) return null
                when (kind) {
                    "moov", "trak" -> walk(offset + headerSize, end, depth + 1)?.let { return it }
                    "tkhd" -> {
                        val version = readAt(offset + headerSize, 1)?.firstOrNull()?.toInt() ?: return null
                        val minimum = when (version) { 0 -> 84; 1 -> 96; else -> return null }
                        if (size - headerSize >= minimum) {
                            val dimensions = readAt(end - 8, 8) ?: return null
                            val width = ((number(dimensions, 0, 4) ?: return null) shr 16).toInt()
                            val height = ((number(dimensions, 4, 4) ?: return null) shr 16).toInt()
                            if (width in 1..32768 && height in 1..32768) return width to height
                        }
                    }
                }
                offset = end
            }
            return null
        }
        return walk(0, null, 0)
    }

    fun read(bytes: ByteArray): Pair<Int, Int>? {
        fun number(at: Int): Long = if (at < 0 || at + 4 > bytes.size) 0 else
            (0..3).fold(0L) { v, i -> (v shl 8) or (bytes[at + i].toLong() and 255) }
        fun kind(at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
        fun children(start: Int, end: Int): Pair<Int, Int>? {
            var offset = start
            while (offset + 8 <= end) {
                val length = number(offset)
                if (length < 8 || length > end - offset) break
                val limit = offset + length.toInt()
                when (kind(offset + 4)) {
                    "moov", "trak" -> children(offset + 8, limit)?.let { return it }
                    "tkhd" -> if (length >= 92) {
                        val w = (number(limit - 8) shr 16).toInt(); val h = (number(limit - 4) shr 16).toInt()
                        if (w in 1..32768 && h in 1..32768) return w to h
                    }
                }
                offset = limit
            }
            return null
        }
        // A suffix range can start in mdat. Accept only a complete, bounded moov.
        for (offset in 4 until bytes.size - 4) {
            if (bytes[offset] == 'm'.code.toByte() && kind(offset) == "moov") {
                val start = offset - 4; val size = number(start)
                if (size >= 8 && size <= bytes.size - start) children(start, start + size.toInt())?.let { return it }
            }
        }
        return null
    }
}
