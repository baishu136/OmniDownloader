package com.omni.downloader.engine

import com.omni.downloader.data.model.*
import java.net.URI
import java.util.UUID

object PixivArtistInput {
    fun userId(url: String): String? = runCatching {
        val uri=URI(url)
        if (uri.scheme !in setOf("https","http") || uri.host?.lowercase() !in setOf("pixiv.net","www.pixiv.net","touch.pixiv.net") || uri.userInfo != null || uri.port != -1) return null
        Regex("/(?:en/|ja/|zh/)?users/([0-9]{1,20})(?:/(?:artworks|illustrations|manga))?/?").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }.getOrNull()
    fun merge(previous: ImagePost, artwork: String, images: List<ImageResource>): ImagePost {
        require(previous.route=="pixiv_artist" && previous.platform=="Pixiv" && userId(previous.url)==previous.artistUserId && previous.contentId=="pixiv-user-${previous.artistUserId}") { "[MEDIA_ID_MISMATCH] 画师身份无效" }
        require(previous.artistArtworkIds.getOrNull(previous.artistOffset)==artwork) { "[MEDIA_ID_MISMATCH] 作品分页位置不一致" }
        require(images.isNotEmpty() && images.all { image -> image.id.substringBefore(':')==artwork && image.kind in setOf("IMAGE","UGOIRA") &&
            runCatching { URI(image.url).let { it.scheme=="https" && it.host?.let { host -> host=="pximg.net" || host.endsWith(".pximg.net") }==true && it.userInfo==null && it.port == -1 } }.getOrDefault(false) }) { "[MEDIA_ID_MISMATCH] 画师返回了无效作品资源" }
        val offset=previous.artistOffset+1
        return previous.copy(resources=(previous.resources+images).distinctBy { it.id },artistOffset=offset,artistComplete=offset==previous.artistArtworkIds.size)
    }
    fun posts(post: ImagePost, ids: Set<String>): List<ImagePost> = post.resources.filter { it.id in ids && it.kind=="IMAGE" }.groupBy { it.id.substringBefore(':') }.map { (id,images) ->
        ImagePost("Pixiv","https://www.pixiv.net/artworks/$id",id,images.first().sourceTitle.ifBlank { "Pixiv $id" },post.author,
            images.map { it.copy(headers=mapOf("Referer" to "https://www.pixiv.net/")) },"pixiv_artist_post")
    }
    fun tasks(post: ImagePost, ids: Set<String>, archive: Boolean, animationFormat: String="ugoira_gif", prefix: String=UUID.randomUUID().toString()): List<DownloadTask> {
        require(post.route=="pixiv_artist" && userId(post.url)==post.artistUserId && post.contentId=="pixiv-user-${post.artistUserId}") { "[MEDIA_ID_MISMATCH] 画师任务身份无效" }
        require(animationFormat in setOf("ugoira_gif","ugoira_mp4","ugoira_zip"))
        val available=post.resources.associateBy { it.id }
        require(ids.isNotEmpty() && ids.all { it in available }) { "[MEDIA_ID_MISMATCH] 所选作品已失效" }
        val chosen=post.resources.filter { it.id in ids }
        require(chosen.all { it.id.substringBefore(':') in post.artistArtworkIds &&
            (if(it.kind=="UGOIRA") it.id.matches(Regex("[0-9]+:ugoira")) else it.kind=="IMAGE" && it.id.matches(Regex("[0-9]+:p[0-9]+"))) }) { "[MEDIA_ID_MISMATCH] 所选资源不是画师原作品" }
        val count=if(archive) chosen.filter { it.kind=="IMAGE" }.map { it.id.substringBefore(':') }.distinct().size+chosen.count { it.kind=="UGOIRA" } else chosen.size
        require(count<=500) { "每次最多添加 500 个任务，请减少选择或按作品打包" }
        val group=prefix
        val images=posts(post,ids).flatMap { source ->
            val batches=if(archive) listOf(source.resources.map { it.id }) else source.resources.map { listOf(it.id) }
            batches.map { batch -> DownloadTask("$prefix-image-${batch.first().replace(':','-')}",source.url,source.title,source.author,
                source.resources.first { it.id==batch.first() }.url,if(archive) DownloadType.IMAGE_ZIP else DownloadType.IMAGE,
                schemaVersion=4,mediaId=source.contentId,imagePlatform="Pixiv",selectedImageIds=batch) }
        }
        val animations=post.resources.filter { it.id in ids && it.kind=="UGOIRA" }.map { image ->
            val id=image.id.substringBefore(':')
            DownloadTask("$prefix-ugoira-$id","https://www.pixiv.net/artworks/$id",image.sourceTitle.ifBlank { "Pixiv Ugoira $id" },post.author,image.url,
                if(animationFormat=="ugoira_gif") DownloadType.GIF else DownloadType.VIDEO_ONLY,schemaVersion=3,
                selectedVideoFormatId=animationFormat,selectedResolution=animationFormat,mediaId=id,selectedWidth=image.width,selectedHeight=image.height)
        }
        val tasks=images+animations
        require(tasks.size<=500) { "每次最多添加 500 个任务，请减少选择或按作品打包" }
        return tasks.mapIndexed { index,task -> task.copy(collectionId=if(tasks.size>1)group else null,collectionTitle=post.title,episodeIndex=index+1,episodeTotal=tasks.size) }
    }
}
