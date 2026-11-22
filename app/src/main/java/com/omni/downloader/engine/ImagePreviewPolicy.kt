package com.omni.downloader.engine

import com.omni.downloader.data.model.ImageResource
import java.net.URI
import java.security.MessageDigest

/** Preview URLs never replace the resource used by downloads or the full viewer.
 * Signed/unknown CDN URLs are left intact. No metadata URL may change origin. */
internal object ImagePreviewPolicy {
    fun thumbnail(url:String)=thumbnail(ImageResource("",url))
    fun thumbnail(image:ImageResource):String=runCatching {
        val uri=URI(image.url);val path=uri.path.orEmpty()
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1)return@runCatching image.url
        when {
            uri.host=="pbs.twimg.com" && path.startsWith("/media/") -> {
                val query=uri.rawQuery.orEmpty().split('&').filter{it.isNotBlank() && it.substringBefore('=')!="name"}
                image.url.substringBefore('?').substringBefore('#')+"?"+(query+"name=small").joinToString("&")
            }
            uri.host?.endsWith(".pximg.net")==true && image.previewUrl.isNotBlank() -> {
                val small=URI(image.previewUrl)
                val original=Regex("([0-9]+)(?:-[0-9a-fA-F]{32})?_p([0-9]+)\\.[A-Za-z0-9]+$").matchEntire(path.substringAfterLast('/'))
                val thumb=Regex("([0-9]+)(?:-[0-9a-fA-F]{32})?_p([0-9]+)_(?:master|square)[0-9]+\\.[A-Za-z0-9]+$").matchEntire(small.path.orEmpty().substringAfterLast('/'))
                if(small.scheme=="https" && small.host==uri.host && small.userInfo==null && small.port==-1 &&
                    original!=null && thumb!=null && original.groupValues.drop(1)==thumb.groupValues.drop(1))image.previewUrl else image.url
            }
            uri.host=="i.pinimg.com" && path.startsWith("/originals/") && uri.rawQuery==null ->
                "https://i.pinimg.com/564x/"+uri.rawPath.removePrefix("/originals/")
            (uri.host=="hdslb.com" || uri.host?.endsWith(".hdslb.com")==true) &&
                uri.rawQuery==null && Regex(".*\\.(?:png|jpg|jpeg|webp)",RegexOption.IGNORE_CASE).matches(path) ->
                image.url.substringBefore('#')+"@640w_640h_0e.webp"
            else -> image.url
        }
    }.getOrDefault(image.url)

    fun cacheKey(url:String,referer:String,cookie:String?,proxy:String,accountScope:String=""):String = "preview-v2-"+
        MessageDigest.getInstance("SHA-256").digest(listOf(url,referer,cookie.orEmpty(),proxy,accountScope).joinToString("\u0000").toByteArray())
            .joinToString(""){"%02x".format(it)}
}
