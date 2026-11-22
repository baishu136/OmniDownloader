package com.omni.downloader.engine

import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

internal data class AndroidAppUpdate(val version: String, val releaseUrl: String)

/** Compare Android asset versions; the release tag may describe a different platform. */
internal object AndroidAppUpdates {
    private const val releases = "https://github.com/baishu136/OmniDownloader/releases"
    private const val api = "https://api.github.com/repos/baishu136/OmniDownloader/releases?per_page=30"
    private val versionPattern = Regex("""v?(\d+)\.(\d+)\.(\d+)""")
    private val apkPattern = Regex("""OmniDownloader-v?(\d+\.\d+\.\d+)(?:-(arm64-v8a|armeabi-v7a|x86_64|x86|universal))?(?:-release)?\.apk""")
    private val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .proxySelector(NetworkProxy)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun checkAutomatically(context: Context): AndroidAppUpdate? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: return@withLock null
            val preferences = context.getSharedPreferences("android_app_update", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val previous = preferences.getLong("checked_at_$installed", 0L)
            if (now >= previous && now - previous < TimeUnit.DAYS.toMillis(1)) return@withLock null
            val request = Request.Builder().url(api)
                .header("Accept", "application/vnd.github+json")
                .build()
            val update = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Update check HTTP ${response.code}")
                select(JSONArray(response.body?.string() ?: throw IOException("Empty release response")),
                    installed, Build.SUPPORTED_ABIS.toSet())
            }
            preferences.edit().putLong("checked_at_$installed", now).apply()
            update
        }
    }

    internal fun select(releasesJson: JSONArray, installedVersion: String, deviceAbis: Set<String>): AndroidAppUpdate? {
        val installed = Version.parse(installedVersion) ?: return null
        var newest = installed
        var update: AndroidAppUpdate? = null
        for (index in 0 until releasesJson.length()) {
            val release = releasesJson.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            if (!versionPattern.matches(tag)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (asset.optString("state") != "uploaded") continue
                val name = asset.optString("name")
                val match = apkPattern.matchEntire(name) ?: continue
                val apkVersion = Version.parse(match.groupValues[1]) ?: continue
                val declaredAbi = match.groupValues[2]
                if (declaredAbi in abis && declaredAbi !in deviceAbis) continue
                if (apkVersion <= newest) continue
                newest = apkVersion
                update = AndroidAppUpdate(match.groupValues[1], "$releases/tag/$tag")
            }
        }
        return update
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        companion object {
            fun parse(value: String): Version? {
                val match = versionPattern.matchEntire(value) ?: return null
                return Version(
                    match.groupValues[1].toIntOrNull() ?: return null,
                    match.groupValues[2].toIntOrNull() ?: return null,
                    match.groupValues[3].toIntOrNull() ?: return null,
                )
            }
        }
    }
}
