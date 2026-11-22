package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.ImagePost
import com.omni.downloader.data.model.ImageResource
import com.omni.downloader.data.repository.SessionRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal object PixivArtistExtractor {
    private val client=PixivNetwork.configure(OkHttpClient.Builder().proxySelector(NetworkProxy).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS)).build()
    fun scope(context: Context,proxy: String)="Pixiv|${SessionRepository.getInstance(context).requestGeneration("Pixiv")}|$proxy|${NetworkProxy.configuredUrl}"
    internal fun index(body: JSONObject): List<String> {
        require(body.has("illusts") && body.has("manga")) { "[EXTRACTOR_CHANGED] 画师未返回完整作品索引" }
        val values=buildList {
            for(key in listOf("illusts","manga")) when(val value=body.opt(key)) {
                is JSONObject -> addAll(value.keys().asSequence().toList())
                is JSONArray -> require(value.length()==0) { "[EXTRACTOR_CHANGED] 画师索引结构已变化" }
                JSONObject.NULL -> Unit
                else -> error("[EXTRACTOR_CHANGED] 画师索引结构无效")
            }
        }
        require(values.all { it.matches(Regex("[0-9]{1,20}")) }) { "[EXTRACTOR_CHANGED] 作品 ID 无效" }
        return values.distinct().sortedWith(compareByDescending<String>{it.length}.thenByDescending { it })
    }
    internal fun resources(id: String,userId: String,work: JSONObject,body: Any): List<ImageResource> {
        require(work.optString("id")==id && work.optString("userId")==userId) { "[MEDIA_ID_MISMATCH] 作品不属于当前画师" }
        val title=work.optString("title").ifBlank { "Pixiv $id" }
        return when(work.optInt("illustType",-1)) {
            0,1 -> {
                val pages=body as? JSONArray ?: error("[EXTRACTOR_CHANGED] 作品未返回图片页")
                require(pages.length() in 1..200 && pages.length()==work.optInt("pageCount")) { "[MEDIA_UNCONFIRMED] 图片页数不完整或超过当前单作品上限" }
                (0 until pages.length()).map { page ->
                    val image=pages.getJSONObject(page);val url=image.getJSONObject("urls").getString("original")
                    check(!url.contains("/common/images/limit_")) { "[AUTH_REQUIRED] 当前会话未获准读取作品原图，请更新 Pixiv Cookie" }
                    require(isOriginalPageUrl(url,id,page)) { "[MEDIA_ID_MISMATCH] 原图与作品页不一致（作品 $id，第 ${page+1} 页）" }
                    ImageResource("$id:p$page",url,image.optInt("width"),image.optInt("height"),true,"SERVER_ORIGINAL",mapOf("Referer" to "https://www.pixiv.net/"),sourceTitle=title,
                        previewUrl=image.getJSONObject("urls").optString("small").ifBlank{image.getJSONObject("urls").optString("regular")})
                }
            }
            2 -> {
                val meta=body as? JSONObject ?: error("[EXTRACTOR_CHANGED] 动图元数据缺失")
                val frames=meta.optJSONArray("frames") ?: error("[MEDIA_UNCONFIRMED] 动图未返回帧时序")
                require(frames.length() in 1..5000 && (0 until frames.length()).all { frames.getJSONObject(it).optInt("delay") in 1..60000 }) { "[MEDIA_UNCONFIRMED] 动图帧时序无效" }
                val zip=meta.optString("originalSrc").ifBlank { meta.optString("src") }
                require(runCatching { java.net.URI(zip).let { it.scheme=="https" && it.host?.endsWith(".pximg.net")==true && it.userInfo==null && it.port == -1 } }.getOrDefault(false) && zip.substringBefore('?').substringAfterLast('/').startsWith("${id}_ugoira")) { "[MEDIA_ID_MISMATCH] 动图帧包与作品不一致" }
                val url=work.optString("url").ifBlank { error("[MEDIA_UNCONFIRMED] 动图缺少封面") }
                listOf(ImageResource("$id:ugoira",url,work.optInt("width"),work.optInt("height"),false,"PREVIEW",kind="UGOIRA",sourceTitle=title))
            }
            else -> error("[NO_MEDIA] 当前作品类型不支持下载")
        }
    }
    /** Replaced Pixiv originals may include a 32-hex revision between ID and page.
     * Match the complete filename and official original path, never an ID substring. */
    private fun isOriginalPageUrl(url: String,id: String,page: Int): Boolean = runCatching {
        val uri=java.net.URI(url);val host=uri.host?.lowercase().orEmpty();val path=uri.path.orEmpty()
        uri.scheme=="https" && (host=="pximg.net" || host.endsWith(".pximg.net")) && uri.userInfo==null && uri.port == -1 &&
            path.startsWith("/img-original/") &&
            Regex("${Regex.escape(id)}(?:-[0-9a-fA-F]{32})?_p$page\\.[A-Za-z0-9]+").matches(path.substringAfterLast('/'))
    }.getOrDefault(false)
    suspend fun fetch(context: Context,url: String,proxy: String,previous: ImagePost?=null,onPage: suspend(ImagePost)->Unit={}): ImagePost = withContext(Dispatchers.IO) {
        val user=requireNotNull(PixivArtistInput.userId(url));val captured=scope(context,proxy)
        suspend fun get(path: String,restricted: Boolean=false): Any {
            currentCoroutineContext().ensureActive()
            val target="https://www.pixiv.net$path"
            val request=Request.Builder().url(target).header("User-Agent","Mozilla/5.0").header("Referer","https://www.pixiv.net/users/$user")
            SessionRepository.getInstance(context).cookieHeader("Pixiv",target)?.let { request.header("Cookie",it) }
            val root=BiliHttp.read(client,request.build()) { response ->
                val code=when { response.code==429 -> "RATE_LIMITED";response.code in 300..399 || response.code in setOf(401,403) || (restricted && response.code==404) -> "AUTH_REQUIRED";response.code in setOf(404,410) -> "CONTENT_UNAVAILABLE";else -> "NETWORK_ERROR" }
                check(response.isSuccessful) { "[$code] Pixiv HTTP ${response.code}，请检查作品访问权限或会话" }
                val bytes=ByteArrayOutputStream();val buffer=ByteArray(32768)
                response.body!!.byteStream().use { input -> while(true) { val count=input.read(buffer);if(count<0)break;check(bytes.size()+count<=8*1024*1024){"[EXTRACTOR_CHANGED] 画师响应过大"};bytes.write(buffer,0,count) } }
                JSONObject(bytes.toString("UTF-8"))
            }
            check(!root.optBoolean("error")) { "[CONTENT_UNAVAILABLE] ${root.optString("message").take(180)}" }
            check(scope(context,proxy)==captured) { "[AUTH_REQUIRED] Pixiv 会话已改变，请重新解析画师主页" }
            return root.opt("body")?.takeUnless { it==JSONObject.NULL } ?: error("[EXTRACTOR_CHANGED] Pixiv 响应缺少正文")
        }
        var result=previous
        if(result==null) {
            val profile=get("/ajax/user/$user?full=1&lang=zh") as? JSONObject ?: error("[EXTRACTOR_CHANGED] 画师信息无效")
            require(profile.optString("userId")==user) { "[MEDIA_ID_MISMATCH] 返回了其他画师" }
            val ids=index(get("/ajax/user/$user/profile/all?lang=zh") as JSONObject)
            result=ImagePost("Pixiv","https://www.pixiv.net/users/$user","pixiv-user-$user",profile.optString("name").ifBlank { "Pixiv $user" }+" 的作品",profile.optString("name"),route="pixiv_artist",
                artistUserId=user,artistArtworkIds=ids,artistComplete=ids.isEmpty())
            onPage(result!!)
        }
        require(result!!.artistUserId==user && result!!.route=="pixiv_artist" && result!!.artistOffset in 0..result!!.artistArtworkIds.size) { "[MEDIA_ID_MISMATCH] 画师续页身份无效" }
        val remaining=result!!.artistArtworkIds.drop(result!!.artistOffset).take(100)
        for(batch in remaining.chunked(20)) {
            val query=batch.joinToString("&") { "ids%5B%5D=$it" }
            val response=get("/ajax/user/$user/profile/illusts?$query&work_category=illustManga&is_first_page=0&lang=zh") as JSONObject
            val works=response.optJSONObject("works") ?: error("[EXTRACTOR_CHANGED] 画师作品列表缺失")
            for(pair in batch.chunked(2)) {
                val loaded=coroutineScope { pair.map { id -> async {
                    try {
                        val work=works.optJSONObject(id) ?: error("[CONTENT_UNAVAILABLE] 作品 $id 未返回，解析尚未完成")
                        require(work.optString("id")==id && work.optString("userId")==user) { "[MEDIA_ID_MISMATCH] 返回了其他画师作品" }
                        val type=work.optInt("illustType",-1)
                        val body=get("/ajax/illust/$id/${if(type==2)"ugoira_meta" else "pages"}?lang=zh",work.optInt("xRestrict")>0)
                        Result.success(resources(id,user,work,body))
                    } catch(cancelled: CancellationException){throw cancelled} catch(error: Exception){Result.failure(error)}
                } }.awaitAll() }
                pair.zip(loaded).forEach { (id,value) -> currentCoroutineContext().ensureActive();result=PixivArtistInput.merge(result!!,id,value.getOrThrow());onPage(result!!) }
            }
        }
        result!!
    }
}
