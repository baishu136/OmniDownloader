package com.omni.downloader.engine

import android.webkit.CookieManager
import com.omni.downloader.data.model.DownloadType
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * 统一构造 yt-dlp 参数。
 *
 * 之前的解析、下载和“安全通道”重试各自拼一套参数，导致：
 * - 重试时丢失 -f、请求头和导出类型；
 * - YouTube 被强制指定 android,ios,mweb，绕开上游默认修复；
 * - 每个调用点都可能漏掉代理或站点请求头。
 *
 * 现在所有路径都从这里生成参数，重试只调整输出目录与分片并发。
 */
object YtDlpOptionsFactory {

    /** 一次下载实际要执行的选流意图，独立于 UI 文案。 */
    data class DownloadFormatPlan(
        val downloadType: DownloadType,
        val selectedFormatId: String = "",
        val selectedFormatIsMuxed: Boolean = false,
        val maxHeight: Int = 0,
        val resolutionLabel: String = "",
        val audioFormatExt: String = "mp3",
        val allowLowerQuality: Boolean = false,
        val container: String = "mp4"
    )

    private val PSEUDO_FORMAT_IDS = setOf("best", "1080", "720", "480", "360", "gif")

    /**
     * @param allowPlaylist 合集/播放列表 URL 必须允许展开 entries；
     *                      单视频 URL 才加 --no-playlist，避免把视频误当合集。
     */
    fun metadataRequest(
        url: String,
        site: String,
        proxyUrl: String,
        allowPlaylist: Boolean,
        cookieFile: File? = null
    ): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("-J")
        if (!allowPlaylist) {
            addOption("--no-playlist")
        }
        addDiagnosticsOptions()
        addNetworkOptions(proxyUrl)
        addCookieFile(cookieFile)
        addSiteHeaders(this, site)
        if (site == LinkNormalizer.PLATFORM_X) {
            addOption("--socket-timeout", "12")
            addOption("--retries", "1")
            addOption("--extractor-retries", "0")
        }
    }

    fun downloadRequest(
        url: String,
        site: String,
        proxyUrl: String,
        plan: DownloadFormatPlan,
        outputPattern: String,
        safeTransport: Boolean = false,
        cookieFile: File? = null,
        infoFile: File? = null
    ): YoutubeDLRequest = YoutubeDLRequest(if (infoFile != null) emptyList() else listOf(url)).apply {
        infoFile?.let { addOption("--load-info-json", it.path) }
        addOption("-o", outputPattern)
        addOption("--no-playlist")
        addDiagnosticsOptions()
        addNetworkOptions(proxyUrl)
        addCookieFile(cookieFile)
        addOutputOptions()

        // 安全通道只降低并发/分片压力，不改变选流与导出类型
        addOption("--concurrent-fragments", if (safeTransport) "1" else "1")
        if (!safeTransport) {
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
        } else {
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
        }

        addSiteHeaders(this, site)
        addOption("-S", "res,fps")
        applyFormatPlan(this, plan)
    }

    /**
     * 精确格式失效时的回退请求：保留站点、代理与导出类型，只把选流降级为质量意图。
     * 返回 null 表示当前任务本身没有可回退空间。
     */
    fun fallbackQualityPlan(plan: DownloadFormatPlan): DownloadFormatPlan? {
        if (plan.selectedFormatId.isBlank() || !plan.allowLowerQuality) return null
        return plan.copy(
            selectedFormatId = "",
            selectedFormatIsMuxed = false,
            allowLowerQuality = true
        )
    }

    fun describe(plan: DownloadFormatPlan): String {
        val parts = mutableListOf("type=${plan.downloadType}", "height<=${plan.maxHeight}")
        if (plan.selectedFormatId.isNotBlank()) parts += "format=${plan.selectedFormatId}"
        if (plan.selectedFormatIsMuxed) parts += "muxed=true"
        if (!plan.allowLowerQuality) parts += "allowLower=false"
        return parts.joinToString(", ")
    }

    private fun YoutubeDLRequest.addDiagnosticsOptions() {
        addOption("--no-cache-dir")
        addOption("--ignore-config")
        addOption("--geo-bypass")
        addOption("--socket-timeout", "30")
    }

    private fun YoutubeDLRequest.addNetworkOptions(proxyUrl: String) {
        if (proxyUrl.isNotBlank()) {
            addOption("--proxy", proxyUrl.trim())
        }
    }

    private fun YoutubeDLRequest.addCookieFile(cookieFile: File?) {
        if (cookieFile != null && cookieFile.exists()) {
            addOption("--cookies", cookieFile.absolutePath)
        }
    }

    private fun YoutubeDLRequest.addOutputOptions() {
        addOption("--no-mtime")
        addOption("--windows-filenames")
        addOption("--restrict-filenames")
        addOption("--no-part")
    }

    /** 站点请求头与 Cookie。Cookie 只用于对应平台，不跨域发送。 */
    private fun addSiteHeaders(request: YoutubeDLRequest, site: String) {
        when (site) {
            LinkNormalizer.PLATFORM_BILIBILI -> {
                request.addOption("--add-header", "Referer:https://www.bilibili.com")
            }
            LinkNormalizer.PLATFORM_X -> {
                // 默认使用上游提取器策略；仅在需要时补充来源站点请求头
                request.addOption("--add-header", "Referer:https://x.com/")
            }
            LinkNormalizer.PLATFORM_INSTAGRAM -> {
                // The extractor supplies endpoint-specific identities. A global mobile
                // Safari UA makes Instagram's API return an empty-media login error.
            }
            LinkNormalizer.PLATFORM_FACEBOOK -> {
                request.addOption(
                    "--add-header",
                    "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
            LinkNormalizer.PLATFORM_PINTEREST -> {
                request.addOption(
                    "--add-header",
                    "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
            LinkNormalizer.PLATFORM_DOUYIN -> {
                request.addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                request.addOption("--add-header", "Referer:https://www.douyin.com/")

            }
            LinkNormalizer.PLATFORM_KUAISHOU -> {
                request.addOption("--add-header", "User-Agent:Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                request.addOption("--add-header", "Referer:https://www.kuaishou.com/")
            }
            LinkNormalizer.PLATFORM_XHS -> {
                request.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                request.addOption("--add-header", "Referer:https://www.xiaohongshu.com/")
            }
            LinkNormalizer.PLATFORM_TIKTOK -> {
                // 使用 yt-dlp 内置客户端指纹
            }
        }
    }

    private fun applyFormatPlan(request: YoutubeDLRequest, plan: DownloadFormatPlan) {
        when (plan.downloadType) {
            DownloadType.IMAGE, DownloadType.IMAGE_ZIP -> error("图文任务使用专用原图下载器")
            DownloadType.VIDEO_WITH_AUDIO -> {
                request.addOption("-f", videoSelector(plan))
                request.addOption("--merge-output-format", plan.containerOr("mp4"))
            }
            DownloadType.VIDEO_ONLY -> {
                request.addOption("-f", videoOnlySelector(plan))
                request.addOption("--postprocessor-args", "ffmpeg:-an")
            }
            DownloadType.AUDIO_ONLY -> {
                request.addOption("-x")
                request.addOption("--audio-format", plan.audioFormatExt)
                request.addOption("--audio-quality", "0")
                request.addOption("-f", "bestaudio/best")
                request.addOption("--add-metadata")
            }
            DownloadType.GIF -> {
                request.addOption("-f", "bestvideo/best")
            }
            DownloadType.COVER -> {
                request.addOption("--write-thumbnail")
                request.addOption("--skip-download")
            }
        }
    }

    private fun videoSelector(plan: DownloadFormatPlan): String {
        val id = usableExactId(plan)
        val h = plan.maxHeight
        return when {
            id != null && plan.selectedFormatIsMuxed -> id
            id != null -> "$id+bestaudio"
            h > 0 -> "bestvideo[height<=$h]+bestaudio/best[height<=$h]"
            else -> "bv*+ba/b"
        }
    }

    private fun videoOnlySelector(plan: DownloadFormatPlan): String {
        val id = usableExactId(plan)
        val h = plan.maxHeight
        return when {
            id != null -> id
            h > 0 -> "bestvideo[height<=$h]/best[height<=$h]"
            else -> "bestvideo/best"
        }
    }

    private fun usableExactId(plan: DownloadFormatPlan): String? {
        val id = plan.selectedFormatId.trim()
        if (id.isBlank()) return null
        if (id.lowercase() in PSEUDO_FORMAT_IDS) return null
        if (id.startsWith("bili_")) return null
        if (!Regex("""[A-Za-z0-9._-]{1,64}""").matches(id)) return null
        return id
    }

    private fun DownloadFormatPlan.containerOr(fallback: String): String = if (container == "auto") "mkv" else container.ifBlank { fallback }
}
