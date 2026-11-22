package com.omni.downloader.engine

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One app-wide budget, including anonymous requests. Python uses the same POSIX
 * record lock and JSON state. No account, cookie or proxy rotation resets it. */
internal class PixivRequestGuard(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val policy: RequestGuardPolicy = RequestGuardPolicy(),
) {
    companion object {
        const val API_GAP = 2000L
        const val MEDIA_GAP = 1000L
        const val GLOBAL_GAP = 500L
        const val BASE_COOLDOWN = 15 * 60_000L
        private val localLock=java.util.concurrent.locks.ReentrantLock()
        fun hostKind(host: String): String? = when {
            host == "pixiv.net" || host.endsWith(".pixiv.net") -> "api"
            host == "pximg.net" || host.endsWith(".pximg.net") -> "media"
            else -> null
        }
        fun retryAfter(value: String?, at: Long): Long = value?.trim()?.let { text ->
            text.toLongOrNull()?.takeIf { it >= 0 }?.let { return minOf(it, Long.MAX_VALUE / 1000) * 1000 }
            runCatching { (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - at).coerceAtLeast(0) }.getOrDefault(0)
        } ?: 0
    }
    private fun pause(ms:Long) {
        try { sleep(ms) } catch (_:InterruptedException) {Thread.currentThread().interrupt();throw IOException("Canceled")}
    }
    private fun <T> locked(cancelled: () -> Boolean = { false }, action: (JSONObject) -> T): T {
        while(true) {
            val acquired=try {localLock.tryLock(50,java.util.concurrent.TimeUnit.MILLISECONDS)} catch (_:InterruptedException) {Thread.currentThread().interrupt();throw IOException("Canceled")}
            if(acquired) break
            if(cancelled()) throw IOException("Canceled")
        }
        try {
        directory.mkdirs()
        RandomAccessFile(File(directory, "${policy.id}.lock"), "rw").use { file ->
            while (true) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Canceled")
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { pause(50); continue }
                lock.use {
                    val path=File(directory,"${policy.id}.json")
                    val state=if (!path.exists()) JSONObject() else try {
                        check(path.length()<=4096); JSONObject(path.readText())
                    } catch (_: Exception) {
                        // An interrupted/corrupt state must not remove a possible cooldown.
                        JSONObject().put("policyVersion",2).put("until",0).put("reason","RATE_LIMITED")
                            .put("warningId",java.util.UUID.randomUUID().toString()).put("warningPending",true)
                    }
                    val before=state.toString()
                    if(state.optInt("policyVersion")<2) {
                        val oldUntil=state.optLong("until");val last=state.optLong("lastFailure")
                        val oldDefault=BASE_COOLDOWN*(1L shl (state.optInt("strikes",1).coerceIn(1,3)-1))
                        state.put("policyVersion",2).put("until",if(last>0 && oldUntil-last>oldDefault)oldUntil else 0)
                        if(oldUntil>now())state.put("warningId",java.util.UUID.randomUUID().toString()).put("warningPending",true)
                    }
                    try { return action(state) } finally {
                        if(!path.exists() || path.length()>4096 || runCatching { JSONObject(path.readText()).toString() }.getOrNull()!=before || state.toString()!=before) {
                            val temporary=File(directory,"${policy.id}.json.tmp")
                            temporary.writeText(state.toString())
                            Files.move(temporary.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                        }
                    }
                }
            }
        }
        } finally { localLock.unlock() }
    }
    fun acquire(kind: String, cancelled: () -> Boolean = {false}) {
        while (true) {
            val wait=locked(cancelled) { state ->
                val at=now();val remaining=state.optLong("until")-at
                if (state.optBoolean("warningPending"))throw java.net.ProtocolException("[${state.optString("reason","RATE_LIMITED")}] ${policy.label} 返回请求限制或身份验证要求，请查看应用内警告后手动重试；已解析结果保留")
                if (remaining>0) throw java.net.ProtocolException("[${state.optString("reason","RATE_LIMITED")}] ${policy.label} 平台要求等待 ${remaining/1000+if(remaining%1000>0)1 else 0} 秒；已解析结果保留")
                val due=maxOf(state.optLong("global"),state.optLong(kind))
                if(due>at) due-at else {
                    state.put("global",at+policy.globalGap).put(kind,at+when(kind){"api"->policy.apiGap;"image"->policy.imageGap;else->policy.mediaGap})
                    0L
                }
            }
            if(wait==0L) return
            pause(minOf(wait,100L)) // Recheck cancellation and newly received cooldowns.
        }
    }
    fun block(status: Int, retry: String? = null, challenge: Boolean = false, resetAt: String? = null) = locked { state ->
        val at=now()
        val strikes=if(at-state.optLong("lastFailure")>24*60*60_000L) 1 else (state.optInt("strikes")+1).coerceIn(1,3)
        val reset=resetAt?.toLongOrNull()?.takeIf{it>=0 && it<=Long.MAX_VALUE/1000}?.let{(it*1000-at).coerceAtLeast(0)} ?: 0
        val duration=maxOf(retryAfter(retry,at),reset)
        val until=if(duration>Long.MAX_VALUE-at) Long.MAX_VALUE else at+duration
        state.put("until",maxOf(state.optLong("until"),until)).put("lastFailure",at).put("strikes",strikes)
            .put("reason",when { challenge -> "CHALLENGE_REQUIRED";status in setOf(401,403) -> "AUTH_REQUIRED";else -> "RATE_LIMITED" })
            .put("warningId",java.util.UUID.randomUUID().toString()).put("warningPending",true)
        Unit
    }
    fun warning():RequestProtectionWarning?=locked{state->
        if(!state.optBoolean("warningPending"))null else RequestProtectionWarning(policy.id,policy.label,
            state.getString("warningId"),state.optString("reason","RATE_LIMITED"),state.optLong("until"))
    }
    fun acknowledge(id:String)=locked{state->
        if(state.optString("warningId")==id)state.put("warningPending",false)
        Unit
    }
}

