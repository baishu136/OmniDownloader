package com.omni.downloader.engine

import java.io.File
import java.io.RandomAccessFile

/** Checks the complete GIF block stream without loading frame pixels into memory. */
internal object GifInspector {
    data class Info(val width: Int, val height: Int, val frames: Int, val durationMs: Long)

    fun inspect(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(6).also(input::readFully).toString(Charsets.US_ASCII)
            require(signature == "GIF87a" || signature == "GIF89a")
            fun byte() = input.readUnsignedByte()
            fun word(): Int = byte() or (byte() shl 8)
            fun skip(count: Long) {
                require(count >= 0 && count <= input.length() - input.filePointer)
                input.seek(input.filePointer + count)
            }
            fun subBlocks(): Long {
                var total = 0L
                while (true) {
                    val size = byte()
                    if (size == 0) return total
                    skip(size.toLong()); total += size
                }
            }
            val width = word(); val height = word()
            require(width > 0 && height > 0)
            val packed = byte()
            skip(2)
            if (packed and 0x80 != 0) skip(3L * (1 shl ((packed and 7) + 1)))
            var frames = 0; var duration = 0L; var delay = 0L
            while (true) {
                when (byte()) {
                    0x21 -> {
                        if (byte() == 0xf9) {
                            require(byte() == 4)
                            byte(); delay = word() * 10L; byte()
                            require(byte() == 0)
                        } else subBlocks()
                    }
                    0x2c -> {
                        word(); word()
                        require(word() > 0 && word() > 0)
                        val imagePacked = byte()
                        if (imagePacked and 0x80 != 0) skip(3L * (1 shl ((imagePacked and 7) + 1)))
                        require(byte() in 2..8)
                        require(subBlocks() > 0)
                        frames++; duration += delay; delay = 0
                    }
                    0x3b -> {
                        require(frames > 0)
                        return@use Info(width, height, frames, duration)
                    }
                    else -> error("Invalid GIF block")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()
}
