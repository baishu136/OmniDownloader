package com.omni.downloader.engine

import com.omni.downloader.data.model.ImageResource
import org.junit.Assert.*
import org.junit.Test

class ImagePreviewPolicyTest {
    @Test fun previewSourcesKeepOriginalsAndSignedOrUnknownUrlsUntouched() {
        val original="https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123_p2.png"
        val small="https://i.pximg.net/c/540x540_70/img-master/img/2026/01/01/00/00/00/123_p2_master1200.jpg"
        val item=ImageResource("123:p2",original,previewUrl=small)
        assertEquals(small,ImagePreviewPolicy.thumbnail(item));assertEquals(original,item.url)
        for(bad in listOf(small.replace("123_p2","999_p2"),small.replace("_p2","_p0"),small.replace("i.pximg.net","evil.test"),small.replace("https:","http:")))
            assertEquals(original,ImagePreviewPolicy.thumbnail(item.copy(previewUrl=bad)))
        val signed="https://i.pinimg.com/originals/a.jpg?token=keep"
        assertEquals(signed,ImagePreviewPolicy.thumbnail(ImageResource("1",signed)))
        assertEquals("https://i.pinimg.com/564x/a.jpg",ImagePreviewPolicy.thumbnail(ImageResource("1","https://i.pinimg.com/originals/a.jpg")))
        assertEquals("https://i0.hdslb.com/bfs/a.png@640w_640h_0e.webp",ImagePreviewPolicy.thumbnail(ImageResource("1","https://i0.hdslb.com/bfs/a.png")))
        val fake="https://i.pinimg.com.evil.test/originals/a.jpg"
        assertEquals(fake,ImagePreviewPolicy.thumbnail(ImageResource("1",fake)))
    }
    @Test fun cacheKeySurvivesReentryButSeparatesCredentialsRefererAndProxyWithoutLeakingThem() {
        fun key(cookie:String?="secret-cookie",referer:String="https://x.com/",proxy:String="")=ImagePreviewPolicy.cacheKey("https://pbs.twimg.com/a",referer,cookie,proxy)
        assertEquals(key(),key());assertNotEquals(key(),key(null));assertNotEquals(key(),key("other"))
        assertNotEquals(key(),key(proxy="http://private:password@localhost:8080"));assertNotEquals(key(),key(referer="https://other.test/"))
        assertFalse(key().contains("secret"));assertEquals(75,key().length)
    }
}
