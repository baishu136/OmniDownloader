package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PixivGuardLiveTest {
    @Test fun protectedPythonParsingAndOriginalDownload(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments();val url=args.getString("pixivGuardArtworkUrl")
        assumeTrue("Opt-in single original only",url!=null)
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val settings=SettingsRepository.getInstance(context);val oldProxy=settings.proxyUrl.value
        val oldPath=settings.downloadPath.value;val oldImages=settings.imageDownloadPath.value
        val proxy=args.getString("pixivProxy").orEmpty();var saved:Uri?=null;var task:DownloadTask?=null
        try {
            settings.setProxyUrl(proxy);settings.setDownloadPath("");settings.setImageDownloadPath("")
            val manifest=SharedPython.run(context,"images",url!!,"Pixiv",proxy)
            val post=Json{ignoreUnknownKeys=true}.decodeFromString<ImagePost>(manifest.toString())
            assertEquals(LinkNormalizer.contentId(url,LinkNormalizer.PLATFORM_PIXIV),post.contentId)
            val image=post.resources.first();assertTrue(image.original)
            ImagePostSnapshots.put(context,manifest)
            task=DownloadTask(id="guard-live-${UUID.randomUUID()}",url=post.url,title=post.title,author=post.author,
                downloadType=DownloadType.IMAGE,imagePlatform="Pixiv",selectedImageIds=listOf(image.id))
            saved=Uri.parse(DownloadEngine.executeDownload(context,task!!,proxy){_,_,_,_->}.getOrThrow())
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            context.contentResolver.openInputStream(saved!!)!!.use{BitmapFactory.decodeStream(it,null,bounds)}
            assertEquals(image.width,bounds.outWidth);assertEquals(image.height,bounds.outHeight)
            val state=JSONObject(File(context.filesDir,"network_guard/pixiv.json").readText())
            assertTrue(state.getLong("api")>0);assertTrue(state.getLong("media")>0)
            val summary=JSONObject().put("success",true).put("id",post.contentId).put("width",bounds.outWidth).put("height",bounds.outHeight)
                .put("apiAndMediaBudgetUsed",true).put("originalDownloaded",true)
            instrument.sendStatus(0,Bundle().apply{putString("stream","OMNI_PIXIV_GUARD_LIVE=$summary\n")})
        } finally {
            saved?.let{context.contentResolver.delete(it,null,null)}
            task?.let{File(context.filesDir,"media_tasks/${it.id}").deleteRecursively()}
            settings.setProxyUrl(oldProxy);settings.setDownloadPath(oldPath);settings.setImageDownloadPath(oldImages)
        }
    }
}
