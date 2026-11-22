package com.omni.downloader.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAppUpdatesTest {
    @Test fun usesApkVersionEvenWhenReleaseTagIsForAnotherPlatform() {
        val releases = JSONArray().put(release("v1.7.20", "OmniDownloader-1.7.19-arm64-v8a.apk", "OmniDownloader_LATEST.apk"))
        assertNull(AndroidAppUpdates.select(releases, "1.7.19", arm64))
        val update = AndroidAppUpdates.select(releases, "1.7.18", arm64)!!
        assertEquals("1.7.19", update.version)
        assertEquals("https://github.com/baishu136/OmniDownloader/releases/tag/v1.7.20", update.releaseUrl)
    }

    @Test fun selectsNewestCompatibleStableApkRegardlessOfReleaseOrder() {
        val releases = JSONArray()
            .put(release("v1.7.10", "OmniDownloader-1.7.10-arm64-v8a.apk"))
            .put(release("v1.7.12", "OmniDownloader-1.7.12-x86_64.apk"))
            .put(release("v1.7.13", "OmniDownloader-v1.7.13-release.apk").put("prerelease", true))
            .put(release("v1.7.14", "OmniDownloader-1.7.14-debug.apk"))
            .put(release("v1.7.15", "OmniDownloader-1.7.15-universal.apk").put("draft", true))
            .put(release("v1.7.9", "OmniDownloader-v1.7.9-release.apk"))
        assertEquals("1.7.10", AndroidAppUpdates.select(releases, "1.7.8", arm64)?.version)
        assertEquals("1.7.12", AndroidAppUpdates.select(releases, "1.7.8", setOf("x86_64"))?.version)
        assertNull(AndroidAppUpdates.select(releases, "1.7.50", arm64))
    }

    private fun release(tag: String, vararg assets: String) = JSONObject()
        .put("tag_name", tag)
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it).put("state", "uploaded") }))

    private val arm64 = setOf("arm64-v8a", "armeabi-v7a")
}
