package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PixivArtistTest {
    @Test fun suppliedPreviewIsStoredSeparatelyFromDownloadOriginal() {
        val body=pages();val preview="https://i.pximg.net/c/540x540_70/img-master/img/102_p0_master1200.jpg"
        body.getJSONObject(0).getJSONObject("urls").put("small",preview)
        val image=PixivArtistExtractor.resources("102","42",work(),body).first()
        assertEquals(preview,image.previewUrl);assertTrue(image.url.contains("/img-original/"));assertTrue(image.original)
    }
    private fun catalog()=ImagePost("Pixiv","https://www.pixiv.net/users/42","pixiv-user-42","Artist","author",route="pixiv_artist",artistUserId="42",artistArtworkIds=listOf("102","101"),artistComplete=false)
    private fun work(id: String="102",type: Int=0)=JSONObject().put("id",id).put("userId","42").put("title","original").put("illustType",type).put("pageCount",2).put("width",64).put("height",64).put("url","https://i.pximg.net/img-master/img/102_ugoira0.jpg")
    private fun pages(id: String="102")=JSONArray().apply { repeat(2) { page ->put(JSONObject().put("width",3000).put("height",4000).put("urls",JSONObject().put("original","https://i.pximg.net/img-original/img/${id}_p$page.png"))) } }
    private fun animation()=JSONObject().put("originalSrc","https://i.pximg.net/img-zip-ugoira/img/101_ugoira1920x1080.zip").put("frames",JSONArray().put(JSONObject().put("file","0.jpg").put("delay",40)).put(JSONObject().put("file","1.jpg").put("delay",120)))
    @Test fun artistLinksAreHostSafeAndRouteDirectlyToImages() {
        for(url in listOf("https://www.pixiv.net/users/42","https://www.pixiv.net/en/users/42/artworks","https://pixiv.net/users/42/manga?x=1")) {
            assertEquals("42",PixivArtistInput.userId(url));assertEquals(MediaPage.IMAGES,MediaAnalysisRouter.knownPage(url))
        }
        for(url in listOf("https://pixiv.net.evil.test/users/42","https://www.pixiv.net@evil.test/users/42","https://www.pixiv.net:443/users/42","https://www.pixiv.net/users/42/bookmarks/artworks","https://www.pixiv.net/artworks/42")) assertNull(url,PixivArtistInput.userId(url))
    }
    @Test fun completeIndexIncludesIllustrationsMangaAndUgoiraButNotNovelsRecommendationsOrBookmarks() {
        val body=JSONObject("""{"illusts":{"101":null,"99999999999999999999":null},"manga":{"102":null,"101":null},"novels":{"999":null},"pickup":[{"id":"998"}]}""")
        assertEquals(listOf("99999999999999999999","102","101"),PixivArtistExtractor.index(body))
        assertTrue(PixivArtistExtractor.index(JSONObject("""{"illusts":[],"manga":{}}""")).isEmpty())
        assertTrue(runCatching { PixivArtistExtractor.index(JSONObject("{}")) }.isFailure)
    }
    @Test fun originalMangaPagesKeepStableIdsDimensionsAndOrder() {
        val resources=PixivArtistExtractor.resources("102","42",work(),pages())
        assertEquals(listOf("102:p0","102:p1"),resources.map { it.id });assertTrue(resources.all { it.original && it.width==3000 && it.height==4000 && it.url.endsWith(".png") })
        val result=PixivArtistInput.merge(catalog(),"102",resources)
        assertEquals(1,result.artistOffset);assertFalse(result.artistComplete)
        assertTrue(runCatching { PixivArtistInput.merge(result,"102",resources) }.isFailure)
    }
    @Test fun officialVersionedOriginalsKeepExactArtworkAndPageIdentity() {
        val id="144196490"
        val original="https://i.pximg.net/img-original/img/2026/05/01/01/26/32/$id-8b2d018ecd2666ccd6e4f7beefc952d8_p0.png"
        val body=JSONArray().put(JSONObject().put("width",2048).put("height",3072).put("urls",JSONObject().put("original",original)))
        val result=PixivArtistExtractor.resources(id,"59290023",work(id).put("userId","59290023").put("pageCount",1),body).single()
        assertEquals("$id:p0",result.id);assertEquals(original,result.url);assertTrue(result.original)
    }
    @Test fun versionedNamesStillRejectOtherWorksPagesThumbnailsAndUntrustedOrigins() {
        val hash="8b2d018ecd2666ccd6e4f7beefc952d8"
        val invalid=listOf(
            "https://i.pximg.net/img-original/img/999-$hash"+"_p0.png",
            "https://i.pximg.net/img-original/img/102-$hash"+"_p1.png",
            "https://i.pximg.net/img-original/img/102-${hash.dropLast(1)}_p0.png",
            "https://i.pximg.net/img-original/img/102-${hash}0_p0.png",
            "https://i.pximg.net/img-original/img/102-${hash.replace('8','g')}_p0.png",
            "https://i.pximg.net/img-original/img/102-${hash}_p0_master1200.jpg",
            "https://i.pximg.net/img-master/img/102-${hash}_p0.png",
            "https://i.pximg.net.evil.test/img-original/img/102-${hash}_p0.png",
            "https://evil.test@i.pximg.net/img-original/img/102-${hash}_p0.png")
        for(url in invalid) {
            val body=JSONArray().put(JSONObject().put("urls",JSONObject().put("original",url)))
            assertTrue(url,runCatching { PixivArtistExtractor.resources("102","42",work().put("pageCount",1),body) }.isFailure)
        }
    }
    @Test fun missingPagesWrongAuthorsAndPreviewPlaceholdersNeverBecomeOriginals() {
        assertTrue(runCatching { PixivArtistExtractor.resources("102","99",work(),pages()) }.isFailure)
        assertTrue(runCatching { PixivArtistExtractor.resources("102","42",work().put("pageCount",3),pages()) }.isFailure)
        assertTrue(runCatching { PixivArtistExtractor.resources("102","42",work(),pages("999")) }.isFailure)
        val limited=pages();limited.getJSONObject(0).getJSONObject("urls").put("original","https://s.pximg.net/common/images/limit_mypixiv.png")
        assertEquals(MediaErrorCode.AUTH_REQUIRED,MediaErrors.codeOf(runCatching { PixivArtistExtractor.resources("102","42",work(),limited) }.exceptionOrNull()!!))
    }
    @Test fun ugoiraIsOneAnimationNotItsCoverOrIndividualFrameImages() {
        val image=PixivArtistExtractor.resources("101","42",work("101",2),animation()).single()
        assertEquals("101:ugoira",image.id);assertEquals("UGOIRA",image.kind);assertFalse(image.original)
        assertTrue(runCatching { PixivArtistExtractor.resources("101","42",work("101",2),animation().put("originalSrc","https://evil.test/101_ugoira.zip")) }.isFailure)
        val invalid=animation();invalid.getJSONArray("frames").getJSONObject(0).put("delay",0)
        assertTrue(runCatching { PixivArtistExtractor.resources("101","42",work("101",2),invalid) }.isFailure)
    }
    @Test fun mixedTasksSaveOriginalPagesAndConvertAnimationsThroughExistingPipeline() {
        val stills=PixivArtistExtractor.resources("102","42",work(),pages())
        val animated=PixivArtistExtractor.resources("101","42",work("101",2),animation())
        val post=PixivArtistInput.merge(PixivArtistInput.merge(catalog(),"102",stills),"101",animated)
        assertTrue(post.artistComplete)
        val ids=post.resources.map { it.id }.toSet()
        val normal=PixivArtistInput.tasks(post,ids,false,prefix="fixture")
        assertEquals(3,normal.size);assertEquals(2,normal.count { it.downloadType==DownloadType.IMAGE });assertEquals(1,normal.count { it.downloadType==DownloadType.GIF })
        assertTrue(normal.all { it.url.startsWith("https://www.pixiv.net/artworks/") && !it.url.contains("users/") })
        assertEquals(listOf("102:p0","102:p1"),normal.filter { it.downloadType==DownloadType.IMAGE }.flatMap { it.selectedImageIds })
        for(format in listOf("ugoira_gif","ugoira_mp4","ugoira_zip")) {
            val planned=PixivArtistInput.tasks(post,ids,true,format,prefix="fixture")
            assertEquals(2,planned.size);assertEquals(1,planned.count { it.downloadType==DownloadType.IMAGE_ZIP })
            val task=planned.single { it.mediaId=="101" };assertEquals(format,task.selectedVideoFormatId);assertTrue(task.selectedImageIds.isEmpty())
        }
        assertTrue(PixivArtistInput.posts(post,ids).all { it.resources.all { r -> r.kind=="IMAGE" && r.headers["Referer"]=="https://www.pixiv.net/" } })
    }
}
