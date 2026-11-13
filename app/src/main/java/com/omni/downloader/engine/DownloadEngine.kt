package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import com.omni.downloader.engine.SafeLog as Log
import android.webkit.CookieManager
import com.omni.downloader.data.model.AudioFormat
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DownloadEngine {

    private const val TAG = "DownloadEngine"
    private val initMutex = Mutex()
    private val activeHttpCalls = ConcurrentHashMap<String, Call>()
    private val cancelledTaskIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var isEngineReady = false
    @Volatile
    private var initErrorMessage: String? = null

    /**
     * 引擎初始化自检与损坏修复
     */
    suspend fun ensureInitialized(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isEngineReady) return@withContext Result.success(Unit)

        initMutex.withLock {
            if (isEngineReady) return@withContext Result.success(Unit)

            try {
                val appContext = context.applicationContext
                Log.d(TAG, "正在启动引擎环境检查与初始化...")

                // 检查若存在损坏的旧版解压残留，主动自愈
                val targetDir = File(appContext.noBackupFilesDir, "youtubedl-android")
                val pythonDir = File(targetDir, "packages/python")
                if (pythonDir.exists() && pythonDir.listFiles().isNullOrEmpty()) {
                    Log.w(TAG, "发现损坏的空运行目录，执行重置清理...")
                    targetDir.deleteRecursively()
                }

                // 初始化核心引擎
                YoutubeDL.getInstance().init(appContext)
                BundledEngineInstaller.install(appContext)
                SharedPython.install(appContext)
                try {
                    FFmpeg.getInstance().init(appContext)
                } catch (fe: Exception) {
                    Log.w(TAG, "FFmpeg 已经初始化或忽略微异常: ${fe.message}")
                }
                FFmpegRuntime.ensure(appContext)

                isEngineReady = true
                initErrorMessage = null
                Log.d(TAG, "YoutubeDL 与 FFmpeg 初始化完全就绪！")
                Result.success(Unit)
            } catch (e: Exception) {
                val errorDetails = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "初始化引擎异常: $errorDetails", e)
                initErrorMessage = errorDetails
                Result.failure(Exception("核心引擎加载异常: $errorDetails"))
            }
        }
    }

    fun isReady(): Boolean = isEngineReady
    fun getInitError(): String? = initErrorMessage

    fun getDownloadDir(context: Context? = null): File {
        if (context != null) {
            try {
                val custom = com.omni.downloader.data.repository.SettingsRepository.getInstance(context).downloadPath.value
                if (custom.isNotBlank()) {
                    val dir = File(custom)
                    if (dir.exists() || dir.mkdirs()) {
                        return dir
                    }
                }
            } catch (ignored: Exception) {}
        }
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(publicDownload, "OmniDownloader")
    }

    /**
     * 解析视频信息与格式选项
     */
    suspend fun fetchVideoMetadata(
        context: Context,
        rawUrl: String,
        proxyUrl: String = "",
        requestId: String? = null,
        refreshSources: Boolean = false,
        onPhase: (String) -> Unit = {},
        resolutionDeadlineNanos: Long? = null,
        onPreview: suspend (VideoMetadata) -> Unit = {}
    ): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val douyinBudget = if (LinkNormalizer.platformOf(rawUrl) == LinkNormalizer.PLATFORM_DOUYIN)
            resolutionDeadlineNanos?.let { DouyinResolutionBudget(it) } ?: DouyinResolutionBudget() else null
        val processId = requestId ?: java.util.UUID.randomUUID().toString()
        var nativeFailure: String? = null
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(rawUrl)
        val site = UrlSniffer.identifySite(cleanUrl)

        Log.d(TAG, "开始解析: site=$site, cleanUrl=$cleanUrl")

        if (site == LinkNormalizer.PLATFORM_PINTEREST) {
            return@withContext runCatching {
                val json = SharedPython.run(context, "site-video", cleanUrl, site, proxyUrl, taskId = processId)
                MetadataSnapshots.put(context, cleanUrl, site, proxyUrl, json)
                parseMetadataFromJson(json, cleanUrl, site)
            }
        }

        // 1. 哔哩哔哩优先走原生直连解析 (免 Python 依赖，毫秒级，100% 成功率)
        if (site == "哔哩哔哩") {
            BilibiliDirectExtractor.syncSession(context)
            val directResult = BilibiliDirectExtractor.extract(context, cleanUrl)
            if (directResult.isSuccess) {
                return@withContext directResult
            }
            val err = directResult.exceptionOrNull() ?: Exception("B站视频信息解析失败")
            Log.e(TAG, "B站原生解析异常: ${err.message}", err)
            return@withContext Result.failure(err)
        }

        // 2. TikTok 优先走原生免登录无水印极速解析 (绕过 WAF，毫秒级)
        if (site == "TikTok" || TikTokDirectExtractor.isSupported(cleanUrl)) {
            val tiktokResult = TikTokDirectExtractor.extract(cleanUrl)
            if (tiktokResult.isSuccess) {
                return@withContext tiktokResult
            }
            Log.w(TAG, "TikTok 原生解析未命中，将降级尝试通用规则引擎: ${tiktokResult.exceptionOrNull()?.message}")
        }

        // 3. 国内平台（抖音、快手、小红书）优先走原生直接解析，若失败自动降级到 yt-dlp
        if (site == LinkNormalizer.PLATFORM_DOUYIN) {
            return@withContext DouyinAutoResolver.resolve(context, cleanUrl, proxyUrl, refreshSources, onPhase,
                budget = douyinBudget ?: DouyinResolutionBudget(), onPreview = onPreview) {
                DomesticDirectExtractor.extract(cleanUrl, site,
                    com.omni.downloader.data.repository.SessionRepository.getInstance(context).cookieHeader(site), cacheResult = false)
            }
        }
        if (DomesticDirectExtractor.isSupported(site)) {
            val sessionCookie = com.omni.downloader.data.repository.SessionRepository
                .getInstance(context).cookieHeader(site)
            val domesticResult = DomesticDirectExtractor.extract(cleanUrl, site, sessionCookie)
            if (domesticResult.isSuccess) {
                return@withContext domesticResult
            }
            Log.w(TAG, "$site 原生解析未命中，将降级尝试通用规则引擎: ${domesticResult.exceptionOrNull()?.message}")
            nativeFailure = domesticResult.exceptionOrNull()?.message
            if (site in DomesticSourceRecovery.platforms &&
                MediaErrors.classify(nativeFailure.orEmpty()) in setOf(MediaErrorCode.BROWSER_ASSIST_REQUIRED,
                    MediaErrorCode.MEDIA_UNCONFIRMED, MediaErrorCode.EXTRACTOR_CHANGED, MediaErrorCode.NETWORK_ERROR, MediaErrorCode.UNKNOWN)) {
                return@withContext runCatching {
                    val json = SharedPython.run(context, "site-video", cleanUrl, site, proxyUrl, taskId = processId)
                    MetadataSnapshots.put(context, cleanUrl, site, proxyUrl, json)
                    val metadata = parseMetadataFromJson(json, cleanUrl, site)
                    if (site == LinkNormalizer.PLATFORM_DOUYIN) DomesticDirectExtractor.confirmDouyinMetadata(metadata)
                    else DomesticDirectExtractor.rememberMetadata(metadata).let { metadata }
                }
            }
            if (nativeFailure.orEmpty().contains("[BROWSER_ASSIST_REQUIRED]")) return@withContext domesticResult
            if (nativeFailure.orEmpty().contains("[NO_MEDIA]") || nativeFailure.orEmpty().contains("[MEDIA_ID_MISMATCH]") || nativeFailure.orEmpty().contains("[AUTH_REQUIRED]") || nativeFailure.orEmpty().contains("[CHALLENGE_REQUIRED]")) return@withContext domesticResult
        }

        // 3.5 Pixiv Ugoira 专用解析（帧资源 + MP4/GIF 导出）
        if (site == LinkNormalizer.PLATFORM_PIXIV) {
            return@withContext PixivExtractor.extract(context, cleanUrl)
        }

        // 3.6 小黑盒分享页（受限支持）
        if (site == LinkNormalizer.PLATFORM_HEYBOX) {
            return@withContext HeyboxExtractor.extract(context, cleanUrl)
        }

        // 4. 通用与海外平台走健壮版 yt-dlp 引擎
        val initRes = ensureInitialized(context)
        if (initRes.isFailure) {
            return@withContext Result.failure(initRes.exceptionOrNull() ?: Exception("引擎未就绪"))
        }

        val requestContext = RequestContextFactory.create(context, site)
        try {
            val allowPlaylist = site == LinkNormalizer.PLATFORM_X ||
                LinkNormalizer.isCollectionUrl(cleanUrl, site)
            val request = YtDlpOptionsFactory.metadataRequest(
                url = cleanUrl,
                site = site,
                proxyUrl = proxyUrl,
                allowPlaylist = allowPlaylist,
                cookieFile = requestContext.cookieFile
            )
            Log.d(TAG, "yt-dlp 解析: site=$site, allowPlaylist=$allowPlaylist")

            // 自己执行并安全提取 JSON，防止因为控制台多余文本导致解析崩溃
            val jsonObject = try {
                val output = YoutubeDL.getInstance().execute(request, processId).out
                extractValidJson(output) ?: error("[MEDIA_UNCONFIRMED] 引擎没有返回媒体数据")
            } catch (first: Exception) {
                val message = first.message.orEmpty().lowercase()
                if (site == LinkNormalizer.PLATFORM_INSTAGRAM && MediaErrors.classify(message) == MediaErrorCode.NETWORK_ERROR) {
                    kotlinx.coroutines.delay(800)
                    extractValidJson(YoutubeDL.getInstance().execute(request, processId).out) ?: error("[MEDIA_UNCONFIRMED] 重试后仍未取得目标媒体")
                } else {
                if (site != LinkNormalizer.PLATFORM_X || !listOf("no video could be found", "429", "timed out", "502", "503",
                        "connection reset", "connection aborted", "remote end closed", "unexpected_eof", "server disconnected").any { it in message }) throw first
                // Independent public endpoint: never sends the account cookie jar.
                if (!proxyUrl.startsWith("https://", true)) {
                    try { XPublicExtractor.extract(context, cleanUrl, proxyUrl) } catch (native: Exception) {
                        if (MediaErrors.classify(native.message.orEmpty()) in setOf(MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.RATE_LIMITED,
                                MediaErrorCode.CONTENT_UNAVAILABLE, MediaErrorCode.MEDIA_ID_MISMATCH)) throw native
                        SharedPython.run(context, "x-video", cleanUrl, site, proxyUrl, taskId = processId)
                    }
                } else SharedPython.run(context, "x-video", cleanUrl, site, proxyUrl, taskId = processId)
                }
            }
            MetadataSnapshots.put(context, cleanUrl, site, proxyUrl, jsonObject)

            val metadata = parseMetadataFromJson(jsonObject, cleanUrl, site)
            Result.success(metadata)
        } catch (e: Exception) {
            val errorMsg = formatDetailedError(e)
            Log.e(TAG, "yt-dlp 解析报错: $errorMsg", e)
            Result.failure(Exception(nativeFailure?.let { "原生解析失败: $it；通用引擎失败: $errorMsg" } ?: errorMsg, e))
        } finally {
            requestContext.cleanup()
        }
    }

    /**
     * 下载高清原图封面并保存至系统相册
     */
    suspend fun downloadCoverFile(
        context: Context,
        coverUrl: String,
        title: String,
        proxyUrl: String = "",
        publicDir: File = getDownloadDir(context),
        onProgressUpdate: ((progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (coverUrl.isBlank()) {
            return@withContext Result.failure(Exception("未获取到有效的封面图片地址"))
        }

        onProgressUpdate?.invoke(10f, "", "正在获取高清封面...", TaskStatus.DOWNLOADING)

        try {
            val requestBuilder = Request.Builder()
                .url(coverUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

            // 根据目标地址配置防盗链 Referer
            if (coverUrl.contains("bilibili.com") || coverUrl.contains("hdslb.com")) {
                requestBuilder.header("Referer", "https://www.bilibili.com/")
            } else if (coverUrl.contains("douyin") || coverUrl.contains("iesdouyin")) {
                requestBuilder.header("Referer", "https://www.douyin.com/")
            } else if (coverUrl.contains("kuaishou") || coverUrl.contains("kwai")) {
                requestBuilder.header("Referer", "https://www.kuaishou.com/")
            } else if (coverUrl.contains("xiaohongshu") || coverUrl.contains("xhscdn")) {
                requestBuilder.header("Referer", "https://www.xiaohongshu.com/")
            }

            val clientBuilder = OkHttpClient.Builder().proxySelector(NetworkProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)

            if (proxyUrl.isNotBlank()) {
                try {
                    val cleanProxy = proxyUrl.removePrefix("http://").removePrefix("socks5://").trim()
                    val parts = cleanProxy.split(":")
                    if (parts.size == 2) {
                        val host = parts[0]
                        val port = parts[1].toInt()
                        val type = if (proxyUrl.startsWith("socks5://")) Proxy.Type.SOCKS else Proxy.Type.HTTP
                        clientBuilder.proxy(Proxy(type, InetSocketAddress(host, port)))
                    }
                } catch (pe: Exception) {
                    Log.w(TAG, "设置代理失败，降级为直连: ${pe.message}")
                }
            }

            val client = clientBuilder.build()
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("下载封面失败: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("封面内容为空"))
            val contentType = response.header("Content-Type", "") ?: ""

            val ext = when {
                coverUrl.contains(".png", ignoreCase = true) || contentType.contains("image/png") -> "png"
                coverUrl.contains(".webp", ignoreCase = true) || contentType.contains("image/webp") -> "webp"
                coverUrl.contains(".gif", ignoreCase = true) || contentType.contains("image/gif") -> "gif"
                else -> "jpg"
            }

            val cleanTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Cover_${System.currentTimeMillis()}" }
            val truncatedTitle = if (cleanTitle.length > 50) cleanTitle.take(50) else cleanTitle

            var destFile = File(publicDir, "${truncatedTitle}_cover.$ext")
            var counter = 1
            while (destFile.exists()) {
                destFile = File(publicDir, "${truncatedTitle}_cover_$counter.$ext")
                counter++
            }

            onProgressUpdate?.invoke(50f, "", "正在保存图片...", TaskStatus.DOWNLOADING)

            FileOutputStream(destFile).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }

            // 广播通知系统相册媒体库刷新新生成的文件
            try {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("image/$ext"),
                    null
                )
            } catch (ignored: Exception) {}

            onProgressUpdate?.invoke(100f, "", "", TaskStatus.COMPLETED)
            Result.success(destFile.absolutePath)
        } catch (e: Exception) {
            val msg = formatDetailedError(e)
            onProgressUpdate?.invoke(0f, "", "", TaskStatus.FAILED)
            Result.failure(Exception(msg, e))
        }
    }

    /**
     * 运行实际下载任务
     */
    suspend fun executeDownload(
        context: Context, task: DownloadTask, proxyUrl: String = "",
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (UrlSniffer.identifySite(task.url) == "哔哩哔哩") BilibiliDirectExtractor.syncSession(context)
        val workspace = File(context.filesDir, "media_tasks/${task.id}").apply { mkdirs() }
        val ready = File(workspace, "ready.txt")
        val signature = java.security.MessageDigest.getInstance("SHA-256").digest("${task.url}|${task.selectedVideoFormatId}|${task.downloadType}|${task.qualitySelection}|${task.selectedImageIds}".toByteArray()).joinToString("") { "%02x".format(it) }
        val record = if (ready.isFile) ready.readLines() else emptyList()
        val existing = record.getOrNull(1)?.let { File(workspace, it) }
        val staged = if (record.firstOrNull() == signature && existing?.isFile == true && existing.length() > 0 &&
            (task.downloadType != DownloadType.GIF || GifInspector.inspect(existing) != null)) {
            Result.success(existing.absolutePath)
        } else executeStagedDownload(context, task, proxyUrl) { p, speed, eta, state ->
            onProgressUpdate(p.coerceAtMost(97f), speed, eta,
                if (state == TaskStatus.COMPLETED) TaskStatus.PROCESSING else state)
        }
        staged.fold(onSuccess = { path ->
            try {
                val source = File(path)
                MediaVerifier.verifyTask(source, task, context)?.let {
                    if (task.downloadType == DownloadType.GIF) ready.delete()
                    error(it)
                }
                ready.writeText("$signature\n${source.relativeTo(workspace).path}")
                onProgressUpdate(98f, "", "正在导出成品…", TaskStatus.PROCESSING)
                val imageTarget = if (task.downloadType in setOf(DownloadType.IMAGE, DownloadType.IMAGE_ZIP))
                    com.omni.downloader.data.repository.SettingsRepository.getInstance(context).imageDownloadPath.value.takeIf { it.isNotBlank() } else null
                val result = StorageExporter.export(context, source, imageTarget)
                ready.delete()
                source.delete()
                Result.success(result)
            } catch (e: Exception) { Result.failure(e) }
        }, onFailure = { Result.failure(it) })
    }

    private suspend fun executeStagedDownload(
        context: Context,
        task: DownloadTask,
        proxyUrl: String = "",
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        cancelledTaskIds.remove(task.id)
        if (task.downloadType == DownloadType.GIF && task.url.startsWith("content://"))
            return@withContext LocalGifConverter.convert(context, task, onProgressUpdate)
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val site = UrlSniffer.identifySite(cleanUrl)

        val publicDir = File(context.filesDir, "media_tasks/${task.id}/output").apply { mkdirs() }
        val stagingDir = File(context.filesDir, "media_tasks/${task.id}/staging").apply { mkdirs() }
        var domesticFailure: String? = null

        if (task.downloadType in setOf(DownloadType.IMAGE, DownloadType.IMAGE_ZIP)) {
            return@withContext runCatching {
                onProgressUpdate(0f, "", "核验原作品并下载原图…", TaskStatus.DOWNLOADING)
                val extra = mutableListOf("--ids", JSONArray(task.selectedImageIds).toString(), "--output", publicDir.path)
                if (task.downloadType == DownloadType.IMAGE_ZIP) extra += "--archive"
                ImagePostSnapshots.get(context, task.url, task.imagePlatform)?.let { extra += listOf("--manifest", it.path) }
                val result = SharedPython.run(context, "download-images", task.url, task.imagePlatform, proxyUrl, task.id, extra)
                val output = File(result.getString("path")).canonicalFile
                check(output.parentFile == publicDir.canonicalFile && output.isFile && output.length() > 0) { "[POSTPROCESS_FAILED] 图片成品校验失败" }
                output.path
            }
        }

        // 0. 保存封面专属逻辑
        if (task.downloadType == DownloadType.COVER) {
            val coverUrl = task.thumbnailUrl.ifBlank { task.url }
            return@withContext downloadCoverFile(
                context = context,
                coverUrl = coverUrl,
                title = task.title,
                proxyUrl = proxyUrl,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
        }

        // 国内平台先复用任务中的完整媒体源，所有导出类型走同一下载/后处理链。
        if (DomesticDirectExtractor.isSupported(site)) {
            val result = DomesticDirectExtractor.download(
                context, task, stagingDir, publicDir, onProgressUpdate, proxyUrl
            )
            if (result.isSuccess) return@withContext result
            domesticFailure = result.exceptionOrNull()?.message
            if (task.directMediaUrl.isNotBlank() || task.selectedResolution.startsWith("http")) {
                // 已经拿到媒体源时保留下载/转码的真实错误，不能重新解析页面掩盖它。
                return@withContext result
            }
            Log.w(TAG, "$site 原生通道失败，尝试通用引擎: $domesticFailure")
        }

        // 0.5 中转站直链或媒体直接下载链接，优先走 OkHttp 高速流式下载 (彻底避免 yt-dlp 启动开销与超长文件名报错)
        val isDirectStream = (task.directMediaUrl.isNotBlank() || task.selectedResolution == "中转直链" ||
                task.selectedResolution.startsWith("http") ||
                UrlSniffer.isDirectMediaUrl(cleanUrl) ||
                cleanUrl.contains("snapcdn.app") ||
                cleanUrl.contains("twimg.com")) && !(site == LinkNormalizer.PLATFORM_X && proxyUrl.startsWith("https://", true))

        if (isDirectStream) {
            val targetStreamUrl = if (task.directMediaUrl.isNotBlank()) {
                task.directMediaUrl
            } else if (task.selectedResolution.startsWith("http")) {
                task.selectedResolution
            } else {
                task.url.ifBlank { cleanUrl }
            }
            Log.d(TAG, "命中直链下载规则: targetUrl=$targetStreamUrl, title=${task.title}")
            var directResult = downloadDirectMediaStream(
                context = context,
                task = task,
                targetUrl = targetStreamUrl,
                proxyUrl = proxyUrl,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate,
                requestHeaders = task.directMediaHeaders.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }
            )


            if (directResult.isFailure && site == "X (Twitter)" && task.mediaId.isNotBlank() &&
                directResult.exceptionOrNull()?.message?.let { it.contains("403") || it.contains("410") || it.contains("过期") } == true) {
                val refreshed = fetchVideoMetadata(context, task.url, proxyUrl).getOrNull()
                val media = refreshed?.let { root -> (root.multiMediaList.ifEmpty { listOf(root) }).firstOrNull { it.mediaId == task.mediaId } }
                val source = media?.availableVideoFormats?.firstOrNull { it.formatId == task.selectedVideoFormatId && !it.isVideoOnly }
                if (source != null && source.sourceUrl.isNotBlank()) {
                    directResult = downloadDirectMediaStream(context, task, source.sourceUrl, proxyUrl, stagingDir, publicDir, onProgressUpdate, source.sourceHeaders)
                }
            }

            // 如果首次下载失败且存在备用地址（如 selectedResolution 与 task.url 不一致），尝试备用地址
            if (directResult.isFailure && task.directMediaUrl.isBlank()) {
                val fallbackUrl = when {
                    task.selectedResolution.startsWith("http") && task.url.isNotBlank() && task.url != task.selectedResolution -> task.url
                    !task.selectedResolution.startsWith("http") && cleanUrl.isNotBlank() && cleanUrl != targetStreamUrl -> cleanUrl
                    else -> null
                }
                if (fallbackUrl != null) {
                    Log.d(TAG, "直链下载失败，正在尝试备用地址: $fallbackUrl")
                    directResult = downloadDirectMediaStream(
                        context = context,
                        task = task,
                        targetUrl = fallbackUrl,
                        proxyUrl = proxyUrl,
                        stagingDir = stagingDir,
                        publicDir = publicDir,
                        onProgressUpdate = onProgressUpdate
                    )
                }
            }

            if (directResult.isSuccess) {
                return@withContext directResult
            }

            // 中转直链任务（或显式直链URL）若下载失败，绝对严禁降级到 yt-dlp，防止报出 Unsupported URL 并掩盖真实错误
            if (task.directMediaUrl.isNotBlank() || task.selectedResolution == "中转直链" || task.selectedResolution.startsWith("http")) {
                val err = directResult.exceptionOrNull() ?: Exception("中转直链下载失败")
                Log.e(TAG, "中转直链任务彻底失败: ${err.message}")
                return@withContext Result.failure(err)
            }

            Log.w(TAG, "直链极速下载失败，尝试降级通用规则引擎: ${directResult.exceptionOrNull()?.message}")
        }

        // 1. 哔哩哔哩直连下载
        if (site == "哔哩哔哩") {
            val directResult = BilibiliDirectExtractor.download(
                context = context,
                task = if (task.downloadType == DownloadType.GIF) task.copy(downloadType = DownloadType.VIDEO_ONLY) else task,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
            if (directResult.isSuccess) {
                if (task.downloadType == DownloadType.GIF) {
                    val input = File(directResult.getOrThrow())
                    val output = File(publicDir, "${input.nameWithoutExtension}.gif")
                    onProgressUpdate(92f, "", "正在转换 GIF…", TaskStatus.PROCESSING)
                    return@withContext try {
                        FFmpegExecutor.convertToGif(context, input, output).getOrThrow()
                        Result.success(output.path)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { output.delete(); Result.failure(error) }
                    finally { input.delete() }
                }
                return@withContext directResult
            }
            val err = directResult.exceptionOrNull() ?: Exception("B站直连下载执行失败")
            Log.e(TAG, "B站直连下载错误: ${err.message}", err)
            return@withContext Result.failure(err)
        }

        // 2. TikTok 原生极速下载
        if ((site == "TikTok" || TikTokDirectExtractor.isSupported(cleanUrl)) &&
            (task.selectedResolution.startsWith("http") || task.selectedResolution.contains("原画") || task.downloadType == DownloadType.AUDIO_ONLY)) {
            val tiktokResult = TikTokDirectExtractor.download(
                context = context,
                task = task,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
            if (tiktokResult.isSuccess) {
                return@withContext tiktokResult
            }
            Log.w(TAG, "TikTok 原生直连下载失败，尝试通用规则引擎下载: ${tiktokResult.exceptionOrNull()?.message}")
        }

        // 3.5 Pixiv Ugoira 专用下载（不经过 yt-dlp）
        if (site == LinkNormalizer.PLATFORM_PIXIV) {
            return@withContext PixivExtractor.download(
                context = context,
                task = task,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
        }

        // 3.6 小黑盒分享页（受限支持）
        if (site == LinkNormalizer.PLATFORM_HEYBOX) {
            return@withContext HeyboxExtractor.download(
                context = context,
                task = task,
                proxyUrl = proxyUrl,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
        }

        // 4. 通用与海外媒体走 yt-dlp 引擎
        val initResult = ensureInitialized(context)
        if (initResult.isFailure) {
            return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("引擎未就绪"))
        }

        val requestContext = RequestContextFactory.create(context, site)
        var snapshotFile: File? = null
        try {
            if (site in setOf(LinkNormalizer.PLATFORM_X, LinkNormalizer.PLATFORM_FACEBOOK, LinkNormalizer.PLATFORM_INSTAGRAM, LinkNormalizer.PLATFORM_PINTEREST)) {
                var snapshot = MetadataSnapshots.get(context, cleanUrl, site, proxyUrl, task.mediaId)
                if (snapshot == null) {
                    fetchVideoMetadata(context, cleanUrl, proxyUrl).getOrThrow()
                    snapshot = MetadataSnapshots.get(context, cleanUrl, site, proxyUrl, task.mediaId)
                }
                check(snapshot != null) { "[MEDIA_ID_MISMATCH] 原媒体已不可定位，请重新解析并选择同一作品" }
                snapshotFile = File.createTempFile("media-info-", ".json", context.cacheDir).apply { writeText(snapshot) }
            }
            val plan = planForTask(task)
            val request = YtDlpOptionsFactory.downloadRequest(
                url = cleanUrl,
                site = site,
                proxyUrl = proxyUrl,
                plan = plan,
                outputPattern = "${stagingDir.absolutePath}/%(title).50s-%(id).30s.%(ext)s",
                cookieFile = requestContext.cookieFile, infoFile = snapshotFile
            )
            Log.d(TAG, "yt-dlp 下载计划: ${YtDlpOptionsFactory.describe(plan)}, site=$site")

            onProgressUpdate(0f, "", "准备下载...", TaskStatus.DOWNLOADING)

            var lastReportedPath = ""

            try {
                YoutubeDL.getInstance().execute(
                    request,
                    task.id
                ) { progress, etaInSeconds, line ->
                    val etaStr = if (etaInSeconds > 0) {
                        val m = etaInSeconds / 60
                        val s = etaInSeconds % 60
                        if (m > 0) "${m}分${s}秒" else "${s}秒"
                    } else ""

                    val speed = parseSpeedFromLine(line)
                    val currentStatus = when {
                        progress >= 99.5f -> TaskStatus.PROCESSING
                        else -> TaskStatus.DOWNLOADING
                    }

                    if (line.contains("[download] Destination:") || line.contains("[Merger] Merging formats into")) {
                        val candidate = line.substringAfter(":").trim().trim('"', '\'')
                        if (candidate.isNotEmpty()) {
                            lastReportedPath = candidate
                        }
                    }

                    onProgressUpdate(progress, speed, etaStr, currentStatus)
                }
            } catch (firstEx: Exception) {
                val rootMsg = getRootCause(firstEx).message ?: firstEx.message ?: ""
                if (rootMsg.contains("I/O operation on closed file", ignoreCase = true) ||
                    rootMsg.contains("closed file", ignoreCase = true) ||
                    rootMsg.contains("broken pipe", ignoreCase = true)
                ) {
                    Log.w(TAG, "首次下载检测到管道/句柄中断，正在自动无缝启用安全单流通道重试: $rootMsg")
                    onProgressUpdate(10f, "", "自动切换安全通道重试中...", TaskStatus.DOWNLOADING)

                    val safeStagingDir = File(context.filesDir, "media_tasks/${task.id}/safe_staging").apply { mkdirs() }
                    safeStagingDir.listFiles()?.filter { it.name.contains(task.id) }?.forEach { it.delete() }

                    // 只调整传输策略，保留同一选流计划、站点请求头与导出类型
                    val safeRequest = YtDlpOptionsFactory.downloadRequest(
                        url = cleanUrl,
                        site = site,
                        proxyUrl = proxyUrl,
                        plan = plan,
                        outputPattern = "${safeStagingDir.absolutePath}/%(id)s.%(ext)s",
                        safeTransport = true,
                        cookieFile = requestContext.cookieFile, infoFile = snapshotFile
                    )

                    YoutubeDL.getInstance().execute(safeRequest, task.id) { progress, etaInSeconds, line ->
                        val etaStr = if (etaInSeconds > 0) "${etaInSeconds}秒" else ""
                        val speed = parseSpeedFromLine(line)
                        onProgressUpdate(progress, speed, etaStr, TaskStatus.DOWNLOADING)
                    }

                    val safeFile = safeStagingDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.maxByOrNull { it.lastModified() }
                    if (safeFile != null) {
                        val cleanTitle = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "video_${System.currentTimeMillis()}" }
                        val safeFinal = File(publicDir, "${cleanTitle.take(60)}.mp4")
                        safeFile.copyTo(safeFinal, overwrite = true)
                        safeFile.delete()
                        try {
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(safeFinal.absolutePath), null, null)
                        } catch (ignored: Exception) {}
                        onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                        return@withContext Result.success(safeFinal.absolutePath)
                    }
                } else {
                    throw firstEx
                }
            }

            val stagedFile = findGeneratedFile(stagingDir, task.id, lastReportedPath)
                ?: findGeneratedFile(publicDir, task.id, lastReportedPath)

            if (task.downloadType == DownloadType.VIDEO_ONLY && stagedFile != null && MediaVerifier.probe(stagedFile)?.hasAudio == true) {
                val muted = File(stagingDir, "muted_${task.id}.mp4")
                FFmpegExecutor.stripAudio(context, stagedFile, muted).getOrThrow()
                check(MediaVerifier.probe(muted)?.hasAudio == false) { "[POSTPROCESS_FAILED] 仅视频成品仍包含音轨" }
                muted.copyTo(stagedFile, overwrite = true); muted.delete()
            }
            val finalFile = if (stagedFile != null && stagedFile.exists()) {
                if (task.downloadType == DownloadType.GIF) {
                    onProgressUpdate(95f, "", "正在转换为 GIF 动图 (FFmpeg)...", TaskStatus.PROCESSING)
                    val baseName = stagedFile.nameWithoutExtension
                    val cleanBase = if (baseName.length > 60) baseName.take(60) else baseName
                    val destGif = File(publicDir, "$cleanBase.gif")

                    val isNativeGif = GifInspector.inspect(stagedFile) != null
                    if (isNativeGif) {
                        Log.d(TAG, "源文件本身即为标准 GIF 动图，执行直通转存: ${stagedFile.name}")
                        stagedFile.copyTo(destGif, overwrite = true)
                        stagedFile.delete()
                        destGif
                    } else {
                        val gifRes = FFmpegExecutor.convertToGif(context, stagedFile, destGif)
                        if (gifRes.isSuccess && destGif.exists() && destGif.length() > 0) {
                            stagedFile.delete()
                            destGif
                        } else {
                            destGif.delete()
                            throw IOException("[POSTPROCESS_FAILED] GIF 转换失败，请重试；未生成 GIF 成品", gifRes.exceptionOrNull())
                        }
                    }
                } else {
                    val dest = File(publicDir, stagedFile.name)
                    try {
                        stagedFile.copyTo(dest, overwrite = true)
                        stagedFile.delete()
                        dest
                    } catch (e: Exception) {
                        Log.w(TAG, "转移到公开目录失败，保留暂存文件: ${e.message}")
                        stagedFile
                    }
                }
            } else {
                val errMsg = "通用引擎未能拉取到有效视频文件，请检查链接或网络代理"
                Log.e(TAG, errMsg)
                onProgressUpdate(0f, "", "", TaskStatus.FAILED)
                return@withContext Result.failure(Exception(errMsg))
            }

            // 广播通知系统相册媒体库刷新新生成的文件，显式指定 image/gif 等标准 MIME 类型
            try {
                val mimeType = when {
                    finalFile.name.endsWith(".gif", ignoreCase = true) -> "image/gif"
                    finalFile.name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                    finalFile.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    finalFile.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                    finalFile.name.endsWith(".jpg", ignoreCase = true) || finalFile.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    finalFile.name.endsWith(".png", ignoreCase = true) -> "image/png"
                    finalFile.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
                    else -> null
                }
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalFile.absolutePath),
                    if (mimeType != null) arrayOf(mimeType) else null,
                    null
                )
            } catch (ignored: Exception) {}

            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            Result.success(finalFile.absolutePath)
        } catch (e: Exception) {
            val errorMsg = formatDetailedError(e)
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            Result.failure(Exception(domesticFailure?.let { "原生通道失败: $it；通用引擎失败: $errorMsg" } ?: errorMsg, e))
        } finally {
            snapshotFile?.delete()
            requestContext.cleanup()
        }
    }

    fun cancelMetadata(requestId: String) {
        SharedPython.cancel(requestId)
        runCatching { YoutubeDL.getInstance().destroyProcessById(requestId) }
    }

    fun cancelTask(taskId: String) {
        SharedPython.cancel(taskId)
        cancelledTaskIds.add(taskId)
        try {
            activeHttpCalls.remove(taskId)?.cancel()
        } catch (ignored: Exception) {
        }
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (ignored: Exception) {
        }
    }

    /**
     * 根据目标链接自适应提取并匹配合法防盗链 Referer
     */
    private fun getRefererForUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: ""
            when {
                host.contains("twimg.com") || host.contains("twitter.com") || host.contains("x.com") -> "https://x.com/"
                host.contains("tiktok.com") || host.contains("byteoversea.com") || host.contains("ibytedtos.com") -> "https://www.tiktok.com/"
                host.contains("douyin.com") || host.contains("snssdk.com") || host.contains("iesdouyin.com") -> "https://www.douyin.com/"
                host.contains("kuaishou.com") || host.contains("kwai.com") || host.contains("yximgs.com") -> "https://www.kuaishou.com/"
                host.contains("bilibili.com") || host.contains("hdslb.com") || host.contains("bilivideo.com") -> "https://www.bilibili.com/"
                host.contains("snapcdn.app") -> "https://x2twitter.com/"
                host.contains("greenvideo.cc") -> "https://greenvideo.cc/"
                host.isNotBlank() -> "${uri.scheme ?: "https"}://$host/"
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 校验下载完成的临时文件是否为真实可播放的音视频文件（通过 Magic Bytes 特征头与最小尺寸判断）
     * 彻底拦截因防盗链、Cloudflare 拦截或接口报错导致将 HTML 网页或 JSON 错误保存为 MP4 的问题（防 MT 管理器 source error）
     */
    fun isValidMediaFile(file: File): Boolean {
        if (GifInspector.inspect(file) != null) return true
        if (!file.exists() || file.length() < 1024) return false
        val header = ByteArray(32)
        val readBytes = try {
            FileInputStream(file).use { it.read(header) }
        } catch (_: Exception) {
            return false
        }
        if (readBytes < 12) return false

        // 检查是否是纯文本（HTML、JSON、XML、M3U8）
        val headerStr = String(header, 0, minOf(readBytes, 32), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        if (headerStr.startsWith("<!doctype") || headerStr.startsWith("<html") ||
            headerStr.startsWith("<?xml") || headerStr.startsWith("{\"") ||
            headerStr.startsWith("[{\"") || headerStr.startsWith("#extm3u")
        ) {
            return false
        }

        // 检查常见媒体文件 Magic Bytes:
        // 1. MP4 / M4A / MOV: offset 4-7 为 "ftyp", "moov", "mdat", "wide", "skip"
        val isMp4 = (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) ||
                (header[4] == 0x6D.toByte() && header[5] == 0x6F.toByte() && header[6] == 0x6F.toByte() && header[7] == 0x76.toByte()) ||
                (header[4] == 0x6D.toByte() && header[5] == 0x64.toByte() && header[6] == 0x61.toByte() && header[7] == 0x74.toByte())

        // 2. WebM / MKV: 0x1A 0x45 0xDF 0xA3
        val isWebm = header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()

        // 3. FLV: "FLV"
        val isFlv = header[0] == 'F'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'V'.code.toByte()

        // 4. MPEG-TS: 0x47
        val isTs = header[0] == 0x47.toByte()

        // 5. MP3: "ID3" 或 0xFF 0xFB/F3/F2
        val isMp3 = (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
                (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)

        // 6. OGG: "OggS"
        val isOgg = header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()

        // 7. RIFF (AVI / WAV)
        val isRiff = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()

        return isMp4 || isWebm || isFlv || isTs || isMp3 || isOgg || isRiff
    }

    /**
     * M3U8 (HLS 分片切片流) 专用拉取与封装管线：
     * 利用 FFmpeg 或 yt-dlp 完整拉取所有分片并合成包含标准 ftyp box 的原片 MP4 文件
     */
    private suspend fun downloadM3u8Stream(
        context: Context,
        task: DownloadTask,
        m3u8Url: String,
        proxyUrl: String = "",
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit,
        requestHeaders: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        onProgressUpdate(10f, "", "检测到 HLS/M3U8 分片流，正在拉取分片并合成 MP4...", TaskStatus.DOWNLOADING)

        val tempFile = File(stagingDir, "m3u8_${task.id}.mp4")

        // 优先使用本地 FFmpeg 组件直转
        val ffmpeg = FFmpegExecutor.getFFmpegBinary(context)
        if (ffmpeg != null) {
            val referer = getRefererForUrl(m3u8Url)
            val cookieEnabled = com.omni.downloader.data.repository.SessionRepository.getInstance(context)
                .isCookieEnabled(CookieRequestPolicy.platformFor(task.url, m3u8Url))
            val cookie = if (!cookieEnabled) "" else try {
                CookieManager.getInstance().getCookie(m3u8Url) ?: ""
            } catch (_: Exception) { "" }

            val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            if (referer.isNotBlank()) headers["Referer"] = referer
            if (cookie.isNotBlank()) headers["Cookie"] = cookie
            if (m3u8Url.contains("greenvideo.cc")) headers["KdSystem"] = "GreenVideo"
            headers.putAll(CookieRequestPolicy.filter(requestHeaders, cookieEnabled))
            val headersText = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }

            val commands = mutableListOf(
                ffmpeg.absolutePath,
                "-y",
                "-headers", headersText
            )
            if (proxyUrl.isNotBlank()) {
                val cleanProxy = proxyUrl.trim()
                if (cleanProxy.startsWith("http")) {
                    commands.add("-http_proxy")
                    commands.add(cleanProxy)
                }
            }
            commands.addAll(listOf(
                "-i", m3u8Url,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-movflags", "+faststart",
                tempFile.absolutePath
            ))

            val ffmpegRes = FFmpegExecutor.executeCommand(context, commands)
            if (ffmpegRes.isSuccess && tempFile.exists() && tempFile.length() > 4096) {
                return@withContext finishDirectMedia(context, task, tempFile, stagingDir, publicDir, onProgressUpdate)
            }
            Log.w(TAG, "FFmpeg 直接拉取 M3U8 未完成，尝试 yt-dlp 原生通道: ${ffmpegRes.exceptionOrNull()?.message}")
        }

        // 备选通道：yt-dlp 原生 HLS 解析下载
        val initRes = ensureInitialized(context)
        if (initRes.isSuccess) {
            val request = YoutubeDLRequest(m3u8Url).apply {
                addOption("-o", "${stagingDir.absolutePath}/m3u8_${task.id}.%(ext)s")
                addOption("--no-playlist")
                addOption("--no-check-certificate")
                addOption("--no-warnings")
                addOption("--ignore-config")
                addOption("--no-cache-dir")
                addOption("--no-mtime")
                addOption("--retries", "10")
                if (proxyUrl.isNotBlank()) addOption("--proxy", proxyUrl.trim())
                requestHeaders.forEach { (name, value) -> addOption("--add-header", "$name:$value") }
            }
            try {
                YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
                    val etaStr = if (etaInSeconds > 0) "${etaInSeconds}秒" else ""
                    val speed = parseSpeedFromLine(line)
                    onProgressUpdate(progress, speed, etaStr, TaskStatus.DOWNLOADING)
                }
                val staged = findGeneratedFile(stagingDir, task.id, null)
                if (staged != null && staged.exists() && staged.length() > 4096) {
                    return@withContext finishDirectMedia(context, task, staged, stagingDir, publicDir, onProgressUpdate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp 拉取 M3U8 失败: ${e.message}", e)
            }
        }

        Result.failure(Exception("未能从 M3U8 切片流合成完整视频文件"))
    }

    /**
     * 直接通过 OkHttp 执行网络音视频直链的高速流式下载 (跳过 yt-dlp，支持断点/平滑速率/避免超长文件名报错)
     */
    internal suspend fun downloadDomesticMediaStream(
        context: Context, task: DownloadTask, targetStreamUrl: String, proxyUrl: String,
        stagingDir: File, publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit,
        requestHeaders: Map<String, String>
    ): Result<String> {
        val site = UrlSniffer.identifySite(task.url)
        // An individual CDN attempt is not the terminal task result. Keep the
        // task active while recovery is still allowed; the caller publishes a
        // final failure only after this whole operation returns.
        val attemptProgress: (Float, String, String, TaskStatus) -> Unit = { progress, speed, eta, status ->
            if (status != TaskStatus.FAILED) onProgressUpdate(progress, speed, eta, status)
        }
        var directResult = downloadDirectMediaStream(context, task, targetStreamUrl, proxyUrl,
            stagingDir, publicDir, attemptProgress, requestHeaders)
        if (directResult.isFailure && site in DomesticSourceRecovery.platforms && task.mediaId.isNotBlank() &&
            DomesticSourceRecovery.canRecover(directResult.exceptionOrNull()) && !cancelledTaskIds.contains(task.id)) {
            val rejected = mutableSetOf(targetStreamUrl)
            var attempts = 0
            suspend fun recover(metadata: VideoMetadata?) {
                for (source in DomesticSourceRecovery.candidates(task, metadata, rejected).take(2)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (attempts++ >= 3 || cancelledTaskIds.contains(task.id) || directResult.isSuccess) break
                    rejected += source.sourceUrl
                    // A new URL must never append to a previous rendition's
                    // partial bytes, even when both belong to the same post.
                    File(stagingDir, "direct_${task.id}.media").delete()
                    File(stagingDir, "direct_${task.id}.resume").delete()
                    onProgressUpdate(0f, "", "正在切换同画质视频源…", TaskStatus.DOWNLOADING)
                    directResult = downloadDirectMediaStream(context, task, source.sourceUrl, proxyUrl,
                        stagingDir, publicDir, attemptProgress, DomesticMediaSource.headers(site) + source.sourceHeaders)
                    if (!DomesticSourceRecovery.canRecover(directResult.exceptionOrNull())) break
                }
            }
            recover(DomesticDirectExtractor.cachedMetadata(task.url))
            if (directResult.isFailure && attempts < 3 && DomesticSourceRecovery.canRecover(directResult.exceptionOrNull()) &&
                !cancelledTaskIds.contains(task.id)) {
                onProgressUpdate(0f, "", "正在刷新目标作品的视频地址…", TaskStatus.DOWNLOADING)
                val refreshed = fetchVideoMetadata(context, task.url, proxyUrl, refreshSources = true)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                recover(refreshed.getOrNull())
            }
        }

        return directResult
    }

    internal suspend fun downloadDirectMediaStream(
        context: Context,
        task: DownloadTask,
        targetUrl: String,
        proxyUrl: String = "",
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit,
        requestHeaders: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        // 前置判定：如果是 M3U8 流，直接重定向至 M3U8 专业下载管线，绝不以原始文本落盘！
        if (UrlSniffer.isM3u8Url(targetUrl)) {
            Log.d(TAG, "目标直链已判定为 M3U8 切片流，切换至 M3U8 合成管线: $targetUrl")
            return@withContext downloadM3u8Stream(
                context = context,
                task = task,
                m3u8Url = targetUrl,
                proxyUrl = proxyUrl,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate,
                requestHeaders = requestHeaders
            )
        }

        // 先保存源媒体，实际输出扩展名由后处理决定。
        val tempFile = File(stagingDir, "direct_${task.id}.media")
        val resumeFile = File(stagingDir, "direct_${task.id}.resume")
        val sourceKey = java.security.MessageDigest.getInstance("SHA-256").digest(targetUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val clientBuilder = OkHttpClient.Builder().proxySelector(NetworkProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

        if (proxyUrl.isNotBlank()) {
            try {
                val cleanProxy = proxyUrl.removePrefix("http://").removePrefix("socks5://").trim()
                val parts = cleanProxy.split(":")
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toInt()
                    val type = if (proxyUrl.startsWith("socks5://")) Proxy.Type.SOCKS else Proxy.Type.HTTP
                    clientBuilder.proxy(Proxy(type, InetSocketAddress(host, port)))
                }
            } catch (pe: Exception) {
                Log.w(TAG, "设置代理失败，降级为直连: ${pe.message}")
            }
        }

        val client = clientBuilder.build()
        var retryCount = 0
        val maxRetries = 3
        var lastException: Exception? = null

        while (retryCount <= maxRetries) {
            var openResponse: okhttp3.Response? = null
            try {
                val resume = if (resumeFile.isFile) resumeFile.readLines() else emptyList()
                if (tempFile.exists() && (resume.getOrNull(0) != sourceKey || resume.getOrNull(1).isNullOrBlank())) {
                    tempFile.delete(); resumeFile.delete()
                }
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val reqBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")

                val referer = getRefererForUrl(targetUrl)
                if (referer.isNotBlank()) {
                    reqBuilder.header("Referer", referer)
                }

                // 核心凭证注入：同步 WebView 累积的 Session Cookie，彻底防止防盗链 403 导致下载假文件
                val cookieEnabled = com.omni.downloader.data.repository.SessionRepository.getInstance(context)
                    .isCookieEnabled(CookieRequestPolicy.platformFor(task.url, targetUrl))
                val cookie = if (!cookieEnabled) null else try {
                    CookieManager.getInstance().getCookie(targetUrl) ?: (
                        if (targetUrl.contains("greenvideo")) CookieManager.getInstance().getCookie("https://greenvideo.cc/") else null
                    )
                } catch (_: Exception) { null }
                if (!cookie.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookie)
                }

                if (targetUrl.contains("greenvideo.cc")) {
                    reqBuilder.header("KdSystem", "GreenVideo")
                }
                CookieRequestPolicy.filter(requestHeaders, cookieEnabled).forEach { (name, value) -> reqBuilder.header(name, value) }

                // 支持 HTTP Range 断点续传
                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                    reqBuilder.header("If-Range", resume[1])
                    Log.d(TAG, "启用断点续传: 从字节 $existingBytes 继续拉取")
                }

                val call = client.newCall(reqBuilder.build())
                activeHttpCalls[task.id] = call

                val response = call.execute().also { openResponse = it }
                val responseCode = response.code

                if (responseCode == 416) {
                    try { HttpRangePolicy.evaluate(416, response.header("Content-Range"), existingBytes, -1) }
                    catch (error: IOException) { tempFile.delete(); resumeFile.delete(); throw error }
                    lastException = null
                    break
                }

                if (!response.isSuccessful && responseCode != 206) {
                    val customMsg = when (responseCode) {
                        405 -> "该地址为接口路由 (HTTP 405 Method Not Allowed)，并非可直接下载的媒体文件，请尝试「窗口排查」"
                        403 -> "服务器拒绝访问 (HTTP 403 Forbidden)，可能受到防盗链限制或链接已失效"
                        404 -> "资源不存在或已失效 (HTTP 404 Not Found)"
                        else -> "HTTP 错误: $responseCode"
                    }
                    throw MediaHttpException(responseCode, customMsg)
                }

                val contentType = response.header("Content-Type", "")?.lowercase(Locale.ROOT) ?: ""

                // 识别 M3U8 响应
                if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
                    response.close()
                    Log.d(TAG, "响应 Content-Type 显示为 M3U8 播放列表，切换至 M3U8 合成管线: $contentType")
                    return@withContext downloadM3u8Stream(
                        context = context,
                        task = task,
                        m3u8Url = targetUrl,
                        proxyUrl = proxyUrl,
                        stagingDir = stagingDir,
                        publicDir = publicDir,
                        onProgressUpdate = onProgressUpdate
                    )
                }

                // 严禁将网页、JSON 错误信息写入视频文件（杜绝 MT 管理器 source error）
                if (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("application/xml")) {
                    val errorSnippet = try {
                        response.body?.string()?.take(500) ?: ""
                    } catch (_: Exception) { "" }
                    response.close()
                    throw IOException("直链返回内容为网页/错误响应 ($contentType)，非媒体流: $errorSnippet")
                }

                val body = response.body ?: throw IOException("直链响应体为空")

                val contentLength = body.contentLength()
                val range = try { HttpRangePolicy.evaluate(responseCode, response.header("Content-Range"), existingBytes, contentLength) }
                    catch (error: IOException) { tempFile.delete(); resumeFile.delete(); throw error }
                val appendMode = range.append
                val totalBytes = range.total
                val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified").orEmpty()
                resumeFile.writeText("$sourceKey\n$validator")

                var downloadedBytes = if (appendMode) existingBytes else 0L
                var lastReportTime = SystemClock.elapsedRealtime()
                var bytesSinceLastReport = 0L

                onProgressUpdate(
                    if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes * 100f).coerceIn(0f, 99f) else 5f,
                    "",
                    if (appendMode) "断点续传恢复中..." else "正在极速下载直链...",
                    TaskStatus.DOWNLOADING
                )

                body.byteStream().use { input ->
                    FileOutputStream(tempFile, appendMode).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            bytesSinceLastReport += read

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReportTime >= 350) {
                                val timeDiffSec = (now - lastReportTime) / 1000f
                                val speedBytesPerSec = if (timeDiffSec > 0) bytesSinceLastReport / timeDiffSec else 0f
                                val speedStr = formatSpeed(speedBytesPerSec)

                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes * 100f).coerceIn(0f, 99f)
                                } else 50f

                                val etaStr = if (totalBytes > downloadedBytes && speedBytesPerSec > 0) {
                                    val remainingSec = ((totalBytes - downloadedBytes) / speedBytesPerSec).toLong()
                                    formatEta(remainingSec)
                                } else ""

                                onProgressUpdate(progress, speedStr, etaStr, TaskStatus.DOWNLOADING)
                                lastReportTime = now
                                bytesSinceLastReport = 0L
                            }
                        }
                        output.flush()
                    }
                }
                if (totalBytes > 0 && downloadedBytes != totalBytes) throw IOException("网络传输中断，媒体长度与响应总长不一致")

                // 正常写完，清除异常标记并退出重试循环
                lastException = null
                break
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastException = e
                activeHttpCalls.remove(task.id)
                Log.w(TAG, "第 ${retryCount + 1} 次直链流式下载中断: ${e.message}")

                // 检查任务是否已被用户取消
                val isCancelled = cancelledTaskIds.contains(task.id)
                if (isCancelled) {
                    Log.d(TAG, "任务已被用户取消，终止重连重试")
                    break
                }
                // A stale signed URL cannot be repaired by requesting it again.
                // Return to the scoped domestic-source recovery path instead.
                if (e is MediaHttpException && e.status in setOf(401, 403, 404, 405, 410)) break

                retryCount++
                if (retryCount <= maxRetries) {
                    onProgressUpdate(
                        if (tempFile.exists()) (tempFile.length().toFloat() / 1024f / 1024f) else 0f,
                        "",
                        "网络抖动，第 $retryCount 次自动重连续传中...",
                        TaskStatus.DOWNLOADING
                    )
                    kotlinx.coroutines.delay((600L * retryCount).coerceAtMost(2000L))
                }
            } finally {
                openResponse?.close()
                activeHttpCalls.remove(task.id)
            }
        }

        if (lastException != null || cancelledTaskIds.contains(task.id)) {
            tempFile.delete()
            resumeFile.delete()
            val failure = lastException ?: IOException("下载已取消")
            Log.e(TAG, "直链下载彻底失败: ${failure.message}", failure)
            val msg = formatDetailedError(failure)
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            return@withContext Result.failure(Exception(msg, lastException))
        }

        // 检查下载到的文件是否以 #EXTM3U 开头
        val isM3u8Content = try {
            val headerBytes = ByteArray(16)
            FileInputStream(tempFile).use { it.read(headerBytes) }
            String(headerBytes, Charsets.ISO_8859_1).startsWith("#EXTM3U", ignoreCase = true)
        } catch (_: Exception) { false }

        if (isM3u8Content) {
            tempFile.delete()
            Log.d(TAG, "下载文件内容为 #EXTM3U 列表，重新调度至 M3U8 合成管线")
            return@withContext downloadM3u8Stream(
                context = context,
                task = task,
                m3u8Url = targetUrl,
                proxyUrl = proxyUrl,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate,
                requestHeaders = requestHeaders
            )
        }

        // 核心防御：媒体文件特征码严格校验！
        if (!isValidMediaFile(tempFile)) {
            val size = tempFile.length()
            tempFile.delete()
            val errMsg = "下载文件未通过媒体有效性校验 (大小: ${size} 字节)，可能是防盗链拦截或失效网页"
            Log.e(TAG, errMsg)
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            return@withContext Result.failure(Exception(errMsg))
        }

        finishDirectMedia(context, task, tempFile, stagingDir, publicDir, onProgressUpdate)
    }

    private suspend fun finishDirectMedia(
        context: Context,
        task: DownloadTask,
        source: File,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> {
        val ext = when (task.downloadType) {
            DownloadType.AUDIO_ONLY -> task.audioFormat.ext
            DownloadType.GIF -> "gif"
            else -> "mp4"
        }
        val title = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            .ifBlank { "OmniVideo_${task.id}" }.take(60)
        val processed = File(stagingDir, "processed_${task.id}.$ext")
        var destination = File(publicDir, "$title.$ext")
        var counter = 1
        while (destination.exists()) destination = File(publicDir, "${title}_${counter++}.$ext")
        var ownsDestination = false
        try {
            if (cancelledTaskIds.contains(task.id)) throw IOException("下载已取消")
            onProgressUpdate(95f, "", "正在处理并保存媒体...", TaskStatus.PROCESSING)
            val conversion = when (task.downloadType) {
                DownloadType.AUDIO_ONLY -> FFmpegExecutor.extractAudio(context, source, processed, task.audioFormat.ext, forceEncode = true)
                DownloadType.VIDEO_ONLY -> FFmpegExecutor.stripAudio(context, source, processed)
                DownloadType.GIF -> FFmpegExecutor.convertToGif(context, source, processed)
                else -> Result.success(Unit)
            }
            conversion.getOrThrow()
            if (cancelledTaskIds.contains(task.id)) throw IOException("下载已取消")
            val output = if (task.downloadType in setOf(DownloadType.AUDIO_ONLY, DownloadType.VIDEO_ONLY, DownloadType.GIF)) processed else source
            if (!output.exists() || output.length() == 0L) throw IOException("媒体处理未生成有效文件")
            if (!destination.createNewFile()) throw IOException("目标文件已存在，请重试")
            ownsDestination = true
            output.copyTo(destination, overwrite = true)
            try {
                MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
            } catch (_: Exception) {}
            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            return Result.success(destination.absolutePath)
        } catch (e: Exception) {
            if (ownsDestination) destination.delete()
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            return Result.failure(e)
        } finally {
            source.delete()
            processed.delete()
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.getDefault(), "%.1f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.getDefault(), "%d B/s", bytesPerSec.toInt())
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    suspend fun updateEngine(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            Result.success(status?.name ?: "规则引擎更新完成")
        } catch (e: Exception) {
            val msg = formatDetailedError(e)
            Result.failure(Exception(msg, e))
        }
    }

    /**
     * 从可能夹杂警告/提示的输出中提取最后一个完整有效的 JSON
     */
    private fun extractValidJson(rawOutput: String): JSONObject? {
        val lines = rawOutput.lines()
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return JSONObject(trimmed)
                } catch (ignored: Exception) {
                }
            }
        }
        val firstBrace = rawOutput.indexOf('{')
        val lastBrace = rawOutput.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            try {
                return JSONObject(rawOutput.substring(firstBrace, lastBrace + 1))
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    internal fun parseMetadataFromJson(json: JSONObject, url: String, site: String): VideoMetadata {
        val entries = json.optJSONArray("entries")
        if (entries != null && entries.length() > 0) {
            val validEntries = mutableListOf<JSONObject>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i)
                if (entry != null) {
                    validEntries.add(entry)
                }
            }

            if (validEntries.size > 1) {
                val subList = validEntries.mapIndexedNotNull { index, entryJson ->
                    val childUrl = MediaSelection.childUrl(url, site, index,
                        entryJson.optString("webpage_url"), entryJson.optString("original_url"),
                        entryJson.optString("url"), entryJson.optString("id"), MetadataSnapshots.formatUrls(entryJson)) ?: return@mapIndexedNotNull null
                    parseSingleVideoMetadata(entryJson, childUrl, site, videoIndex = index + 1, totalVideos = validEntries.size)
                }
                require(subList.isNotEmpty()) { "[MEDIA_UNCONFIRMED] 合集没有返回可定位的媒体，请重新解析" }
                val selected = MediaSelection.initialIndex(url, url, subList.map { it.url })
                val currentMeta = subList.getOrElse(selected) { parseSingleVideoMetadata(json, url, site) }
                return currentMeta.copy(
                    multiMediaList = subList
                )
            } else if (validEntries.size == 1) {
                return parseSingleVideoMetadata(validEntries[0], url, site, 1, 1)
            }
        }

        return parseSingleVideoMetadata(json, url, site, 1, 1)
    }

    private fun parseSingleVideoMetadata(
        json: JSONObject,
        url: String,
        site: String,
        videoIndex: Int = 1,
        totalVideos: Int = 1
    ): VideoMetadata {
        val rawTitle = json.optString("title", "未知媒体")
        val title = if (totalVideos > 1 && !rawTitle.contains("视频 $videoIndex")) {
            "$rawTitle (视频 $videoIndex)"
        } else {
            rawTitle
        }
        val uploader = json.optString("uploader", json.optString("channel", site))
        val duration = json.optInt("duration", 0)
        val thumbnail = json.optString("thumbnail", "")

        val formatsJson = json.optJSONArray("formats") ?: JSONArray()
        // 以 format_id 为键保留每个真实格式，不再用第一个同高度格式代表全部编码
        val videoFormatsById = linkedMapOf<String, FormatOption>()
        val audioFormatsList = mutableListOf<FormatOption>()
        var hasTwitterRegularVideo = false
        var hasTwitterTweetVideo = false

        for (i in 0 until formatsJson.length()) {
            val f = formatsJson.optJSONObject(i) ?: continue
            val formatUrl = f.optString("url", "")
            val formatId = f.optString("format_id", "")
            if (site == "X (Twitter)") {
                if (formatUrl.contains("ext_tw_video") || formatUrl.contains("amplify_video")) {
                    hasTwitterRegularVideo = true
                }
                if (formatUrl.contains("video.twimg.com/tweet_video/")) {
                    hasTwitterTweetVideo = true
                }
            }

            val h = f.optInt("height", 0)
            val w = f.optInt("width", 0)
            val vcodec = f.optString("vcodec", "")
            val acodec = f.optString("acodec", "")
            val isAudioOnly = (vcodec == "none" || vcodec.isEmpty()) && acodec != "none" && acodec.isNotEmpty()
            val isVideoOnly = (acodec == "none" || acodec.isEmpty()) && vcodec != "none" && vcodec.isNotEmpty()

            val sizeBytes = f.optLong("filesize", f.optLong("filesize_approx", 0))
            val sizeStr = if (sizeBytes > 0) formatBytes(sizeBytes) else ""

            if (isAudioOnly) {
                val abr = f.optInt("abr", 0)
                audioFormatsList.add(
                    FormatOption(
                        formatId = f.optString("format_id", "audio"),
                        resolutionLabel = if (abr > 0) "$abr kbps" else "高音质",
                        ext = f.optString("ext", "m4a"),
                        approximateSize = sizeStr,
                        isAudioOnly = true
                    )
                )
            } else if (h > 0 || w > 0 || (site in DomesticSourceRecovery.platforms && formatUrl.startsWith("http")) || (site == LinkNormalizer.PLATFORM_X && formatUrl.contains("video.twimg.com/tweet_video/"))) {
                val label = if (w > 0 || h > 0) QualitySelector.label(w, h)
                    else if (site == LinkNormalizer.PLATFORM_DOUYIN) "源视频（尺寸待确认）"
                    else if (site == LinkNormalizer.PLATFORM_XHS) "原始源（尺寸待确认）" else "源动图（尺寸未知）"

                val key = formatId.ifBlank { "$h-$vcodec-$w" }
                val existing = videoFormatsById[key]
                if (existing == null ||
                    (sizeStr.isNotEmpty() && existing.approximateSize.isEmpty())
                ) {
                    videoFormatsById[key] = FormatOption(
                        formatId = formatId.ifBlank { key },
                        resolutionLabel = label,
                        width = w,
                        height = h,
                        fps = f.optInt("fps", 30),
                        ext = f.optString("ext", "mp4"),
                        approximateSize = sizeStr,
                        isVideoOnly = isVideoOnly,
                        sourceUrl = formatUrl,
                        sourceHeaders = buildMap {
                            val headers = f.optJSONObject("http_headers") ?: json.optJSONObject("http_headers")
                            headers?.keys()?.forEach { key -> if (!key.equals("Cookie", true) && !key.equals("Authorization", true)) put(key, headers.optString(key)) }
                        },
                        dynamicRange = f.optString("dynamic_range"),
                        videoBitrateKbps = f.optDouble("tbr", 0.0),
                        isUpscaled = f.optBoolean("is_upscaled"),
                        vcodec = vcodec.takeIf { it != "none" }.orEmpty(),
                        acodec = acodec.takeIf { it != "none" && it != "unknown" }.orEmpty(),
                        note = vcodec.takeIf { it.isNotBlank() && it != "none" }
                            ?.substringBefore('.')
                            ?.uppercase()
                            .orEmpty()
                    )
                }
            }
        }

        // Twitter GIF 严格特征判定：仅当流来自 tweet_video 专用动图路径且无常规视频流与音频轨时，才标记为动图
        val isGifDetected = (site == "X (Twitter)" && hasTwitterTweetVideo && !hasTwitterRegularVideo && audioFormatsList.isEmpty())

        val sortedVideoFormats = if (videoFormatsById.isNotEmpty()) {
            val sorted = videoFormatsById.values.sortedWith(
                compareByDescending<FormatOption> { it.width.toLong() * it.height }.thenByDescending { it.fps }
            )
            if (isGifDetected) {
                listOf(
                    FormatOption(
                        formatId = "gif",
                        resolutionLabel = "GIF 动图 (原画循环)",
                        ext = "gif",
                        note = "直接导出为免播放器循环动图",
                        isSynthetic = true
                    )
                ) + sorted
            } else {
                sorted
            }
        } else {
            error("[MEDIA_UNCONFIRMED] 本次未取得可验证的视频流，请重试或验证会话")
        }

        return VideoMetadata(
            url = url,
            title = title,
            author = uploader,
            durationText = formatDuration(duration),
            thumbnailUrl = thumbnail,
            siteName = site,
            isGif = isGifDetected,
            mediaId = json.optString("id"),
            expectedDurationMs = duration.toLong() * 1000,
            availableVideoFormats = sortedVideoFormats,
            availableAudioFormats = audioFormatsList,
            qualityWarning = json.optString("quality_warning")
        )
    }

    /**
     * 统一错误描述：先按稳定错误码分类，再对原始文本脱敏。
     * 日志与 UI 都拿到 [CODE] 前缀，便于判断下一步动作。
     */
    private fun formatDetailedError(e: Throwable): String {
        val root = getRootCause(e)
        val raw = root.message ?: e.message ?: "未知异常"
        val safe = LogSanitizer.sanitize(raw)
        return MediaErrors.describe(MediaErrors.classify(safe), safe)
    }

    private fun planForTask(task: DownloadTask): YtDlpOptionsFactory.DownloadFormatPlan {
        val quality = task.qualitySelection
        val maxHeight = quality.maxHeight.takeIf { it > 0 }
            ?: extractHeightFromResolution(task.selectedResolution)
        // 直链任务的 format ID 来自另一套提取器，不能直接套用
        val exactFormatId = if (UrlSniffer.isDirectMediaUrl(task.url) || task.url.contains("twimg.com")) {
            ""
        } else {
            task.selectedVideoFormatId
        }
        return YtDlpOptionsFactory.DownloadFormatPlan(
            downloadType = task.downloadType,
            selectedFormatId = exactFormatId,
            selectedFormatIsMuxed = task.selectedVideoIsMuxed && exactFormatId.isNotBlank(),
            maxHeight = maxHeight,
            resolutionLabel = task.selectedResolution,
            audioFormatExt = task.audioFormat.ext,
            allowLowerQuality = quality.allowLowerQuality,
            container = quality.container
        )
    }

    private fun getRootCause(throwable: Throwable): Throwable {
        var cause = throwable
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun extractHeightFromResolution(resolutionLabel: String): Int {
        val lower = resolutionLabel.lowercase(java.util.Locale.ROOT).trim()
        if (lower.contains("自适应") || lower.contains("最佳") || lower.contains("最高") ||
            lower.contains("auto") || lower.contains("best") || lower.contains("默认")) {
            return 0
        }
        if (lower.contains("8k") || lower.contains("4320")) return 4320
        if (lower.contains("6k") || lower.contains("3240")) return 3240
        if (lower.contains("5k") || lower.contains("2880")) return 2880
        if (lower.contains("4k") || lower.contains("2160")) return 2160
        if (lower.contains("2k") || lower.contains("1440")) return 1440
        val regex = Regex("(\\d{3,4})")
        val match = regex.find(resolutionLabel)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun parseSpeedFromLine(line: String): String {
        val regex = Regex("""at\s+([0-9.]+[kKMGT]?i?B/s)""")
        return regex.find(line)?.groupValues?.get(1) ?: ""
    }

    private fun findGeneratedFile(dir: File, taskId: String, hintPath: String? = null): File? {
        if (!hintPath.isNullOrEmpty()) {
            val f = File(hintPath)
            if (f.exists() && f.length() > 0) return f
        }
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    private fun isGifMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return try {
            file.inputStream().use {
                val bytes = ByteArray(6)
                val read = it.read(bytes)
                if (read >= 6) {
                    val header = String(bytes, Charsets.US_ASCII)
                    header == "GIF87a" || header == "GIF89a"
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }
}
