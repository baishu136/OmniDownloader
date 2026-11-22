package com.omni.downloader.engine

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** All local accounts, public fallbacks and media share one X budget. */
internal object XNetwork {
    val policy=RequestGuardPolicy("x","X",apiGap=2000,mediaGap=250,imageGap=1000,globalGap=250)
    @Volatile private var guard:PixivRequestGuard?=null
    private class Admission(val url:String){val used=AtomicBoolean()}
    fun initialize(directory:File){if(guard==null)synchronized(this){if(guard==null)guard=PixivRequestGuard(directory,policy=policy)}}
    fun kind(host:String):String?=when {
        host=="x.com" || host.endsWith(".x.com") || host=="twitter.com" || host.endsWith(".twitter.com") ||
            host=="t.co" || host=="syndication.twimg.com" || host.endsWith(".syndication.twimg.com") -> "api"
        host=="pbs.twimg.com" -> "image"
        host=="twimg.com" || host.endsWith(".twimg.com") -> "media"
        else->null
    }
    fun configure(builder:OkHttpClient.Builder,requestGuard:PixivRequestGuard?=null):OkHttpClient.Builder=builder
        .addInterceptor(Interceptor{chain->
            val lane=kind(chain.request().url.host) ?: return@Interceptor chain.proceed(chain.request())
            val active=requestGuard ?: guard ?: error("[ENGINE_UNAVAILABLE] X 请求保护尚未初始化")
            active.acquire(lane){chain.call().isCanceled()}
            val response=chain.proceed(chain.request().newBuilder().tag(Admission::class.java,Admission(chain.request().url.toString())).build())
            inspectBody(response,lane,active)
        })
        .addNetworkInterceptor(Interceptor{chain->
            val lane=kind(chain.request().url.host) ?: return@Interceptor chain.proceed(chain.request())
            val active=requestGuard ?: guard ?: error("[ENGINE_UNAVAILABLE] X 请求保护尚未初始化")
            val admission=chain.request().tag(Admission::class.java)
            if(admission==null || admission.url!=chain.request().url.toString() || !admission.used.compareAndSet(false,true)) active.acquire(lane){chain.call().isCanceled()}
            val response=chain.proceed(chain.request())
            val login=response.code in 300..399 && response.header("Location").orEmpty().let{it.contains("/i/flow/login") || it.contains("/account/access")}
            if(response.code==429 || (lane=="api" && response.code in setOf(401,403)) || login ||
                (response.code==503 && response.header("Retry-After")!=null)) {
                active.block(if(login)401 else response.code,response.header("Retry-After"),resetAt=response.header("x-rate-limit-reset"))
                response.close();active.acquire(lane)
            }
            if(lane=="api" && response.isSuccessful && response.header("x-rate-limit-remaining")=="0")
                active.block(429,response.header("Retry-After"),resetAt=response.header("x-rate-limit-reset"))
            response
        })
    private fun inspectBody(response:Response,lane:String,active:PixivRequestGuard):Response {
        if(lane!="api") return response
        val type=response.header("Content-Type").orEmpty()
        if(type.contains("json",true) || type.contains("html",true)) {
            val body=response.peekBody(64*1024).string()
            val errors=if(type.contains("json",true)) runCatching{JSONObject(body).optJSONArray("errors")}.getOrNull() else null
            val codes=errors?.let{array->(0 until array.length()).map{array.optJSONObject(it)?.optInt("code")}.toSet()}.orEmpty()
            val challenge=type.contains("html",true) && listOf("cf-chl-","challenge-platform","verify you are human","complete a security check").any{it in body.lowercase()}
            val status=when {88 in codes->429;codes.any{it in setOf(32,64,89,326)}->401;else->0}
            if(challenge || status!=0) {
                active.block(status,response.header("Retry-After"),challenge, response.header("x-rate-limit-reset"))
                response.close();active.acquire(lane)
            }
        }
        return response
    }
}