internal data class RequestGuardPolicy(val id:String="pixiv",val label:String="Pixiv",val apiGap:Long=2000,
    val mediaGap:Long=1000,val imageGap:Long=1000,val globalGap:Long=500)

internal object PixivNetwork {
    @Volatile private var guard: PixivRequestGuard? = null
    private val cache=PixivMetadataCache()
    private class Admission(val url:String) {val used=java.util.concurrent.atomic.AtomicBoolean(false)}
    fun initialize(directory: File) { if(guard==null) synchronized(this) { if(guard==null) guard=PixivRequestGuard(directory) } }
    fun configure(builder: OkHttpClient.Builder, requestGuard: PixivRequestGuard? = null): OkHttpClient.Builder = builder
        .addInterceptor(cache)
        .addInterceptor(Interceptor { chain ->
            val kind=PixivRequestGuard.hostKind(chain.request().url.host)
            val active=requestGuard ?: guard
            if(kind==null || active==null) return@Interceptor chain.proceed(chain.request())
            // Run before DNS/TCP/TLS; a blocked retry must not open a connection.
            active.acquire(kind) {chain.call().isCanceled()}
            chain.proceed(chain.request().newBuilder().tag(Admission::class.java,Admission(chain.request().url.toString())).build())
        })
        .addNetworkInterceptor(Interceptor { chain ->
            val kind=PixivRequestGuard.hostKind(chain.request().url.host)
            val active=requestGuard ?: guard
            if(kind==null || active==null) return@Interceptor chain.proceed(chain.request())
            val admission=chain.request().tag(Admission::class.java)
            // Follow-ups also spend a slot; the first attempt already did so.
            if(admission==null || admission.url!=chain.request().url.toString() || !admission.used.compareAndSet(false,true))
                active.acquire(kind) { chain.call().isCanceled() }
            val response=chain.proceed(chain.request())
            inspect(response,active)
        })
    internal fun inspect(response: Response, guard: PixivRequestGuard): Response {
        val challenge=response.header("Content-Type").orEmpty().contains("text/html",true)
        val loginRedirect=response.code in 300..399 && response.header("Location").orEmpty().lowercase().let {
            it.contains("/login") || it.contains("/captcha") || it.contains("/challenge")
        }
        if(challenge || loginRedirect || response.code in setOf(401,403,429) || (response.code==503 && response.header("Retry-After")!=null)) {
            guard.block(if(loginRedirect)401 else response.code,response.header("Retry-After"),challenge)
            response.close()
            guard.acquire("api") // Fail with the persisted cooldown and stable error code.
        }
        return response
    }
}

/** Short-lived memory only. Never reuse one account/proxy's response for another. */
internal class PixivMetadataCache(private val now:()->Long=System::currentTimeMillis) : Interceptor {
    private data class Entry(val until:Long,val bytes:ByteArray,val response:Response)
    private val entries=object:LinkedHashMap<String,Entry>(16,0.75f,true){}
    override fun intercept(chain: Interceptor.Chain): Response {
        val request=chain.request()
        if(request.method!="GET" || PixivRequestGuard.hostKind(request.url.host)!="api" || !request.url.encodedPath.startsWith("/ajax/")) return chain.proceed(request)
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(
            "${request.url}\u0000${request.header("Cookie")}\u0000${NetworkProxy.configuredUrl}".toByteArray()).joinToString(""){"%02x".format(it)}
        synchronized(entries) { entries[key]?.takeIf{it.until>now()}?.let { cached ->
            return cached.response.newBuilder().request(request).body(cached.bytes.toResponseBody()).build()
        } }
        val response=chain.proceed(request)
        if(response.code==200 && response.header("Content-Type").orEmpty().contains("json",true) &&
            !response.header("Cache-Control").orEmpty().contains("no-store",true) && response.header("Vary")!="*") {
            val bytes=response.peekBody(512*1024+1).bytes()
            if(bytes.size<=512*1024 && runCatching { !JSONObject(String(bytes,Charsets.UTF_8)).optBoolean("error",true) }.getOrDefault(false)) {
                synchronized(entries) {
                    entries[key]=Entry(now()+3*60_000,bytes,response.newBuilder().removeHeader("Set-Cookie").body(null).build())
                    while(entries.size>128 || entries.values.sumOf { it.bytes.size }>8*1024*1024) entries.remove(entries.keys.first())
                }
            }
        }
        return response
    }
}
