package com.omni.downloader.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.omni.downloader.data.model.AudioFormat
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object BilibiliDirectExtractor {

    private const val TAG = "BilibiliExtractor"
    private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val BV_PATTERN = Pattern.compile("""(BV[a-zA-Z0-9]{10})|(av\d+)""", Pattern.CASE_INSENSITIVE)
    private val cookieMap = ConcurrentHashMap<String, String>()

    @Volatile
    var customUserCookie: String = ""

    /**
     * 获取基础会话 Cookie 并拼接用户自定义凭证
     */
    private fun ensureCookies() {
        if (cookieMap.containsKey("buvid3")) return
        try {
            val req = Request.Builder()
                .url("https://www.bilibili.com")
                .header("User-Agent", PC_USER_AGENT)
                .build()
            val resp = httpClient.newCall(req).execute()
            val headers = resp.headers("Set-Cookie")
            for (header in headers) {
                val parts = header.split(";")
                if (parts.isNotEmpty()) {
                    val kv = parts[0].split("=", limit = 2)
                    if (kv.size == 2) {
                        cookieMap[kv[0].trim()] = kv[1].trim()
                    }
                }
            }
            Log.d(TAG, "已获取 B 站会话 Cookie: ${cookieMap.keys}")
        } catch (e: Exception) {
            Log.w(TAG, "获取 B 站初始 Cookie 忽略: ${e.message}")
        }
    }

    private fun getCookieHeader(): String {
        ensureCookies()
        val baseCookie = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val user = customUserCookie.trim()
        if (user.isEmpty()) return baseCookie

        val formattedUser = if (!user.contains("=") && !user.contains(";")) {
            "SESSDATA=$user"
        } else {
            user
        }
        return "$baseCookie; $formattedUser"
    }

    /**
     * 判断是否是 Bilibili 链接并尝试提取 BV 号
     */
    fun extractBvid(url: String): String? {
        val matcher = BV_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(0) else null
    }

    /**
     * 原生秒级直接解析 B 站视频元数据与清晰度列表
     */
    suspend fun extract(rawUrl: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            var resolvedUrl = UrlSniffer.sanitizeAndResolveUrl(rawUrl)
            var bvid = extractBvid(resolvedUrl)

            // 若仍包含 b23.tv 且未提取出 bvid，进行直连页面重定向探测
            if (bvid == null && resolvedUrl.contains("b23.tv")) {
                try {
                    val req = Request.Builder()
                        .url(resolvedUrl)
                        .header("User-Agent", PC_USER_AGENT)
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val finalUrl = resp.request.url.toString()
                    bvid = extractBvid(finalUrl)
                    if (bvid != null) {
                        resolvedUrl = finalUrl
                    }
                } catch (ignored: Exception) {
                }
            }

            if (bvid == null) {
                return@withContext Result.failure(IllegalArgumentException("未能识别到 B 站视频 ID (BV/av)，请确认链接是否有效"))
            }

            ensureCookies()

            // 1. 获取视频元数据
            val viewApiUrl = if (bvid.startsWith("BV", ignoreCase = true)) {
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            } else {
                "https://api.bilibili.com/x/web-interface/view?aid=${bvid.substring(2)}"
            }

            val viewRequest = Request.Builder()
                .url(viewApiUrl)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .header("Cookie", getCookieHeader())
                .build()

            val viewResponse = httpClient.newCall(viewRequest).execute()
            val viewBody = viewResponse.body?.string() ?: ""
            val viewJson = JSONObject(viewBody)

            if (viewJson.optInt("code", -1) != 0) {
                val msg = viewJson.optString("message", "获取视频信息失败")
                return@withContext Result.failure(Exception("B站接口反馈: $msg"))
            }

            val data = viewJson.getJSONObject("data")
            val title = data.optString("title", "B站视频")
            val pic = data.optString("pic", "")
            val duration = data.optInt("duration", 0)
            val owner = data.optJSONObject("owner")?.optString("name", "B站UP主") ?: "B站UP主"
            val cid = data.optLong("cid", 0)

            // 2. 获取清晰度格式 (qn=120, fnval=4048 支持 4K/1080P/HDR 等全清晰度流)
            val playApiUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=120&fnval=4048&fnver=0&fourk=1"
            val playRequest = Request.Builder()
                .url(playApiUrl)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "https://www.bilibili.com/video/$bvid")
                .header("Cookie", getCookieHeader())
                .build()

            val playResponse = httpClient.newCall(playRequest).execute()
            val playBody = playResponse.body?.string() ?: ""
            val playJson = JSONObject(playBody)

            val videoFormats = mutableListOf<FormatOption>()
            val audioFormats = mutableListOf<FormatOption>()

            val playData = playJson.optJSONObject("data")
            val dash = playData?.optJSONObject("dash")

            if (dash != null) {
                val videos = dash.optJSONArray("video")
                val seenHeights = mutableSetOf<Int>()

                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.getJSONObject(i)
                        val h = v.optInt("height", 0)
                        val id = v.optInt("id", 0)
                        val bandwidth = v.optLong("bandwidth", 0)
                        val codecs = v.optString("codecs", "")

                        if (h > 0 && !seenHeights.contains(h)) {
                            seenHeights.add(h)
                            val label = when {
                                h >= 2160 -> "4K (2160p)"
                                h >= 1440 -> "2K (1440p)"
                                h >= 1080 -> "1080p 全高清"
                                h >= 720 -> "720p 高清"
                                h >= 480 -> "480p 标清"
                                else -> "${h}p"
                            }
                            val estSize = if (duration > 0 && bandwidth > 0) {
                                formatBytes((bandwidth * duration) / 8)
                            } else ""

                            videoFormats.add(
                                FormatOption(
                                    formatId = "bili_$id",
                                    resolutionLabel = label,
                                    width = v.optInt("width", 0),
                                    height = h,
                                    fps = v.optInt("frameRate", 30),
                                    approximateSize = estSize,
                                    note = codecs
                                )
                            )
                        }
                    }
                }

                val audios = dash.optJSONArray("audio")
                if (audios != null && audios.length() > 0) {
                    audioFormats.add(
                        FormatOption(
                            formatId = "bili_audio_best",
                            resolutionLabel = "320 kbps 极高音质",
                            ext = "m4a",
                            isAudioOnly = true
                        )
                    )
                }
            }

            if (videoFormats.isEmpty()) {
                videoFormats.addAll(
                    listOf(
                        FormatOption(formatId = "1080", resolutionLabel = "1080p 全高清", height = 1080),
                        FormatOption(formatId = "720", resolutionLabel = "720p 高清", height = 720),
                        FormatOption(formatId = "480", resolutionLabel = "480p 标清", height = 480)
                    )
                )
            }

            videoFormats.sortByDescending { it.height }

            // 3. 提取分P与合集列表
            val multiMediaList = mutableListOf<VideoMetadata>()
            val pagesJson = data.optJSONArray("pages")
            val ugcSeason = data.optJSONObject("ugc_season")

            if (pagesJson != null && pagesJson.length() > 1) {
                for (i in 0 until pagesJson.length()) {
                    val pageObj = pagesJson.optJSONObject(i) ?: continue
                    val pNum = pageObj.optInt("page", i + 1)
                    val partTitle = pageObj.optString("part", "第${pNum}P")
                    val pageDuration = pageObj.optInt("duration", 0)
                    val pagePic = pageObj.optString("first_frame", "").ifEmpty { pic }
                    val finalPic = if (pagePic.startsWith("//")) "https:$pagePic" else pagePic

                    multiMediaList.add(
                        VideoMetadata(
                            url = "https://www.bilibili.com/video/$bvid?p=$pNum",
                            title = "$title - P$pNum $partTitle",
                            author = owner,
                            durationText = formatDuration(pageDuration),
                            thumbnailUrl = finalPic,
                            siteName = "哔哩哔哩",
                            availableVideoFormats = videoFormats,
                            availableAudioFormats = audioFormats
                        )
                    )
                }
            } else if (ugcSeason != null) {
                val seasonTitle = ugcSeason.optString("title", "合集")
                val sections = ugcSeason.optJSONArray("sections")
                if (sections != null) {
                    for (s in 0 until sections.length()) {
                        val secObj = sections.optJSONObject(s) ?: continue
                        val episodes = secObj.optJSONArray("episodes") ?: continue
                        for (e in 0 until episodes.length()) {
                            val ep = episodes.optJSONObject(e) ?: continue
                            val epBvid = ep.optString("bvid", bvid)
                            val epTitle = ep.optString("title", "分集")
                            val arcObj = ep.optJSONObject("arc")
                            val epDuration = arcObj?.optInt("duration", 0) ?: ep.optJSONObject("page")?.optInt("duration", 0) ?: 0
                            val epPic = arcObj?.optString("pic", pic) ?: pic
                            val finalPic = if (epPic.startsWith("//")) "https:$epPic" else epPic

                            multiMediaList.add(
                                VideoMetadata(
                                    url = "https://www.bilibili.com/video/$epBvid",
                                    title = "[$seasonTitle] $epTitle",
                                    author = owner,
                                    durationText = formatDuration(epDuration),
                                    thumbnailUrl = finalPic,
                                    siteName = "哔哩哔哩",
                                    availableVideoFormats = videoFormats,
                                    availableAudioFormats = audioFormats
                                )
                            )
                        }
                    }
                }
            }

            val finalTitle = if (multiMediaList.size > 1) "$title (共${multiMediaList.size}集)" else title
            val meta = VideoMetadata(
                url = "https://www.bilibili.com/video/$bvid",
                title = finalTitle,
                author = owner,
                durationText = formatDuration(duration),
                thumbnailUrl = if (pic.startsWith("//")) "https:$pic" else pic,
                siteName = "哔哩哔哩",
                availableVideoFormats = videoFormats,
                availableAudioFormats = audioFormats,
                multiMediaList = multiMediaList
            )

            Log.d(TAG, "B站解析成功: $finalTitle, 分集数: ${multiMediaList.size}")
            Result.success(meta)
        } catch (e: Exception) {
            Log.e(TAG, "B站解析异常", e)
            Result.failure(e)
        }
    }

    /**
     * B 站视频直连下载
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bvid = extractBvid(task.url)
                ?: return@withContext Result.failure(Exception("无法解析 B 站视频 ID"))

            onProgressUpdate(0f, "", "正在获取高清直链...", TaskStatus.DOWNLOADING)
            ensureCookies()

            // 1. 获取对应分P的 cid 与真实标题
            val viewApiUrl = if (bvid.startsWith("BV", ignoreCase = true)) {
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            } else {
                "https://api.bilibili.com/x/web-interface/view?aid=${bvid.substring(2)}"
            }

            val viewResp = httpClient.newCall(
                Request.Builder()
                    .url(viewApiUrl)
                    .header("User-Agent", PC_USER_AGENT)
                    .header("Referer", "https://www.bilibili.com")
                    .header("Cookie", getCookieHeader())
                    .build()
            ).execute()

            val viewJson = JSONObject(viewResp.body?.string() ?: "")
            if (viewJson.optInt("code", -1) != 0) {
                return@withContext Result.failure(Exception("获取视频元数据失败: ${viewJson.optString("message")}"))
            }

            val dataObj = viewJson.getJSONObject("data")
            var cid = dataObj.optLong("cid", 0)
            var realTitle = dataObj.optString("title", task.title)

            // 匹配分P
            val pages = dataObj.optJSONArray("pages")
            val pageMatch = Regex("""[?&]p=(\d+)""").find(task.url)
            val pageNum = pageMatch?.groupValues?.get(1)?.toIntOrNull()

            if (pageNum != null && pages != null) {
                for (i in 0 until pages.length()) {
                    val p = pages.optJSONObject(i) ?: continue
                    if (p.optInt("page") == pageNum) {
                        cid = p.optLong("cid", cid)
                        val part = p.optString("part", "")
                        realTitle = if (part.isNotBlank()) "$realTitle - P$pageNum $part" else "$realTitle - P$pageNum"
                        break
                    }
                }
            } else if (task.title.isNotBlank()) {
                realTitle = task.title
            }

            val reqHeight = extractHeightFromResolution(task.selectedResolution)
            val qn = when {
                reqHeight >= 1080 -> 80
                reqHeight >= 720 -> 64
                reqHeight >= 480 -> 32
                else -> 80
            }

            // 2. 请求 DASH 流接口 (fnval=4048)
            val playApiUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=4048&fnver=0&fourk=1"
            val playResp = httpClient.newCall(
                Request.Builder()
                    .url(playApiUrl)
                    .header("User-Agent", PC_USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("Cookie", getCookieHeader())
                    .build()
            ).execute()

            val playJson = JSONObject(playResp.body?.string() ?: "")
            val dash = playJson.optJSONObject("data")?.optJSONObject("dash")

            stagingDir.mkdirs()
            publicDir.mkdirs()

            val cleanTitle = sanitizeFileName(realTitle)

            // 处理 VIDEO_WITH_AUDIO 完整音画下载
            if (task.downloadType == DownloadType.VIDEO_WITH_AUDIO) {
                var mergeSuccess = false
                val mergedFile = File(stagingDir, "bili_${task.id}_final.mp4")

                // 优先尝试 DASH 双流下载并 FFmpeg 毫秒混流
                if (dash != null) {
                    val videosJson = dash.optJSONArray("video")
                    val audiosJson = dash.optJSONArray("audio")

                    var bestVideoUrl = ""
                    var bestVideoBackups = listOf<String>()
                    var bestDiff = Int.MAX_VALUE
                    var bestIsAvc = false

                    if (videosJson != null && videosJson.length() > 0) {
                        for (i in 0 until videosJson.length()) {
                            val v = videosJson.getJSONObject(i)
                            val h = v.optInt("height", 0)
                            val codecs = v.optString("codecs", "")
                            val isAvc = codecs.startsWith("avc", ignoreCase = true)
                            val diff = if (reqHeight > 0) Math.abs(h - reqHeight) else 0

                            val isBetter = when {
                                bestVideoUrl.isEmpty() -> true
                                diff < bestDiff -> true
                                diff == bestDiff && isAvc && !bestIsAvc -> true
                                else -> false
                            }

                            if (isBetter) {
                                bestDiff = diff
                                bestIsAvc = isAvc
                                bestVideoUrl = v.optString("baseUrl", v.optString("base_url", ""))
                                val backups = v.optJSONArray("backupUrl") ?: v.optJSONArray("backup_url")
                                bestVideoBackups = parseBackupUrls(backups)
                            }
                        }
                    }

                    var bestAudioUrl = ""
                    var bestAudioBackups = listOf<String>()
                    var maxAudioBandwidth = -1L

                    if (audiosJson != null && audiosJson.length() > 0) {
                        for (i in 0 until audiosJson.length()) {
                            val a = audiosJson.getJSONObject(i)
                            val bw = a.optLong("bandwidth", 0)
                            if (bw > maxAudioBandwidth || bestAudioUrl.isEmpty()) {
                                maxAudioBandwidth = bw
                                bestAudioUrl = a.optString("baseUrl", a.optString("base_url", ""))
                                val backups = a.optJSONArray("backupUrl") ?: a.optJSONArray("backup_url")
                                bestAudioBackups = parseBackupUrls(backups)
                            }
                        }
                    }

                    if (bestVideoUrl.isNotEmpty()) {
                        val tempVideo = File(stagingDir, "bili_${task.id}_video.m4s")
                        val tempAudio = File(stagingDir, "bili_${task.id}_audio.m4s")

                        try {
                            downloadStreamToFile(
                                url = bestVideoUrl,
                                backups = bestVideoBackups,
                                bvid = bvid,
                                dest = tempVideo,
                                startProgress = 0f,
                                progressWeight = 80f,
                                stepTitle = "下载视频画面",
                                onProgressUpdate = onProgressUpdate
                            )

                            if (bestAudioUrl.isNotEmpty()) {
                                downloadStreamToFile(
                                    url = bestAudioUrl,
                                    backups = bestAudioBackups,
                                    bvid = bvid,
                                    dest = tempAudio,
                                    startProgress = 80f,
                                    progressWeight = 15f,
                                    stepTitle = "下载音频轨",
                                    onProgressUpdate = onProgressUpdate
                                )
                            }

                            onProgressUpdate(95f, "", "正在音画无损合成 (FFmpeg)...", TaskStatus.PROCESSING)
                            val mergeRes = if (tempAudio.exists() && tempAudio.length() > 0) {
                                FFmpegExecutor.mergeVideoAndAudio(context, tempVideo, tempAudio, mergedFile)
                            } else {
                                FFmpegExecutor.stripAudio(context, tempVideo, mergedFile)
                            }

                            if (mergeRes.isSuccess && mergedFile.exists() && mergedFile.length() > 1024) {
                                mergeSuccess = true
                            } else {
                                Log.w(TAG, "FFmpeg 混流未通过，将启用 DURL 渐进式备选直链: ${mergeRes.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "DASH 下载或合成异常: ${e.message}")
                        } finally {
                            tempVideo.delete()
                            tempAudio.delete()
                        }
                    }
                }

                // 若 DASH 不可用或合成失败，自动启用 DURL 渐进式一体化 MP4 直连（免合并，100% 成功）
                if (!mergeSuccess) {
                    onProgressUpdate(10f, "", "切换官方 MP4 直链通道...", TaskStatus.DOWNLOADING)
                    val durlSuccess = downloadDurlDirectly(
                        bvid = bvid,
                        cid = cid,
                        qn = qn,
                        dest = mergedFile,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (!durlSuccess || !mergedFile.exists() || mergedFile.length() == 0L) {
                        return@withContext Result.failure(Exception("无法获取或下载该视频的有效流地址"))
                    }
                }

                var finalPublicFile = File(publicDir, "$cleanTitle.mp4")
                try {
                    mergedFile.copyTo(finalPublicFile, overwrite = true)
                } catch (e: Exception) {
                    finalPublicFile = File(publicDir, "bili_${bvid}_${System.currentTimeMillis()}.mp4")
                    mergedFile.copyTo(finalPublicFile, overwrite = true)
                }
                mergedFile.delete()

                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(finalPublicFile.absolutePath), null, null)
                } catch (ignored: Exception) {}

                onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                return@withContext Result.success(finalPublicFile.absolutePath)
            }

            // 处理 VIDEO_ONLY 纯视频画面
            if (task.downloadType == DownloadType.VIDEO_ONLY) {
                val videosJson = dash?.optJSONArray("video")
                var bestVideoUrl = ""
                var bestVideoBackups = listOf<String>()
                if (videosJson != null && videosJson.length() > 0) {
                    val v = videosJson.getJSONObject(0)
                    bestVideoUrl = v.optString("baseUrl", v.optString("base_url", ""))
                    bestVideoBackups = parseBackupUrls(v.optJSONArray("backupUrl") ?: v.optJSONArray("backup_url"))
                }

                if (bestVideoUrl.isEmpty()) {
                    return@withContext Result.failure(Exception("无法获取纯视频画面流"))
                }

                val tempVideo = File(stagingDir, "bili_${task.id}_vonly.m4s")
                downloadStreamToFile(
                    url = bestVideoUrl,
                    backups = bestVideoBackups,
                    bvid = bvid,
                    dest = tempVideo,
                    startProgress = 0f,
                    progressWeight = 90f,
                    stepTitle = "下载纯画面",
                    onProgressUpdate = onProgressUpdate
                )

                onProgressUpdate(92f, "", "封装 MP4 格式...", TaskStatus.PROCESSING)
                val mergedFile = File(stagingDir, "bili_${task.id}_final.mp4")
                val res = FFmpegExecutor.stripAudio(context, tempVideo, mergedFile)
                if (res.isFailure) {
                    // 若 stripAudio 失败，直接作为 mp4 封装
                    tempVideo.copyTo(mergedFile, overwrite = true)
                }

                var finalPublicFile = File(publicDir, "$cleanTitle.mp4")
                try {
                    mergedFile.copyTo(finalPublicFile, overwrite = true)
                } catch (e: Exception) {
                    finalPublicFile = File(publicDir, "bili_${bvid}_${System.currentTimeMillis()}.mp4")
                    mergedFile.copyTo(finalPublicFile, overwrite = true)
                }
                tempVideo.delete()
                mergedFile.delete()

                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(finalPublicFile.absolutePath), null, null)
                } catch (ignored: Exception) {}

                onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                return@withContext Result.success(finalPublicFile.absolutePath)
            }

            // 处理 AUDIO_ONLY 纯音频提取
            if (task.downloadType == DownloadType.AUDIO_ONLY) {
                val audiosJson = dash?.optJSONArray("audio")
                var bestAudioUrl = ""
                var bestAudioBackups = listOf<String>()
                if (audiosJson != null && audiosJson.length() > 0) {
                    var maxBw = -1L
                    for (i in 0 until audiosJson.length()) {
                        val a = audiosJson.getJSONObject(i)
                        val bw = a.optLong("bandwidth", 0)
                        if (bw > maxBw || bestAudioUrl.isEmpty()) {
                            maxBw = bw
                            bestAudioUrl = a.optString("baseUrl", a.optString("base_url", ""))
                            bestAudioBackups = parseBackupUrls(a.optJSONArray("backupUrl") ?: a.optJSONArray("backup_url"))
                        }
                    }
                }

                if (bestAudioUrl.isEmpty()) {
                    return@withContext Result.failure(Exception("无法获取高品质音轨流"))
                }

                val ext = task.audioFormat.ext
                val finalExt = if (ext == "mp3") "mp3" else "m4a"
                val tempAudio = File(stagingDir, "bili_${task.id}_raw_audio.m4s")

                downloadStreamToFile(
                    url = bestAudioUrl,
                    backups = bestAudioBackups,
                    bvid = bvid,
                    dest = tempAudio,
                    startProgress = 0f,
                    progressWeight = 90f,
                    stepTitle = "下载音频数据",
                    onProgressUpdate = onProgressUpdate
                )

                val finalAudioStaging = File(stagingDir, "bili_${task.id}_final.$finalExt")
                if (finalExt == "m4a") {
                    // 原声即为 aac/m4a，直接重命名封装
                    tempAudio.copyTo(finalAudioStaging, overwrite = true)
                } else {
                    onProgressUpdate(92f, "", "正在转换为 ${finalExt.uppercase()}...", TaskStatus.PROCESSING)
                    val extractRes = FFmpegExecutor.extractAudio(context, tempAudio, finalAudioStaging, finalExt)
                    if (extractRes.isFailure) {
                        Log.w(TAG, "转码 MP3 降级为原生 M4A 导出")
                        tempAudio.copyTo(File(stagingDir, "bili_${task.id}_final.m4a"), overwrite = true)
                    }
                }

                val actualExt = if (finalAudioStaging.exists()) finalExt else "m4a"
                val actualStaging = if (finalAudioStaging.exists()) finalAudioStaging else File(stagingDir, "bili_${task.id}_final.m4a")

                var finalPublicFile = File(publicDir, "$cleanTitle.$actualExt")
                try {
                    actualStaging.copyTo(finalPublicFile, overwrite = true)
                } catch (e: Exception) {
                    finalPublicFile = File(publicDir, "bili_${bvid}_${System.currentTimeMillis()}.$actualExt")
                    actualStaging.copyTo(finalPublicFile, overwrite = true)
                }
                tempAudio.delete()
                actualStaging.delete()

                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(finalPublicFile.absolutePath), null, null)
                } catch (ignored: Exception) {}

                onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                return@withContext Result.success(finalPublicFile.absolutePath)
            }

            Result.failure(Exception("未知下载模式"))
        } catch (e: Exception) {
            Log.e(TAG, "B站直连下载执行失败", e)
            Result.failure(e)
        }
    }

    /**
     * 流式下载单个分块到沙盒文件并上报瞬时网速和精准进度
     */
    private fun downloadStreamToFile(
        url: String,
        backups: List<String>,
        bvid: String,
        dest: File,
        startProgress: Float,
        progressWeight: Float,
        stepTitle: String,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ) {
        val targetUrls = mutableListOf(url).apply { addAll(backups) }
        var lastErr: Exception? = null

        for (targetUrl in targetUrls) {
            try {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", PC_USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("Cookie", getCookieHeader())
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP 错误: ${resp.code}")
                }

                val body = resp.body ?: throw IOException("返回内容为空")
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(dest)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L
                var lastTime = SystemClock.elapsedRealtime()
                var bytesSinceLast = 0L

                outputStream.use { out ->
                    inputStream.use { inp ->
                        while (inp.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            bytesSinceLast += bytesRead

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTime >= 150) {
                                val speedBytesPerSec = (bytesSinceLast * 1000) / (now - lastTime)
                                val speedStr = formatBytes(speedBytesPerSec) + "/s"
                                val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0.5f
                                val currentProgress = startProgress + fraction * progressWeight

                                val etaStr = if (totalBytes > 0 && speedBytesPerSec > 0) {
                                    val remainingBytes = totalBytes - downloadedBytes
                                    val etaSec = remainingBytes / speedBytesPerSec
                                    "${etaSec}秒"
                                } else ""

                                onProgressUpdate(currentProgress, speedStr, etaStr, TaskStatus.DOWNLOADING)
                                lastTime = now
                                bytesSinceLast = 0L
                            }
                        }
                    }
                }
                return // 成功下载并退出
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "下载流尝试失败，切换备选流: ${e.message}")
            }
        }
        throw lastErr ?: IOException("所有 CDN 流均下载失败")
    }

    /**
     * 当 DASH 双流或 FFmpeg 混流出现异常时，直接从 B 站官方拉取渐进式一体化 MP4 直链 (无须混流，100% 成功)
     */
    private fun downloadDurlDirectly(
        bvid: String,
        cid: Long,
        qn: Int,
        dest: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit
    ): Boolean {
        try {
            ensureCookies()
            val durlApi = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=0&fnver=0&fourk=1"
            val req = Request.Builder()
                .url(durlApi)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "https://www.bilibili.com/video/$bvid")
                .header("Cookie", getCookieHeader())
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val durlArray = json.optJSONObject("data")?.optJSONArray("durl") ?: return false
            if (durlArray.length() == 0) return false

            val first = durlArray.getJSONObject(0)
            val streamUrl = first.optString("url", "")
            if (streamUrl.isEmpty()) return false
            val backups = parseBackupUrls(first.optJSONArray("backup_url"))

            downloadStreamToFile(
                url = streamUrl,
                backups = backups,
                bvid = bvid,
                dest = dest,
                startProgress = 0f,
                progressWeight = 98f,
                stepTitle = "下载官方 MP4 直链",
                onProgressUpdate = onProgressUpdate
            )
            return true
        } catch (e: Exception) {
            Log.w(TAG, "DURL 渐进式直链备选下载失败: ${e.message}")
            return false
        }
    }

    private fun parseBackupUrls(jsonArray: org.json.JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val u = jsonArray.optString(i, "")
            if (u.isNotEmpty()) list.add(u)
        }
        return list
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        val safe = clean.filter { ch ->
            Character.isDefined(ch) && !Character.isISOControl(ch)
        }
        return if (safe.length > 70) safe.substring(0, 70).trim() else safe.ifEmpty { "video_${System.currentTimeMillis()}" }
    }

    private fun extractHeightFromResolution(resolutionLabel: String): Int {
        val regex = Regex("(\\d{3,4})")
        val match = regex.find(resolutionLabel)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1080
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
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
}
