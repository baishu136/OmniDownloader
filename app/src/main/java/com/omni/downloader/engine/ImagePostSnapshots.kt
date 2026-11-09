package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.repository.SessionRepository
import org.json.JSONObject
import java.io.File

/** Short-lived private manifests, invalidated by account import or cookie policy changes. */
object ImagePostSnapshots {
    private fun target(context: Context, url: String, platform: String): File {
        val generation = SessionRepository.getInstance(context).requestGeneration(platform)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("$url|$platform|$generation|${NetworkProxy.configuredUrl}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "image-posts/$key.json")
    }
    @Synchronized fun put(context: Context, post: JSONObject) {
        val directory = File(context.cacheDir, "image-posts").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 600_000 }?.forEach { it.delete() }
        if ((directory.listFiles()?.size ?: 0) >= 32) directory.listFiles()?.minByOrNull { it.lastModified() }?.delete()
        val file = target(context, post.getString("url"), post.getString("platform"))
        file.writeText(post.toString())
    }
    @Synchronized fun get(context: Context, url: String, platform: String): File? = target(context, url, platform)
        .takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() in 0..600_000 && it.length() <= 4_194_304 }
}
