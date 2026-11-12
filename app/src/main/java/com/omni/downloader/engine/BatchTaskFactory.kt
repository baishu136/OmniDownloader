package com.omni.downloader.engine

import com.omni.downloader.data.model.*

object BatchTaskFactory {
    fun videos(item: BatchItem, metadata: VideoMetadata, options: BatchOptions): List<DownloadTask> {
        val selected = if (options.allEpisodes && metadata.multiMediaList.isNotEmpty()) metadata.multiMediaList else
            listOf(metadata.multiMediaList.getOrElse(MediaSelection.initialIndex(item.url, metadata.url, metadata.multiMediaList.map { it.url })) { metadata })
        require(selected.size <= 100) { "分集过多，请使用具体分集链接分批下载" }
        return selected.mapIndexed { index, meta ->
            val policy = QualitySelection(container = if (options.type == DownloadType.AUDIO_ONLY) options.audio.ext else "mp4")
            val formats = meta.availableVideoFormats.filter { !it.isAudioOnly }
            val format = if (formats.any { it.height > 0 && !it.isSynthetic }) QualitySelector.select(formats, policy) else formats.firstOrNull()
            val identity = meta.mediaId.ifBlank { MediaSelection.identity(meta.url) ?: "index:$index" }
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            DownloadTask(id = "batch-${item.id}-$key", url = meta.url, title = meta.title, author = meta.author,
                thumbnailUrl = meta.thumbnailUrl, downloadType = options.type, audioFormat = options.audio,
                schemaVersion = 3, qualitySelection = policy, selectedResolution = format?.resolutionLabel ?: "best",
                selectedVideoFormatId = format?.takeUnless { it.isSynthetic || it.formatId.lowercase() in setOf("best", "gif", "1080", "720", "480", "360") }?.formatId.orEmpty(),
                selectedVideoIsMuxed = format?.let { !it.isVideoOnly && !it.isAudioOnly } ?: false,
                mediaId = meta.mediaId, expectedDurationMs = format?.sourceDurationMs?.takeIf { it > 0 } ?: meta.expectedDurationMs,
                selectedWidth = format?.width ?: 0, selectedHeight = format?.height ?: 0,
                requireAudio = (format?.acodec?.let { it.isNotBlank() && it != "none" } ?: false) || meta.availableAudioFormats.isNotEmpty(),
                directMediaUrl = if (meta.siteName == "X (Twitter)" && format?.isVideoOnly == false) format.sourceUrl else DomesticMediaSource.url(meta, format),
                directMediaHeaders = DomesticMediaSource.headers(meta.siteName) + format?.sourceHeaders.orEmpty(),
                collectionId = if (selected.size > 1) item.id else null,
                collectionTitle = if (selected.size > 1) metadata.title else null, episodeIndex = index + 1, episodeTotal = selected.size)
        }
    }
}
