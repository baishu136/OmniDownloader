package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.repository.SessionRepository
import org.json.JSONObject

/** Private in-memory signed URL snapshots. Changing account or network invalidates them. */
internal object MetadataSnapshots {
    private data class Item(val time: Long, val json: String)
    private val items = LinkedHashMap<String, Item>()
    private fun address(url: String, site: String): String =
        if (site == LinkNormalizer.PLATFORM_X && LinkNormalizer.platformOf(url) == site) LinkNormalizer.normalize(url).contentUrl else url
    internal fun formatUrls(json: JSONObject): List<String> {
        val formats = json.optJSONArray("formats") ?: return emptyList()
        return (0 until formats.length()).mapNotNull { formats.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) }
    }
    private fun key(context: Context, url: String, site: String, proxy: String) =
        "${address(url, site)}|$site|$proxy|${SessionRepository.getInstance(context).requestGeneration(site)}|${SessionRepository.getInstance(context).info(site)?.importedAt}"
    @Synchronized fun put(context: Context, url: String, site: String, proxy: String, json: JSONObject) {
        fun store(address: String, value: JSONObject, includeBase: Boolean = true) {
            val base = key(context, address, site, proxy)
            val item = Item(android.os.SystemClock.elapsedRealtime(), value.toString())
            if (includeBase) items[base] = item
            if (!value.has("entries") && value.optString("id").isNotBlank()) items["$base|media=${value.optString("id")}"] = item
            while (items.size > 128) items.remove(items.keys.first())
        }
        store(url, json)
        val entries = json.optJSONArray("entries") ?: return
        for (i in 0 until entries.length()) {
            val child = entries.optJSONObject(i) ?: continue
            val childUrl = MediaSelection.childUrl(url, site, i, child.optString("webpage_url"), child.optString("original_url"),
                child.optString("url"), child.optString("id"), formatUrls(child)) ?: continue
            store(childUrl, child, includeBase = address(childUrl, site) != address(url, site))
        }
    }
    @Synchronized fun get(context: Context, url: String, site: String, proxy: String, mediaId: String): String? {
        val base = key(context, url, site, proxy)
        val item = (if (mediaId.isNotBlank()) items["$base|media=$mediaId"] else null) ?: items[base] ?: return null
        if (android.os.SystemClock.elapsedRealtime() - item.time > 600_000) return null
        val json = JSONObject(item.json)
        if (json.has("entries") || (mediaId.isNotBlank() && json.optString("id") != mediaId)) return null
        return item.json
    }
}
