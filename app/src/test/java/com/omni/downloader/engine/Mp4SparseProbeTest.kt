package com.omni.downloader.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Mp4SparseProbeTest {
    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun box(name: String, payload: ByteArray) = int(payload.size + 8) + name.toByteArray() + payload
    private val track = box("tkhd", ByteArray(76) + int(4224 shl 16) + int(3024 shl 16))
    private val ftyp = box("ftyp", ByteArray(24))

    @Test fun seeksPast64BitMdatAndReadsOnlySmallTrackHeadersInsideLargeMoov() {
        val mdatSize = 15_894_214_251L
        val moovAt = ftyp.size + mdatSize
        val moovSize = 4_923_716
        val total = moovAt + moovSize
        val segments = mapOf(0L to (ftyp + int(1) + "mdat".toByteArray() + long(mdatSize)),
            moovAt to (int(moovSize) + "moov".toByteArray() + int(moovSize - 8) + "trak".toByteArray() + track))
        val requests = mutableListOf<LongRange>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val range = chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toLong)
            val start = range[0]; val end = minOf(range[1], total - 1)
            requests += start..end
            val bytes = ByteArray((end - start + 1).toInt())
            segments.forEach { (offset, data) ->
                val a = maxOf(start, offset); val b = minOf(end + 1, offset + data.size)
                if (a < b) data.copyInto(bytes, (a - start).toInt(), (a - offset).toInt(), (b - offset).toInt())
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("fixture")
                .header("Content-Range", "bytes $start-$end/$total").body(bytes.toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        val info = HttpMp4Probe.probe("https://cdn.test/original.mp4", http, emptyMap())!!
        assertEquals(4224, info.width); assertEquals(3024, info.height); assertEquals(total, info.fileBytes)
        assertEquals(2, requests.size)
        assertTrue(requests.sumOf { it.last - it.first + 1 } <= 131072)
        assertTrue(requests[1].first > Int.MAX_VALUE)
        assertNull(Mp4Dimensions.read(segments.getValue(moovAt))) // Former complete-moov logic cannot use this prefix.
    }

    @Test fun acceptsFastStartWithoutRequiringRangeSupport() {
        val data = ftyp + box("moov", box("trak", track))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(data.toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        assertEquals(4224, HttpMp4Probe.probe("https://cdn.test/fast.mp4", http, emptyMap())!!.width)
    }

    @Test fun readsMovieDurationInBothHeaderVersionsWithoutDownloadingMedia() {
        for (version in listOf(0, 1)) {
            val payload = if (version == 0) ByteArray(12) + int(1000) + int(8500)
                else byteArrayOf(1, 0, 0, 0) + ByteArray(16) + int(1000) + long(8500)
            val data = ftyp + box("moov", box("mvhd", payload) + box("trak", track))
            val reader = { at: Long, count: Int -> if (at >= 0 && at + count <= data.size) data.copyOfRange(at.toInt(), at.toInt() + count) else null }
            assertEquals(8500L, Mp4Dimensions.readDurationSparse(reader))
            assertEquals(4224 to 3024, Mp4Dimensions.readSparse(reader))
        }
        val invalid = ftyp + box("moov", box("mvhd", ByteArray(12) + int(0) + int(8500)))
        assertNull(Mp4Dimensions.readDurationSparse { at, count -> if (at >= 0 && at + count <= invalid.size) invalid.copyOfRange(at.toInt(), at.toInt() + count) else null })
    }

    @Test fun optionalDurationFailurePreservesConfirmedOriginalDimensions() {
        val prefix = ftyp + int(200000) + "moov".toByteArray() + int(140000) + "trak".toByteArray() + track
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().header("Range") != "bytes=0-65535") throw java.io.IOException("optional range unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("fixture")
                .header("Content-Range", "bytes 0-65535/200032")
                .body(prefix.copyOf(65536).toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        val info = HttpMp4Probe.probe("https://cdn.test/original.mp4", http, emptyMap())!!
        assertEquals(4224, info.width); assertEquals(3024, info.height); assertNull(info.durationMs)
    }

    @Test fun rejectsWrongRangesAndOverflowingOrOutOfParentBoxes() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("fixture")
                .header("Content-Range", "bytes 999-1000/1001").body(ByteArray(2).toResponseBody()).build()
        }.build()
        assertNull(HttpMp4Probe.probe("https://cdn.test/wrong.mp4", http, emptyMap()))
        val overflow = int(1) + "mdat".toByteArray() + long(-1)
        assertNull(Mp4Dimensions.readSparse { at, size -> if (at == 0L) overflow.copyOf(size) else null })
        val invalid = box("moov", int(1000) + "trak".toByteArray() + track)
        assertNull(Mp4Dimensions.readSparse { at, size -> if (at >= 0 && at + size <= invalid.size) invalid.copyOfRange(at.toInt(), at.toInt() + size) else null })
    }

    @Test fun boundedReaderStopsPathologicalFilesWithoutWalkingForever() {
        var reads = 0
        assertNull(Mp4Dimensions.readSparse { _, _ -> reads++; int(16) + "free".toByteArray() + ByteArray(8) })
        assertTrue(reads <= 128)
    }

    @Test fun transientOriginalProbeFailureDoesNotDiscardTheHigherRendition() {
        val data = ftyp + box("moov", box("trak", track))
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val failure = calls++ == 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (failure) 503 else 200)
                .message("fixture").body((if (failure) ByteArray(0) else data).toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        assertEquals(4224, HttpMp4Probe.probe("https://cdn.test/original.mp4", http, emptyMap())!!.width)
        assertEquals(2, calls)
    }

    @Test fun clampedRangesAreNotMistakenForCompleteAlignedBlocks() {
        val data = ftyp + box("moov", box("trak", box("free", ByteArray(3000)) + track))
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val start = chain.request().header("Range")!!.substringAfter('=').substringBefore('-').toInt()
            val end = minOf(start + 1023, data.lastIndex)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("fixture")
                .header("Content-Range", "bytes $start-$end/${data.size}")
                .body(data.copyOfRange(start, end + 1).toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        assertEquals(4224, HttpMp4Probe.probe("https://cdn.test/large-moov.mp4", http, emptyMap())!!.width)
        assertTrue(calls <= 4)
    }

    @Test fun forbiddenOriginalIsNotRetriedAsTransientOrLabeledAsKnownResolution() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("fixture")
                .body("denied".toResponseBody("text/html".toMediaType())).build()
        }.build()
        assertNull(HttpMp4Probe.probe("https://cdn.test/denied.mp4", http, emptyMap()))
        assertEquals(1, calls)
    }
}
