package com.omni.downloader.engine

import android.content.Context
import android.os.Environment
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

object DownloadEngine {

    private const val TAG = "DownloadEngine"
    private val initMutex = Mutex()
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
                addOption("-o", "${stagingDir.absolutePath}/%(title).80s-%(id)s.%(ext)s")
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
                File(publicDir, "${task.title}.mp4")
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
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (ignored: Exception) {
        }
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

    private fun findGeneratedFile(dir: File, taskId: String, hintPath: String): File? {
        if (hintPath.isNotEmpty()) {
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
