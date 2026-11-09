package com.omni.downloader.engine

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** Bilibili player WBI signing; protocol and permutation match pinned yt-dlp. */
object WbiSigner {
    private val permutation = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
    fun key(imageUrl: String, subUrl: String): String {
        val lookup = listOf(imageUrl, subUrl).joinToString("") { URI(it).path.substringAfterLast('/').substringBefore('.') }
        require(lookup.length == 64 && lookup.all { it in "0123456789abcdefABCDEF" }) { "[EXTRACTOR_CHANGED] 无效WBI公钥" }
        return permutation.joinToString("") { lookup[it].toString() }
    }
    fun query(params: Map<String, String>, key: String, timestamp: Long): String {
        require(key.length == 32)
        val values = (params + ("wts" to timestamp.toString())).toSortedMap().mapValues { (_, v) -> v.filter { it !in "!'()*" } }
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("%7E", "~")
        val query = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val signature = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$signature"
    }
}
