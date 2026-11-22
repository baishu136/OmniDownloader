package com.omni.downloader.engine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.zip.ZipFile

class PixivArtistDownloadTest {
    private class StopBeforeVersionedOriginal : RuntimeException()
    @Test fun reportedArtistContinuesFromTheBlockedArtworkAndReachesTheEnd(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments();val url=args.getString("pixivResumeArtistUrl")
        assumeTrue("Opt-in reported artist",url!=null)
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val settings=SettingsRepository.getInstance(context);val oldProxy=settings.proxyUrl.value;val oldPath=settings.downloadPath.value;val oldImages=settings.imageDownloadPath.value
        val proxy=args.getString("pixivProxy").orEmpty();var saved:Uri?=null;var task:DownloadTask?=null
        try {
            settings.setProxyUrl(proxy);settings.setDownloadPath("");settings.setImageDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            var checkpoint:ImagePost?=null
            try {
                PixivArtistExtractor.fetch(context,url!!,proxy) { page ->
                    val position=page.artistArtworkIds.indexOf("144196490")
                    check(position>=0){"Acceptance artwork no longer present"}
                    if(page.artistOffset==position) { checkpoint=page;throw StopBeforeVersionedOriginal() }
                }
                fail("The regression checkpoint was not reached")
            } catch(_:StopBeforeVersionedOriginal) { }
            var current=requireNotNull(checkpoint);val initialOffset=current.artistOffset;val retained=current.resources
            val steps=org.json.JSONArray().put(initialOffset)
            while(!current.artistComplete) {
                val before=current.artistOffset
                current=PixivArtistExtractor.fetch(context,url!!,proxy,current)
                assertTrue(current.artistOffset>before)
                assertEquals(retained,current.resources.take(retained.size))
                steps.put(current.artistOffset)
                instrument.sendStatus(0,Bundle().apply {putString("stream","OMNI_PIXIV_CONTINUE_STEP=${current.artistOffset}/${current.artistArtworkIds.size}\n")})
            }
            assertEquals(current.artistArtworkIds.size,current.artistOffset)
            val affected=current.resources.single { it.id=="144196490:p0" }
            assertTrue(affected.url.contains("144196490-8b2d018ecd2666ccd6e4f7beefc952d8_p0.png"))
            val ids=setOf(affected.id)
            PixivArtistInput.posts(current,ids).forEach {ImagePostSnapshots.put(context,XProfileImages.manifest(it))}
            task=PixivArtistInput.tasks(current,ids,false,prefix="versioned-original-${UUID.randomUUID()}").single()
            val output=DownloadEngine.executeDownload(context,task!!,proxy){_,_,_,_->}.getOrThrow();saved=Uri.parse(output)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openInputStream(saved!!)!!.use {BitmapFactory.decodeStream(it,null,bounds)}
            assertEquals(affected.width,bounds.outWidth);assertEquals(affected.height,bounds.outHeight)
            val summary=JSONObject().put("success",true).put("userId",current.artistUserId).put("works",current.artistOffset).put("resources",current.resources.size)
                .put("initialOffset",initialOffset).put("steps",steps).put("retainedResourcesUnchanged",true).put("affectedOriginalDownloaded",true).put("affectedWidth",bounds.outWidth).put("affectedHeight",bounds.outHeight)
            instrument.sendStatus(0,Bundle().apply {putString("stream","OMNI_PIXIV_CONTINUE=$summary\n")})
        } finally {
            saved?.let {context.contentResolver.delete(it,null,null)}
            task?.let {File(context.filesDir,"media_tasks/${it.id}").deleteRecursively()}
            settings.setProxyUrl(oldProxy);settings.setDownloadPath(oldPath);settings.setImageDownloadPath(oldImages)
        }
    }
    @Test fun suppliedArtistDownloadsEveryConfirmedOriginalPage(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments();val url=args.getString("pixivArtistUrl")
        assumeTrue("Opt-in user supplied artist",url!=null)
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val settings=SettingsRepository.getInstance(context);val oldProxy=settings.proxyUrl.value;val oldPath=settings.downloadPath.value;val oldImages=settings.imageDownloadPath.value
        val proxy=args.getString("pixivProxy").orEmpty();val saved=mutableListOf<Uri>();var tasks=emptyList<DownloadTask>()
        try {
            settings.setProxyUrl(proxy);settings.setDownloadPath("");settings.setImageDownloadPath("")
            DownloadEngine.ensureInitialized(context).getOrThrow()
            val catalog=PixivArtistExtractor.fetch(context,url!!,proxy)
            assertTrue(catalog.artistComplete);assertEquals(catalog.artistArtworkIds.size,catalog.artistOffset)
            assertTrue(catalog.resources.all { it.kind=="IMAGE" && it.original })
            val ids=catalog.resources.map { it.id }.toSet();val originals=catalog.resources.associateBy { it.id }
            tasks=PixivArtistInput.tasks(catalog,ids,false,prefix="artist-live-${UUID.randomUUID()}")
            PixivArtistInput.posts(catalog,ids).forEach { ImagePostSnapshots.put(context,XProfileImages.manifest(it)) }
            var total=0L
            for(task in tasks) {
                val output=DownloadEngine.executeDownload(context,task,proxy){_,_,_,_->}.getOrThrow();val uri=Uri.parse(output);saved+=uri
                val expected=originals.getValue(task.selectedImageIds.single());val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,bounds) }
                assertEquals(expected.width,bounds.outWidth);assertEquals(expected.height,bounds.outHeight)
                context.contentResolver.openInputStream(uri)!!.use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;total+=n} }
            }
            val summary=JSONObject().put("success",true).put("userId",catalog.artistUserId).put("works",catalog.artistOffset).put("images",saved.size).put("allOriginalDimensionsVerified",true).put("bytes",total)
            instrument.sendStatus(0,Bundle().apply {putString("stream","OMNI_PIXIV_ARTIST=$summary\n")})
        } finally {
            saved.forEach { context.contentResolver.delete(it,null,null) }
            tasks.forEach { File(context.filesDir,"media_tasks/${it.id}").deleteRecursively() }
            settings.setProxyUrl(oldProxy);settings.setDownloadPath(oldPath);settings.setImageDownloadPath(oldImages)
        }
    }
    @Test fun artistAnimationTasksExportGifMp4AndTimedOriginalFrameZip(): Unit = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val zip=instrument.context.assets.open("ugoira-source.zip").use { it.readBytes() }
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val thread=Thread { while(!server.isClosed) try { server.accept().use { connection ->
            val reader=connection.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${zip.size}\r\nConnection: close\r\n\r\n".toByteArray());write(zip);flush()}
        } }catch(_:Exception){} }.apply {isDaemon=true;start()}
        val root=File(context.cacheDir,"artist-animation-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val catalog=ImagePost("Pixiv","https://www.pixiv.net/users/42","pixiv-user-42","artist","artist",
                listOf(ImageResource("101:ugoira","https://i.pximg.net/img-master/img/101_ugoira0.jpg",101,99,kind="UGOIRA")),"pixiv_artist",
                artistUserId="42",artistArtworkIds=listOf("101"),artistOffset=1)
            for(format in listOf("ugoira_gif","ugoira_mp4","ugoira_zip")) {
                val task=PixivArtistInput.tasks(catalog,setOf("101:ugoira"),false,format,prefix=format).single()
                val output=UgoiraProcessor.process(context,task,"http://127.0.0.1:${server.localPort}/frames.zip",emptyMap(),
                    listOf(UgoiraProcessor.Frame("0.png",80),UgoiraProcessor.Frame("1.png",230),UgoiraProcessor.Frame("2.png",390)),"101",
                    File(root,"$format/stage").apply {mkdirs()},File(root,"$format/out").apply {mkdirs()}){_,_,_,_->}.getOrThrow()
                val file=File(output)
                when(format) {
                    "ugoira_gif" -> {assertNull(MediaVerifier.verifyGif(file));val info=GifInspector.inspect(file)!!;instrument.sendStatus(0,Bundle().apply {putString("stream","OMNI_PIXIV_GIF=$info\n")});assertTrue("$info",info.frames>=3);assertTrue("$info",kotlin.math.abs(info.durationMs-700)<=100)}
                    "ugoira_mp4" -> {val info=MediaVerifier.probeWithFallback(context,file)!!;assertEquals(102,info.width);assertEquals(100,info.height);assertTrue(kotlin.math.abs(info.durationMs-700)<100)}
                    else -> ZipFile(file).use { archive -> val metadata=JSONObject(archive.getInputStream(archive.getEntry("frames.json")).bufferedReader().readText());assertEquals(700,metadata.getInt("totalDurationMs"));assertEquals(3,metadata.getJSONArray("frames").length()) }
                }
            }
        } finally {server.close();thread.join(2000);root.deleteRecursively()}
    }
}
