package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Log
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
                try {
                    FFmpeg.getInstance().init(appContext)
                } catch (fe: Exception) {
                    Log.w(TAG, "FFmpeg 已经初始化或忽略微异常: ${fe.message}")
                }

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
        proxyUrl: String = ""
    ): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(rawUrl)
        val site = UrlSniffer.identifySite(cleanUrl)

        Log.d(TAG, "开始解析: site=$site, cleanUrl=$cleanUrl")

        // 1. 哔哩哔哩优先走原生直连解析 (免 Python 依赖，毫秒级，100% 成功率)
        if (site == "哔哩哔哩") {
            val directResult = BilibiliDirectExtractor.extract(cleanUrl)
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
        if (DomesticDirectExtractor.isSupported(site)) {
            val domesticResult = DomesticDirectExtractor.extract(cleanUrl, site)
            if (domesticResult.isSuccess) {
                return@withContext domesticResult
            }
            Log.w(TAG, "$site 原生解析未命中，将降级尝试通用规则引擎: ${domesticResult.exceptionOrNull()?.message}")
        }

        // 4. 通用与海外平台走健壮版 yt-dlp 引擎
        val initRes = ensureInitialized(context)
        if (initRes.isFailure) {
            return@withContext Result.failure(initRes.exceptionOrNull() ?: Exception("引擎未就绪"))
        }

        try {
            val isXPlatform = site == "X (Twitter)"
            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("-J") // 完整 JSON 输出模式（支持单视频与多视频推文 Playlist）
                if (!isXPlatform) {
                    addOption("--no-playlist")
                }
                addOption("--no-check-certificate")
                addOption("--no-warnings")
                addOption("--no-cache-dir")
                addOption("--ignore-config")
                addOption("--geo-bypass")
                addOption("--socket-timeout", "30")

                if (proxyUrl.isNotBlank()) {
                    addOption("--proxy", proxyUrl.trim())
                }

                when (site) {
                    "哔哩哔哩" -> {
                        addOption("--add-header", "Referer:https://www.bilibili.com")
                    }
                    "YouTube" -> {
                        addOption("--extractor-args", "youtube:player_client=android,ios,mweb")
                    }
                    "X (Twitter)" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
                    }
                    "TikTok" -> {
                        // 使用 yt-dlp 自身内置的真实客户端指纹，避免被 TikTok WAF 强校验拦截
                    }
                    "Instagram" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                    }
                    "Facebook" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    "Pinterest" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    "抖音" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                        addOption("--add-header", "Referer:https://www.douyin.com/")
                    }
                    "快手" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                        addOption("--add-header", "Referer:https://www.kuaishou.com/")
                    }
                    "小红书" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        addOption("--add-header", "Referer:https://www.xiaohongshu.com/")
                    }
                }
            }

            // 自己执行并安全提取 JSON，防止因为控制台多余文本导致解析崩溃
            val response = YoutubeDL.getInstance().execute(request)
            val output = response.out
            val jsonObject = extractValidJson(output)
                ?: return@withContext Result.failure(Exception("解析输出未包含有效媒体数据，原始反馈: ${output.take(200)}"))

            val metadata = parseMetadataFromJson(jsonObject, cleanUrl, site)
            Result.success(metadata)
        } catch (e: Exception) {
            val errorMsg = formatDetailedError(e)
            Log.e(TAG, "yt-dlp 解析报错: $errorMsg", e)
            Result.failure(Exception(errorMsg, e))
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

            val clientBuilder = OkHttpClient.Builder()
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
        context: Context,
        task: DownloadTask,
        proxyUrl: String = "",
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val site = UrlSniffer.identifySite(cleanUrl)

        val publicDir = getDownloadDir(context).apply { mkdirs() }
        val stagingDir = File(context.cacheDir, "staging").apply { mkdirs() }

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

        // 0.5 中转站直链或媒体直接下载链接，优先走 OkHttp 高速流式下载 (彻底避免 yt-dlp 启动开销与超长文件名报错)
        val isDirectStream = task.selectedResolution == "中转直链" ||
                task.selectedResolution.startsWith("http") ||
                UrlSniffer.isDirectMediaUrl(cleanUrl) ||
                cleanUrl.contains("snapcdn.app") ||
                cleanUrl.contains("twimg.com")

        if (isDirectStream) {
            val targetStreamUrl = if (task.selectedResolution.startsWith("http")) {
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
                onProgressUpdate = onProgressUpdate
            )

            // 如果首次下载失败且存在备用地址（如 selectedResolution 与 task.url 不一致），尝试备用地址
            if (directResult.isFailure) {
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
            if (task.selectedResolution == "中转直链" || task.selectedResolution.startsWith("http")) {
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
                task = task,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
            if (directResult.isSuccess) {
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

        // 3. 国内短视频平台（抖音、快手、小红书）原生极速下载
        if (DomesticDirectExtractor.isSupported(site) && (task.selectedResolution.startsWith("http") || task.selectedResolution.contains("原画") || task.downloadType == DownloadType.AUDIO_ONLY)) {
            val domesticResult = DomesticDirectExtractor.download(
                context = context,
                task = task,
                stagingDir = stagingDir,
                publicDir = publicDir,
                onProgressUpdate = onProgressUpdate
            )
            if (domesticResult.isSuccess) {
                return@withContext domesticResult
            }
            Log.w(TAG, "$site 原生直接下载失败，尝试通用规则引擎下载: ${domesticResult.exceptionOrNull()?.message}")
        }

        // 4. 通用与海外媒体走 yt-dlp 引擎
        val initResult = ensureInitialized(context)
        if (initResult.isFailure) {
            return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("引擎未就绪"))
        }

        try {
            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("-o", "${stagingDir.absolutePath}/%(title).50s-%(id).30s.%(ext)s")
                addOption("--no-playlist")
                addOption("--no-check-certificate")
                addOption("--no-warnings")
                addOption("--ignore-config")
                addOption("--no-cache-dir")
                addOption("--no-mtime")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--concurrent-fragments", "1")
                addOption("--windows-filenames")
                addOption("--restrict-filenames")
                addOption("--no-part")

                if (proxyUrl.isNotBlank()) {
                    addOption("--proxy", proxyUrl.trim())
                }

                when (site) {
                    "哔哩哔哩" -> {
                        addOption("--add-header", "Referer:https://www.bilibili.com")
                    }
                    "YouTube" -> {
                        addOption("--extractor-args", "youtube:player_client=android,ios,mweb")
                    }
                    "X (Twitter)" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
                    }
                    "TikTok" -> {
                        // 使用 yt-dlp 自身内置的真实客户端指纹，避免被 TikTok WAF 强校验拦截
                    }
                    "Instagram" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                    }
                    "Facebook" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    "Pinterest" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    "抖音" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                        addOption("--add-header", "Referer:https://www.douyin.com/")
                    }
                    "快手" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                        addOption("--add-header", "Referer:https://www.kuaishou.com/")
                    }
                    "小红书" -> {
                        addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        addOption("--add-header", "Referer:https://www.xiaohongshu.com/")
                    }
                }

                when (task.downloadType) {
                    DownloadType.VIDEO_WITH_AUDIO -> {
                        val h = extractHeightFromResolution(task.selectedResolution)
                        if (h > 0) {
                            addOption("-f", "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best")
                        } else {
                            addOption("-f", "bestvideo+bestaudio/best")
                        }
                        addOption("--merge-output-format", "mp4")
                    }
                    DownloadType.VIDEO_ONLY -> {
                        val h = extractHeightFromResolution(task.selectedResolution)
                        if (h > 0) {
                            addOption("-f", "bestvideo[height<=$h]/best[height<=$h]")
                        } else {
                            addOption("-f", "bestvideo/best")
                        }
                        addOption("--postprocessor-args", "ffmpeg:-an")
                    }
                    DownloadType.AUDIO_ONLY -> {
                        addOption("-x")
                        addOption("--audio-format", task.audioFormat.ext)
                        addOption("--audio-quality", "0")
                        addOption("-f", "bestaudio/best")
                        addOption("--add-metadata")
                    }
                    DownloadType.GIF -> {
                        addOption("-f", "bestvideo/best")
                    }
                    DownloadType.COVER -> {
                        addOption("--write-thumbnail")
                        addOption("--skip-download")
                    }
                }
            }

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

                    val safeStagingDir = File(context.filesDir, "safe_staging").apply { mkdirs() }
                    safeStagingDir.listFiles()?.filter { it.name.contains(task.id) }?.forEach { it.delete() }

                    val safeRequest = YoutubeDLRequest(cleanUrl).apply {
                        addOption("-o", "${safeStagingDir.absolutePath}/%(id)s.%(ext)s")
                        addOption("--no-playlist")
                        addOption("--no-check-certificate")
                        addOption("--no-warnings")
                        addOption("--ignore-config")
                        addOption("--no-cache-dir")
                        addOption("--no-mtime")
                        addOption("--windows-filenames")
                        addOption("--restrict-filenames")
                        addOption("--no-part")
                        addOption("--concurrent-fragments", "1")
                        addOption("-f", "best/bestvideo+bestaudio")

                        if (proxyUrl.isNotBlank()) {
                            addOption("--proxy", proxyUrl.trim())
                        }
                    }

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

            val finalFile = if (stagedFile != null && stagedFile.exists()) {
                if (task.downloadType == DownloadType.GIF) {
                    onProgressUpdate(95f, "", "正在转换为 GIF 动图 (FFmpeg)...", TaskStatus.PROCESSING)
                    val baseName = stagedFile.nameWithoutExtension
                    val cleanBase = if (baseName.length > 60) baseName.take(60) else baseName
                    val destGif = File(publicDir, "$cleanBase.gif")

                    val isNativeGif = stagedFile.extension.equals("gif", ignoreCase = true) || isGifMagic(stagedFile)
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
                            Log.w(TAG, "GIF 转换未通过，降级保留 MP4 原片: ${gifRes.exceptionOrNull()?.message}")
                            val fallbackDest = File(publicDir, "$cleanBase.mp4")
                            try {
                                stagedFile.copyTo(fallbackDest, overwrite = true)
                                stagedFile.delete()
                            } catch (ce: Exception) {
                                Log.e(TAG, "降级复制 MP4 失败", ce)
                            }
                            if (fallbackDest.exists() && fallbackDest.length() > 0) fallbackDest else stagedFile
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
            Result.failure(Exception(errorMsg, e))
        }
    }

    fun cancelTask(taskId: String) {
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
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        onProgressUpdate(10f, "", "检测到 HLS/M3U8 分片流，正在拉取分片并合成 MP4...", TaskStatus.DOWNLOADING)

        val cleanTitle = task.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "OmniVideo_${System.currentTimeMillis()}" }
        val safeTitle = if (cleanTitle.length > 60) cleanTitle.take(60) else cleanTitle
        val tempFile = File(stagingDir, "m3u8_${task.id}.mp4")

        var destFile = File(publicDir, "$safeTitle.mp4")
        var counter = 1
        while (destFile.exists()) {
            destFile = File(publicDir, "${safeTitle}_$counter.mp4")
            counter++
        }

        // 优先使用本地 FFmpeg 组件直转
        val ffmpeg = FFmpegExecutor.getFFmpegBinary(context)
        if (ffmpeg != null) {
            val referer = getRefererForUrl(m3u8Url)
            val cookie = try {
                CookieManager.getInstance().getCookie(m3u8Url) ?: ""
            } catch (_: Exception) { "" }

            val headersSb = StringBuilder()
            headersSb.append("User-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36\r\n")
            if (referer.isNotBlank()) headersSb.append("Referer: $referer\r\n")
            if (cookie.isNotBlank()) headersSb.append("Cookie: $cookie\r\n")

            val commands = mutableListOf(
                ffmpeg.absolutePath,
                "-y",
                "-headers", headersSb.toString()
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
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                try {
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
                } catch (_: Exception) {}
                onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                return@withContext Result.success(destFile.absolutePath)
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
            }
            try {
                YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
                    val etaStr = if (etaInSeconds > 0) "${etaInSeconds}秒" else ""
                    val speed = parseSpeedFromLine(line)
                    onProgressUpdate(progress, speed, etaStr, TaskStatus.DOWNLOADING)
                }
                val staged = findGeneratedFile(stagingDir, task.id, null)
                if (staged != null && staged.exists() && staged.length() > 4096) {
                    staged.copyTo(destFile, overwrite = true)
                    staged.delete()
                    try {
                        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
                    } catch (_: Exception) {}
                    onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                    return@withContext Result.success(destFile.absolutePath)
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
    private suspend fun downloadDirectMediaStream(
        context: Context,
        task: DownloadTask,
        targetUrl: String,
        proxyUrl: String = "",
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
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
                onProgressUpdate = onProgressUpdate
            )
        }

        val isAudio = task.downloadType == DownloadType.AUDIO_ONLY
        val ext = if (isAudio) "mp3" else "mp4"

        // 规范化文件名，截断为安全长度（小于60字符，彻底杜绝 Linux 文件系统 255 字节超长报错）
        val cleanTitle = task.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "OmniVideo_${System.currentTimeMillis()}" }
        val safeTitle = if (cleanTitle.length > 60) cleanTitle.take(60) else cleanTitle

        val tempFile = File(stagingDir, "direct_${task.id}.$ext")

        var finalFile = File(publicDir, "$safeTitle.$ext")
        var counter = 1
        while (finalFile.exists()) {
            finalFile = File(publicDir, "${safeTitle}_$counter.$ext")
            counter++
        }

        cancelledTaskIds.remove(task.id)

        val clientBuilder = OkHttpClient.Builder()
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
            try {
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
                val cookie = try {
                    CookieManager.getInstance().getCookie(targetUrl)
                } catch (_: Exception) { null }
                if (!cookie.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookie)
                }

                // 支持 HTTP Range 断点续传
                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                    Log.d(TAG, "启用断点续传: 从字节 $existingBytes 继续拉取")
                }

                val call = client.newCall(reqBuilder.build())
                activeHttpCalls[task.id] = call

                val response = call.execute()
                val responseCode = response.code

                if (responseCode == 416) {
                    // 416 表示范围无法满足，即本地已全部下载完毕
                    Log.d(TAG, "服务器返回 416 Range Not Satisfiable，本地文件已为完整内容")
                    lastException = null
                    break
                }

                if (!response.isSuccessful && responseCode != 206) {
                    throw IOException("HTTP 错误: $responseCode")
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

                val isPartial = (responseCode == 206)
                val appendMode = isPartial && existingBytes > 0
                val body = response.body ?: throw IOException("直链响应体为空")

                val contentLength = body.contentLength()
                val totalBytes = if (isPartial && contentLength > 0) {
                    existingBytes + contentLength
                } else if (contentLength > 0) {
                    contentLength
                } else {
                    -1L
                }

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

                // 正常写完，清除异常标记并退出重试循环
                lastException = null
                break
            } catch (e: Exception) {
                lastException = e
                activeHttpCalls.remove(task.id)
                Log.w(TAG, "第 ${retryCount + 1} 次直链流式下载中断: ${e.message}")

                // 检查任务是否已被用户取消
                val isCancelled = cancelledTaskIds.contains(task.id)
                if (isCancelled) {
                    Log.d(TAG, "任务已被用户取消，终止重连重试")
                    break
                }

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
                activeHttpCalls.remove(task.id)
            }
        }

        if (lastException != null && (!tempFile.exists() || tempFile.length() == 0L)) {
            Log.e(TAG, "直链下载彻底失败: ${lastException.message}", lastException)
            val msg = formatDetailedError(lastException)
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
                onProgressUpdate = onProgressUpdate
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

        try {
            onProgressUpdate(99f, "", "正在保存到媒体库...", TaskStatus.PROCESSING)
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()

            try {
                val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
                MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), arrayOf(mimeType), null)
            } catch (ignored: Exception) {}

            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            Result.success(finalFile.absolutePath)
        } catch (e: Exception) {
            val msg = formatDetailedError(e)
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            Result.failure(Exception(msg, e))
        } finally {
            cancelledTaskIds.remove(task.id)
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

    private fun parseMetadataFromJson(json: JSONObject, url: String, site: String): VideoMetadata {
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
                val subList = validEntries.mapIndexed { index, entryJson ->
                    val childUrl = when {
                        entryJson.optString("webpage_url").isNotEmpty() && entryJson.optString("webpage_url").contains("/video/") ->
                            entryJson.optString("webpage_url")
                        else -> {
                            val baseWithoutVideo = url.replace(Regex("/video/\\d+/?$"), "").trimEnd('/')
                            "$baseWithoutVideo/video/${index + 1}"
                        }
                    }
                    parseSingleVideoMetadata(entryJson, childUrl, site, videoIndex = index + 1, totalVideos = validEntries.size)
                }
                val firstMeta = subList.first()
                return firstMeta.copy(
                    title = "${json.optString("title", firstMeta.title)} (共${subList.size}个独立视频)",
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
        val videoFormatsMap = mutableMapOf<Int, FormatOption>()
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
            } else if (h > 0) {
                val label = when {
                    h >= 2160 -> "4K (2160p)"
                    h >= 1440 -> "2K (1440p)"
                    h >= 1080 -> "1080p 全高清"
                    h >= 720 -> "720p 高清"
                    h >= 480 -> "480p 标清"
                    h >= 360 -> "360p 流畅"
                    else -> "${h}p"
                }

                val existing = videoFormatsMap[h]
                if (existing == null || (sizeStr.isNotEmpty() && existing.approximateSize.isEmpty())) {
                    videoFormatsMap[h] = FormatOption(
                        formatId = f.optString("format_id", h.toString()),
                        resolutionLabel = label,
                        width = w,
                        height = h,
                        fps = f.optInt("fps", 30),
                        ext = f.optString("ext", "mp4"),
                        approximateSize = sizeStr,
                        isVideoOnly = isVideoOnly
                    )
                }
            }
        }

        // Twitter GIF 严格特征判定：仅当流来自 tweet_video 专用动图路径且无常规视频流与音频轨时，才标记为动图
        val isGifDetected = (site == "X (Twitter)" && hasTwitterTweetVideo && !hasTwitterRegularVideo && audioFormatsList.isEmpty())

        val sortedVideoFormats = if (videoFormatsMap.isNotEmpty()) {
            val sorted = videoFormatsMap.values.sortedByDescending { it.height }
            if (isGifDetected) {
                listOf(
                    FormatOption(
                        formatId = "gif",
                        resolutionLabel = "GIF 动图 (原画循环)",
                        ext = "gif",
                        note = "直接导出为免播放器循环动图"
                    )
                ) + sorted
            } else {
                sorted
            }
        } else {
            val baseList = listOf(
                FormatOption(formatId = "best", resolutionLabel = "最佳画质 (自动)", ext = "mp4"),
                FormatOption(formatId = "1080", resolutionLabel = "1080p 全高清", height = 1080, ext = "mp4"),
                FormatOption(formatId = "720", resolutionLabel = "720p 高清", height = 720, ext = "mp4"),
                FormatOption(formatId = "480", resolutionLabel = "480p 标清", height = 480, ext = "mp4")
            )
            if (isGifDetected) {
                listOf(
                    FormatOption(
                        formatId = "gif",
                        resolutionLabel = "GIF 动图 (原画循环)",
                        ext = "gif",
                        note = "直接导出为免播放器循环动图"
                    )
                ) + baseList
            } else {
                baseList
            }
        }

        return VideoMetadata(
            url = url,
            title = title,
            author = uploader,
            durationText = formatDuration(duration),
            thumbnailUrl = thumbnail,
            siteName = site,
            isGif = isGifDetected,
            availableVideoFormats = sortedVideoFormats,
            availableAudioFormats = audioFormatsList
        )
    }

    private fun formatDetailedError(e: Throwable): String {
        val root = getRootCause(e)
        val msg = root.message ?: e.message ?: "未知异常"
        return when {
            msg.contains("instance not initialized", ignoreCase = true) ->
                "核心引擎尚未初始化完成，请稍候重试"
            msg.contains("Permission denied", ignoreCase = true) ->
                "系统拦截了运行权限，请检查手机是否开启了安全隔离模式"
            msg.contains("Unable to extract", ignoreCase = true) ->
                "解析受限: 视频需要登录/大会员或平台更新了规则 ($msg)"
            msg.contains("I/O operation on closed file", ignoreCase = true) || msg.contains("closed file", ignoreCase = true) ->
                "下载写入异常 (I/O closed)，建议检查手机可用存储空间或尝试更换清晰度"
            msg.contains("timed out", ignoreCase = true) || msg.contains("Connection refused", ignoreCase = true) || msg.contains("Failed to connect", ignoreCase = true) ->
                "网络连接超时: 解析海外视频 (YouTube/X) 请在设置中开启或配置代理端口"
            else -> msg
        }
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
