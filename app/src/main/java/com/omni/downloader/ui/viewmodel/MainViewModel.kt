package com.omni.downloader.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.downloader.data.model.AudioFormat
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.QualityMode
import com.omni.downloader.data.model.QualitySelection
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SessionPlatforms
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.data.repository.SessionStatus
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.engine.MediaErrorCode
import com.omni.downloader.engine.MediaAnalysisRouter
import com.omni.downloader.engine.MediaNavigationRequest
import com.omni.downloader.engine.MediaPage
import com.omni.downloader.engine.ParsedContent
import com.omni.downloader.engine.DomesticMediaSource
import com.omni.downloader.engine.UrlSniffer
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import com.omni.downloader.ui.model.TaskListState
import com.omni.downloader.ui.model.buildTaskListState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omni.downloader.ui.model.hasSourceQualityChoices
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _analysisStage = MutableStateFlow("")
    val analysisStage: StateFlow<String> = _analysisStage.asStateFlow()

    private val repository = TaskRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    val inputDrafts = com.omni.downloader.data.repository.InputDraftRepository.getInstance(application)

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    val taskStructure: StateFlow<List<DownloadTask>> = repository.taskStructure
    internal val taskListState = repository.taskStructure
        .map(::buildTaskListState)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TaskListState())
    fun getTaskProgress(taskId: String) = repository.getTaskProgressFlow(taskId)

    val activeTasks: StateFlow<List<DownloadTask>> = repository.taskStructure
        .map { list ->
            list.filter {
                it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val proxyUrl: StateFlow<String> = settingsRepository.proxyUrl
    val requestedSessionPlatform = MutableStateFlow<String?>(null)
    val imageInput = MutableStateFlow("")
    val imagePost = MutableStateFlow<com.omni.downloader.data.model.ImagePost?>(null)
    val imageBusy = MutableStateFlow(false)
    val imageError = MutableStateFlow("")
    val imageBrowserUrl = MutableStateFlow("")
    private var analysisJob: Job? = null
    private var analysisGeneration = 0L
    private var analysisProcessId: String? = null
    private var activeAnalysisInput = ""
    private var activeAnalysisOrigin = MediaPage.VIDEO
    private val _mediaNavigation = MutableStateFlow<MediaNavigationRequest?>(null)
    val mediaNavigation = _mediaNavigation.asStateFlow()

    fun consumeMediaNavigation(request: MediaNavigationRequest): Boolean {
        if (_mediaNavigation.value != request || request.generation != analysisGeneration) return false
        _mediaNavigation.value = null
        return true
    }

    fun updateImageInput(value: String) = updateInputUrl(value)

    fun pasteImageLink(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            val url = UrlSniffer.extractUrl(text)
            if (url.isNullOrBlank()) _message.value = "剪贴板中未发现有效链接"
            else updateImageInput(url)
        } catch (_: Exception) { _message.value = "无法读取剪贴板" }
    }

    fun analyzeImages() = startRoutedAnalysis(imageInput.value, MediaPage.IMAGES)

    private fun cancelMediaAnalysis() {
        analysisGeneration++
        _analysisStage.value = ""
        analysisJob?.cancel()
        analysisJob = null
        analysisProcessId?.let(DownloadEngine::cancelMetadata)
        analysisProcessId = null
        _isAnalyzing.value = false
        imageBusy.value = false
        _mediaNavigation.value = null
    }

    private fun startRoutedAnalysis(input: String, origin: MediaPage, autoDownload: Boolean = false, context: Context? = null) {
        val target = (UrlSniffer.extractUrl(input) ?: input).trim()
        if (target.isBlank()) { _message.value = "请输入或粘贴作品链接"; return }
        if (analysisJob?.isActive == true && activeAnalysisInput == target && activeAnalysisOrigin == origin) return
        cancelMediaAnalysis()
        val generation = analysisGeneration
        val processId = "metadata-${UUID.randomUUID()}"
        analysisProcessId = processId
        activeAnalysisInput = target
        activeAnalysisOrigin = origin
        var destination = origin
        var resolvedUrl = target
        _metadata.value = null; imagePost.value = null
        _showFormatSheet.value = false; _errorDialogDetail.value = null
        imageError.value = ""; imageBrowserUrl.value = ""
        fun showDestination(page: MediaPage) {
            if (generation != analysisGeneration) return
            _isAnalyzing.value = page == MediaPage.VIDEO
            imageBusy.value = page == MediaPage.IMAGES
            _inputUrl.value = resolvedUrl; imageInput.value = resolvedUrl
            if (page != destination) _mediaNavigation.value = MediaNavigationRequest(generation, page)
            destination = page
        }
        showDestination(origin)
        analysisJob = launchAnalysis launch@{
            try {
                resolvedUrl = UrlSniffer.sanitizeAndResolveUrl(target)
                currentCoroutineContext().ensureActive()
                // Browser assistance must receive the resolved target even when
                // metadata parsing fails (short shares do not contain an ID).
                showDestination(destination)
                val site = com.omni.downloader.engine.LinkNormalizer.platformOf(resolvedUrl)
                val proxy = proxyUrl.value
                val douyinScope = if (site == com.omni.downloader.engine.LinkNormalizer.PLATFORM_DOUYIN)
                    com.omni.downloader.engine.DouyinAutoResolver.scope(getApplication(), proxy) else null
                if (site == com.omni.downloader.engine.LinkNormalizer.PLATFORM_DOUYIN) imageBrowserUrl.value = resolvedUrl
                val router = MediaAnalysisRouter(
                    video = { url: String -> DownloadEngine.fetchVideoMetadata(getApplication(), url, proxy, processId,
                        onPhase = { if (generation == analysisGeneration) _analysisStage.value = it }).getOrThrow() },
                    images = { url: String ->
                        val imageUrl = MediaAnalysisRouter.imageInput(target, url)
                        val json = com.omni.downloader.engine.SharedPython.run(getApplication(), "images", imageUrl, site, proxy, taskId = processId)
                        val post = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .decodeFromString<com.omni.downloader.data.model.ImagePost>(json.toString())
                        check(post.resources.isNotEmpty()) { "[NO_MEDIA] 该作品没有可确认的图片资源" }
                        currentCoroutineContext().ensureActive()
                        com.omni.downloader.engine.ImagePostSnapshots.put(getApplication(), json)
                        post
                    }
                )
                val content = router.analyze(resolvedUrl, origin, ::showDestination)
                currentCoroutineContext().ensureActive()
                if (douyinScope != null && douyinScope != com.omni.downloader.engine.DouyinAutoResolver.scope(getApplication(), proxy))
                    throw CancellationException("解析会话已改变")
                if (generation != analysisGeneration) return@launch
                when (content) {
                    is ParsedContent.Video -> {
                        showDestination(MediaPage.VIDEO)
                        applyVideoMetadata(content.metadata, target, autoDownload && origin == MediaPage.VIDEO, context)
                    }
                    is ParsedContent.Images -> { showDestination(MediaPage.IMAGES); imagePost.value = content.post }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation != analysisGeneration) return@launch
                val detail = com.omni.downloader.engine.LogSanitizer.sanitize(error.message.orEmpty())
                if (destination == MediaPage.IMAGES) imageError.value = detail
                else { _errorDialogDetail.value = detail; _message.value = "解析失败，点击弹窗查看详情" }
                if (com.omni.downloader.engine.MediaErrors.codeOf(error) in setOf(MediaErrorCode.AUTH_REQUIRED, MediaErrorCode.CHALLENGE_REQUIRED)) {
                    val platform = UrlSniffer.identifySite(resolvedUrl)
                    if (platform in SessionPlatforms.all) SessionRepository.getInstance(getApplication()).markStatus(platform, SessionStatus.NEEDS_AUTH)
                }
            } finally {
                if (generation == analysisGeneration) {
                    _isAnalyzing.value = false; imageBusy.value = false; analysisProcessId = null; _analysisStage.value = ""
                }
            }
        }
    }

    private fun launchAnalysis(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        val token = UUID.randomUUID().toString()
        val context = getApplication<Application>()
        val held = runCatching { DownloadService.analysisLease(context, token, true) }.isSuccess
        return viewModelScope.launch(block = block).also { job -> job.invokeOnCompletion {
            if (held) runCatching { DownloadService.analysisLease(context, token, false) }
        } }
    }

    private val batchOwner = lazy { com.omni.downloader.engine.BatchDownloads(getApplication(), viewModelScope) }
    val batchDownloads get() = batchOwner.value

    override fun onCleared() { if (batchOwner.isInitialized()) batchOwner.value.cancel(); cancelMediaAnalysis(); super.onCleared() }
    fun acceptBrowserVideo(item: String, url: String) {
        com.omni.downloader.engine.DouyinAutoResolver.invalidate(url)
        cancelMediaAnalysis()
        val generation = analysisGeneration
        _inputUrl.value = url
        _metadata.value = null; _showFormatSheet.value = false; _errorDialogDetail.value = null
        _isAnalyzing.value = true
        analysisJob = launchAnalysis launch@{
            try {
                val parsed = withContext(Dispatchers.Default) {
                    com.omni.downloader.engine.DouyinVideoParser.parseItem(item, url)
                }
                val metadata = withContext(Dispatchers.IO) {
                    com.omni.downloader.engine.DomesticDirectExtractor.confirmDouyinMetadata(parsed, verifyHighest = true, budgetMillis = 12_000)
                }
                currentCoroutineContext().ensureActive()
                if (generation == analysisGeneration) applyVideoMetadata(metadata, url, false, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == analysisGeneration) _errorDialogDetail.value = com.omni.downloader.engine.LogSanitizer.sanitize(error.message.orEmpty())
            } finally { if (generation == analysisGeneration) _isAnalyzing.value = false }
        }
    }

    fun acceptBrowserImages(item: String, url: String) {
        if (imageBusy.value) return
        cancelMediaAnalysis()
        val generation = analysisGeneration
        val processId = "metadata-${UUID.randomUUID()}"
        analysisProcessId = processId
        imageBusy.value = true
        analysisJob = launchAnalysis launch@{
            var file: File? = null
            try {
                file = File.createTempFile("browser-image-item-", ".json", getApplication<android.app.Application>().cacheDir)
                file.writeText(item)
                val json = com.omni.downloader.engine.SharedPython.run(getApplication(), "images-state", url, "抖音", proxyUrl.value,
                    taskId = processId, extra = listOf("--state-file", file.path))
                currentCoroutineContext().ensureActive()
                if (generation != analysisGeneration) return@launch
                com.omni.downloader.engine.ImagePostSnapshots.put(getApplication(), json)
                imagePost.value = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<com.omni.downloader.data.model.ImagePost>(json.toString())
                imageError.value = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == analysisGeneration) imageError.value = com.omni.downloader.engine.LogSanitizer.sanitize(e.message.orEmpty()) }
            finally {
                file?.delete()
                if (generation == analysisGeneration) { imageBusy.value = false; analysisProcessId = null }
            }
        }
    }
    fun downloadImages(ids: Set<String>, archive: Boolean) {
        val post = imagePost.value ?: return
        val selected = post.resources.filter { it.id in ids }
        if (selected.isEmpty()) return
        val group = UUID.randomUUID().toString()
        val batches = if (archive) listOf(selected.map { it.id }) else selected.map { listOf(it.id) }
        val jobs = batches.mapIndexed { index, batch -> DownloadTask(
            id = UUID.randomUUID().toString(), url = post.url, title = "${post.title.take(70)} (${if (archive) "原图归档" else "${index + 1}/${selected.size}"})",
            author = post.author, thumbnailUrl = selected.firstOrNull { it.id == batch.first() }?.url.orEmpty(),
            downloadType = if (archive) DownloadType.IMAGE_ZIP else DownloadType.IMAGE,
            imagePlatform = post.platform, selectedImageIds = batch, mediaId = post.contentId, schemaVersion = 4,
            collectionId = if (batches.size > 1) group else null, collectionTitle = post.title,
            episodeIndex = index + 1, episodeTotal = batches.size
        ) }
        repository.addTasks(jobs)
        jobs.forEach { DownloadService.startDownload(getApplication(), it.id) }
    }
    val downloadPath: StateFlow<String> = settingsRepository.downloadPath
    val appLanguage: StateFlow<String> = settingsRepository.appLanguage
    val themeMode: StateFlow<String> = settingsRepository.themeMode

    fun setThemeMode(mode: String) = settingsRepository.setThemeMode(mode)
    val bilibiliCookie: StateFlow<String> = settingsRepository.bilibiliCookie
    val hasPromptedBilibiliLogin: StateFlow<Boolean> = settingsRepository.hasPromptedBilibiliLogin
    val hasShownRelayCompatTip: StateFlow<Boolean> = settingsRepository.hasShownRelayCompatTip
    val relaySites: StateFlow<List<RelaySite>> = settingsRepository.relaySites

    fun addRelaySite(site: RelaySite) {
        settingsRepository.addRelaySite(site)
    }

    fun removeRelaySite(siteId: String) {
        settingsRepository.removeRelaySite(siteId)
    }

    fun markRelayCompatTipShown() {
        settingsRepository.setHasShownRelayCompatTip(true)
    }

    fun markBilibiliLoginPrompted() {
        settingsRepository.setHasPromptedBilibiliLogin(true)
    }

    fun setAppLanguage(language: String) {
        settingsRepository.setAppLanguage(language)
    }

    fun updateBilibiliCookie(cookie: String) {
        settingsRepository.setBilibiliCookie(cookie)
    }

    fun clearBilibiliCookie() {
        settingsRepository.clearBilibiliCookie()
    }

    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _metadata = MutableStateFlow<VideoMetadata?>(null)
    val metadata: StateFlow<VideoMetadata?> = _metadata.asStateFlow()

    private val _selectedMultiMediaIndex = MutableStateFlow(0)
    val selectedMultiMediaIndex: StateFlow<Int> = _selectedMultiMediaIndex.asStateFlow()

    private val _showFormatSheet = MutableStateFlow(false)
    val showFormatSheet: StateFlow<Boolean> = _showFormatSheet.asStateFlow()

    private val _errorDialogDetail = MutableStateFlow<String?>(null)
    val errorDialogDetail: StateFlow<String?> = _errorDialogDetail.asStateFlow()

    private val _selectedDownloadType = MutableStateFlow(DownloadType.VIDEO_WITH_AUDIO)
    val selectedDownloadType: StateFlow<DownloadType> = _selectedDownloadType.asStateFlow()

    private val _qualityPolicy = MutableStateFlow<QualitySelection?>(QualitySelection())
    val qualityPolicy = _qualityPolicy.asStateFlow()
    fun selectQualityPolicy(policy: QualitySelection?) {
        _qualityPolicy.value = policy
        val meta = _metadata.value ?: return
        val active = meta.multiMediaList.getOrElse(_selectedMultiMediaIndex.value) { meta }
        if (policy != null && active.hasSourceQualityChoices()) {
            runCatching { com.omni.downloader.engine.QualitySelector.select(active.availableVideoFormats, policy) }
                .onSuccess { _selectedVideoFormat.value = it }.onFailure { _message.value = it.message }
        }
    }

    private val _selectedVideoFormat = MutableStateFlow<FormatOption?>(null)
    val selectedVideoFormat: StateFlow<FormatOption?> = _selectedVideoFormat.asStateFlow()

    private val _selectedAudioFormat = MutableStateFlow(AudioFormat.MP3)
    val selectedAudioFormat: StateFlow<AudioFormat> = _selectedAudioFormat.asStateFlow()

    private val _isUpdatingEngine = MutableStateFlow(false)
    val isUpdatingEngine: StateFlow<Boolean> = _isUpdatingEngine.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            inputDrafts.awaitLoaded()
            _inputUrl.value = inputDrafts.draft.value.home.ifBlank { inputDrafts.draft.value.images }
            imageInput.value = _inputUrl.value
            launch { _inputUrl.collect { text -> inputDrafts.update { it.copy(home = text) } } }
            launch { imageInput.collect { text -> inputDrafts.update { it.copy(images = text) } } }
        }
    }

    fun updateInputUrl(url: String) {
        if (_inputUrl.value != url) {
            cancelMediaAnalysis()
            _metadata.value = null; _showFormatSheet.value = false; _errorDialogDetail.value = null
            imagePost.value = null; imageError.value = ""; imageBrowserUrl.value = ""
        }
        imageInput.value = url
        _inputUrl.value = url
        inputDrafts.update { it.copy(home = url) }
    }

    fun updateProxyUrl(url: String) {
        settingsRepository.setProxyUrl(url)
    }

    fun setDownloadPath(path: String) {
        settingsRepository.setDownloadPath(path)
    }

    fun resetDownloadPath() {
        settingsRepository.resetDownloadPath()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun dismissErrorDialog() {
        _errorDialogDetail.value = null
    }

    fun dismissFormatSheet() {
        _showFormatSheet.value = false
    }

    fun selectMultiMediaIndex(index: Int) {
        val meta = _metadata.value ?: return
        if (index !in meta.multiMediaList.indices) return
        _selectedMultiMediaIndex.value = index
        val sub = meta.multiMediaList[index]
        _selectedVideoFormat.value = sub.availableVideoFormats.firstOrNull()
        if (sub.isGif) {
            _selectedDownloadType.value = DownloadType.GIF
        } else {
            _selectedDownloadType.value = DownloadType.VIDEO_WITH_AUDIO
        }
    }

    fun selectDownloadType(type: DownloadType) {
        _selectedDownloadType.value = type
    }

    fun selectVideoFormat(format: FormatOption) {
        _qualityPolicy.value = null
        _selectedVideoFormat.value = format
    }

    fun selectAudioFormat(format: AudioFormat) {
        _selectedAudioFormat.value = format
    }

    fun checkClipboardAndPaste(context: Context, autoDownload: Boolean = false) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val item = clipboard?.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            val extracted = UrlSniffer.extractUrl(text)
            if (!extracted.isNullOrEmpty()) {
                updateInputUrl(extracted)
                if (autoDownload) {
                    _message.value = "已读取剪贴板，正在解析..."
                    startAnalyze(rawUrl = extracted, autoDownload = true, context = context)
                } else {
                    _message.value = "已从剪贴板粘贴视频链接"
                }
            } else {
                _message.value = "剪贴板中未发现有效链接"
            }
        } catch (e: Exception) {
            _message.value = "无法读取剪贴板"
        }
    }

    fun startAnalyze(rawUrl: String? = null, autoDownload: Boolean = false, context: Context? = null) {
        val targetUrl = (rawUrl ?: _inputUrl.value).trim()
        val extracted = UrlSniffer.extractUrl(targetUrl) ?: targetUrl
        if (extracted.isBlank()) {
            _message.value = "请输入或粘贴视频网址"
            return
        }

        // 前置防呆：防止误将中转解析站本身网址当成视频源解析
        val isRelaySiteHome = settingsRepository.relaySites.value.any { site ->
            val siteHost = try { android.net.Uri.parse(site.url).host } catch (_: Exception) { null }
            val inputHost = try { android.net.Uri.parse(extracted).host } catch (_: Exception) { null }
            inputHost != null && siteHost != null && inputHost.equals(siteHost, ignoreCase = true)
        } || extracted.contains("greenvideo.cc", ignoreCase = true) ||
             extracted.contains("x2twitter.com", ignoreCase = true) ||
             extracted.contains("snapany.com", ignoreCase = true)

        if (isRelaySiteHome && !UrlSniffer.isDirectMediaUrl(extracted)) {
            _message.value = "提示：此为中转网站地址，请粘贴具体视频链接"
            _errorDialogDetail.value = "检测到您输入的是中转解析站地址 ($extracted)。\n\n请在输入框粘贴欲下载的真实视频链接（如 Bilibili、YouTube、TikTok、X 等平台的分享链接），或在下方中转站卡片内输入视频链接进行解析。"
            return
        }

        startRoutedAnalysis(extracted, MediaPage.VIDEO, autoDownload, context)
    }

    private fun applyVideoMetadata(data: VideoMetadata, extracted: String, autoDownload: Boolean, context: Context?) {
                _metadata.value = data
                _selectedMultiMediaIndex.value = com.omni.downloader.engine.MediaSelection.initialIndex(
                    extracted, data.url, data.multiMediaList.map { it.url }
                )
                val activeMeta = data.multiMediaList.getOrElse(_selectedMultiMediaIndex.value) { data }
                _selectedVideoFormat.value = activeMeta.availableVideoFormats.firstOrNull()
                // 默认下载类型始终锁定为原画视频 (VIDEO_WITH_AUDIO)，
                // 彻底杜绝普通视频或短动效在粘贴自动下载时被私自执行 GIF 转码
                _selectedDownloadType.value = if (activeMeta.isGif) DownloadType.VIDEO_ONLY else DownloadType.VIDEO_WITH_AUDIO
                _selectedAudioFormat.value = AudioFormat.MP3

                if (autoDownload && context != null) {
                    if (data.multiMediaList.size > 1) {
                        // 发现链接是合集/多视频时，不直接下载，而是先弹出下载选项由用户自己决定如何下载
                        _message.value = "检测到合集内容(共${data.multiMediaList.size}个视频)，请选择下载选项"
                        _showFormatSheet.value = true
                    } else {
                        startDownload(context)
                    }
                } else {
                    _showFormatSheet.value = true
                }
    }

    /**
     * 创建任务并启动后台服务下载当前选中的视频
     */
    fun startDownload(context: Context, saveCover: Boolean = false) {
        val meta = _metadata.value ?: return
        val currentMeta = if (meta.multiMediaList.isNotEmpty()) {
            meta.multiMediaList.getOrElse(_selectedMultiMediaIndex.value) { meta }
        } else meta

        val type = _selectedDownloadType.value
        val format = if (_qualityPolicy.value != null && currentMeta.hasSourceQualityChoices()) {
            runCatching { com.omni.downloader.engine.QualitySelector.select(currentMeta.availableVideoFormats, _qualityPolicy.value!!) }
                .getOrElse { _message.value = it.message; return }
        } else _selectedVideoFormat.value
        val audioFormat = _selectedAudioFormat.value
        val resolutionLabel = when (type) {
            DownloadType.COVER -> "原图封面"
            DownloadType.AUDIO_ONLY -> audioFormat.ext.uppercase()
            DownloadType.GIF -> "GIF"
            else -> format?.resolutionLabel ?: "默认画质"
        }

        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            url = currentMeta.url,
            title = currentMeta.title,
            author = currentMeta.author,
            thumbnailUrl = currentMeta.thumbnailUrl,
            downloadType = type,
            selectedResolution = resolutionLabel,
            audioFormat = audioFormat,
            schemaVersion = 3,
            qualitySelection = qualityFor(type, format, audioFormat),
            selectedVideoFormatId = exactFormatId(format),
            selectedVideoIsMuxed = format?.let { !it.isVideoOnly && !it.isAudioOnly } ?: false,
            mediaId = currentMeta.mediaId,
            expectedDurationMs = format?.sourceDurationMs?.takeIf { it > 0 } ?: currentMeta.expectedDurationMs,
            selectedWidth = format?.width ?: 0,
            selectedHeight = format?.height ?: 0,
            requireAudio = (format?.acodec?.let { it.isNotBlank() && it != "none" } ?: false) || currentMeta.availableAudioFormats.isNotEmpty(),
            directMediaUrl = if (currentMeta.siteName == "X (Twitter)" && format?.isVideoOnly == false) format.sourceUrl else DomesticMediaSource.url(currentMeta, format),
            directMediaHeaders = DomesticMediaSource.headers(currentMeta.siteName) + format?.sourceHeaders.orEmpty()
        )

        repository.addTask(task)
        DownloadService.startDownload(context, task.id)

        if (saveCover && currentMeta.thumbnailUrl.isNotBlank()) {
            val coverTask = DownloadTask(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                url = currentMeta.url,
                title = "${currentMeta.title} (封面)",
                author = currentMeta.author,
                thumbnailUrl = currentMeta.thumbnailUrl,
                downloadType = DownloadType.COVER,
                selectedResolution = "原图封面",
                schemaVersion = 3,
                audioFormat = AudioFormat.MP3
            )
            repository.addTask(coverTask)
            DownloadService.startDownload(context, coverTask.id)
        }

        _showFormatSheet.value = false
        _message.value = if (saveCover) "已加入下载队列 (含封面): ${task.title}" else "已加入下载队列: ${task.title}"
    }

    /**
     * 将从第三方中转网页捕获到的视频下载直链直接加入后台下载队列
     */
    fun startDirectDownload(
        context: Context,
        directUrl: String,
        title: String = "中转下载视频",
        author: String = "第三方中转",
        thumbnailUrl: String = ""
    ) {
        val (cleanUrl, unpackedTitle) = UrlSniffer.unpackDirectMediaUrl(directUrl, title)
        val candidateTitle = if (unpackedTitle.isNotBlank() && unpackedTitle != "中转下载视频" && unpackedTitle != "视频") unpackedTitle else title
        val safeTitle = candidateTitle
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "中转下载视频" }
            .take(60)

        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            url = cleanUrl,
            title = safeTitle,
            author = author,
            thumbnailUrl = thumbnailUrl,
            downloadType = DownloadType.VIDEO_WITH_AUDIO,
            selectedResolution = "中转直链",
            schemaVersion = 3,
            audioFormat = AudioFormat.MP3
        )
        repository.addTask(task)
        DownloadService.startDownload(context, task.id)
        _message.value = "已从备用站捕获并加入下载队列: $safeTitle"
    }

    /** 把 UI 上选中的格式转换为持久化的质量意图。 */
    private fun qualityFor(
        type: DownloadType,
        format: FormatOption?,
        audioFormat: AudioFormat
    ): QualitySelection {
        if (type != DownloadType.AUDIO_ONLY && type != DownloadType.COVER) _qualityPolicy.value?.let { return it }
        val height = format?.let { if (it.width > 0 && it.height > 0) minOf(it.width, it.height) else it.height }?.takeIf { it > 0 } ?: 0
        return when {
            type == DownloadType.AUDIO_ONLY -> QualitySelection(
                mode = QualityMode.BEST_AVAILABLE,
                container = audioFormat.ext
            )
            type == DownloadType.COVER || format == null -> QualitySelection()
            format.isSynthetic ||
                format.formatId.lowercase() in setOf("best", "gif") -> QualitySelection(
                mode = if (height > 0) QualityMode.MAX_HEIGHT else QualityMode.BEST_AVAILABLE,
                maxHeight = height
            )
            else -> QualitySelection(
                mode = QualityMode.EXACT_FORMAT,
                maxHeight = height,
                allowLowerQuality = false
            )
        }
    }

    /** 只有 yt-dlp 真实格式 ID 才能用于 -f；UI 兜底项与 B 站自定义 ID 一律忽略。 */
    private fun exactFormatId(format: FormatOption?): String {
        if (format == null || format.isSynthetic) return ""
        val id = format.formatId.trim()
        if (id.isBlank()) return ""
        if (id.lowercase() in setOf("best", "gif", "1080", "720", "480", "360")) return ""
        return id
    }

    /**
     * 一键直接下载当前选中的视频封面至系统相册
     */
    fun downloadCoverDirectly(context: Context) {
        val meta = _metadata.value ?: return
        val currentMeta = if (meta.multiMediaList.isNotEmpty()) {
            meta.multiMediaList.getOrElse(_selectedMultiMediaIndex.value) { meta }
        } else meta

        if (currentMeta.thumbnailUrl.isBlank()) {
            _message.value = "未发现封面图地址"
            return
        }

        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            url = currentMeta.url,
            title = currentMeta.title,
            author = currentMeta.author,
            thumbnailUrl = currentMeta.thumbnailUrl,
            downloadType = DownloadType.COVER,
            selectedResolution = "原图封面",
            schemaVersion = 3,
            audioFormat = AudioFormat.MP3
        )

        repository.addTask(task)
        DownloadService.startDownload(context, task.id)

        _showFormatSheet.value = false
        _message.value = "已开始下载封面: ${task.title}"
    }

    /**
     * 批量下载用户勾选的合集/多视频列表
     * @param selectedIndices 选中的分集索引集合，若为空则默认下载全部
     */
    fun startDownloadSelectedMultiMedia(context: Context, selectedIndices: Set<Int>, saveCover: Boolean = false) {
        val meta = _metadata.value ?: return
        val list = meta.multiMediaList
        if (list.isEmpty()) {
            startDownload(context, saveCover)
            return
        }

        val targetList = if (selectedIndices.isNotEmpty()) {
            selectedIndices.sorted().mapNotNull { list.getOrNull(it) }
        } else {
            list
        }

        if (targetList.isEmpty()) {
            _message.value = "请先勾选需要下载的视频"
            return
        }

        val type = _selectedDownloadType.value
        val format = _selectedVideoFormat.value
        val audioFormat = _selectedAudioFormat.value

        val resolutionLabel = when (type) {
            DownloadType.COVER -> "原图封面"
            DownloadType.AUDIO_ONLY -> audioFormat.ext.uppercase()
            DownloadType.GIF -> "GIF"
            else -> format?.resolutionLabel ?: "最佳画质"
        }

        val isCollection = targetList.size > 1
        val collectionId = if (isCollection) "col_" + UUID.randomUUID().toString().replace("-", "").take(12) else null
        val collectionTitle = meta.title.ifBlank { "合集视频" }
        val totalEpisodes = targetList.size

        val tasksToAdd = mutableListOf<DownloadTask>()

        targetList.forEachIndexed { index, sub ->
            val finalType = if (type == DownloadType.COVER) {
                DownloadType.COVER
            } else if (sub.isGif) {
                DownloadType.GIF
            } else {
                type
            }

            // 每个分集独立校验格式：根列表的 format ID 不一定存在于该分集中
            val policy = _qualityPolicy.value ?: QualitySelection(mode = QualityMode.MAX_HEIGHT,
                maxHeight = format?.let { minOf(it.width, it.height) } ?: 0)
            val subFormat = if (sub.hasSourceQualityChoices()) {
                runCatching { com.omni.downloader.engine.QualitySelector.select(sub.availableVideoFormats, policy) }.getOrNull()
            } else sub.availableVideoFormats.firstOrNull()
            val task = DownloadTask(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                url = sub.url,
                title = sub.title,
                author = sub.author.ifBlank { meta.author },
                thumbnailUrl = sub.thumbnailUrl.ifBlank { meta.thumbnailUrl },
                downloadType = finalType,
                selectedResolution = resolutionLabel,
                audioFormat = audioFormat,
                schemaVersion = 3,
                qualitySelection = policy,
                selectedVideoFormatId = if (sub.siteName == "哔哩哔哩") "" else exactFormatId(subFormat),
                mediaId = sub.mediaId, expectedDurationMs = subFormat?.sourceDurationMs?.takeIf { it > 0 } ?: sub.expectedDurationMs,
                selectedWidth = if (sub.siteName == "哔哩哔哩") 0 else subFormat?.width ?: 0,
                selectedHeight = if (sub.siteName == "哔哩哔哩") 0 else subFormat?.height ?: 0,
                requireAudio = sub.availableAudioFormats.isNotEmpty(),
                selectedVideoIsMuxed = subFormat?.let { !it.isVideoOnly && !it.isAudioOnly } ?: false,
                collectionId = collectionId,
                collectionTitle = if (isCollection) collectionTitle else null,
                episodeIndex = if (isCollection) index + 1 else 0,
                episodeTotal = if (isCollection) totalEpisodes else 0,
                directMediaUrl = if (sub.siteName == "X (Twitter)" && subFormat?.isVideoOnly == false) subFormat.sourceUrl else DomesticMediaSource.url(sub, subFormat),
                directMediaHeaders = DomesticMediaSource.headers(sub.siteName)
            )
            tasksToAdd.add(task)

            if (saveCover && sub.thumbnailUrl.isNotBlank()) {
                val coverTask = DownloadTask(
                    id = UUID.randomUUID().toString().replace("-", "").take(12),
                    url = sub.url,
                    title = "${sub.title} (封面)",
                    author = sub.author.ifBlank { meta.author },
                    thumbnailUrl = sub.thumbnailUrl,
                    downloadType = DownloadType.COVER,
                    selectedResolution = "原图封面",
                    audioFormat = AudioFormat.MP3
                )
                tasksToAdd.add(coverTask)
            }
        }

        repository.addTasks(tasksToAdd)
        tasksToAdd.forEach { task ->
            DownloadService.startDownload(context, task.id)
        }

        _showFormatSheet.value = false
        _message.value = if (isCollection) {
            "已加入合集下载队列 (共 ${targetList.size} 集)"
        } else {
            "已开始下载: ${targetList[0].title}"
        }
    }

    /**
     * 一键将多视频推文中的所有独立视频加入下载队列
     */
    fun startDownloadAllMultiMedia(context: Context) {
        val meta = _metadata.value ?: return
        startDownloadSelectedMultiMedia(context, meta.multiMediaList.indices.toSet())
    }

    fun retryTask(context: Context, taskId: String) {
        val task = repository.getTask(taskId) ?: return
        if (task.status !in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.WAITING_FOR_AUTH)) return
        repository.updateTaskProgress(taskId, 0f, "", "", TaskStatus.PENDING)
        DownloadService.startDownload(context, taskId)
    }

    fun cancelTask(context: Context, taskId: String) {
        DownloadService.cancelDownload(context, taskId)
        _message.value = "任务已取消"
    }

    fun deleteTask(taskId: String, deleteFile: Boolean) {
        repository.removeTask(taskId, deleteFile)
        _message.value = if (deleteFile) "已删除记录与本地文件" else "已从列表移除"
    }

    /**
     * 取消合集下的所有正在下载或等待中的分集
     */
    fun cancelCollection(context: Context, collectionId: String) {
        val allTasks = repository.tasks.value.filter { it.collectionId == collectionId }
        allTasks.forEach { task ->
            if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PROCESSING || task.status == TaskStatus.PENDING) {
                DownloadService.cancelDownload(context, task.id)
            }
        }
        _message.value = "合集下载已取消"
    }

    /**
     * 删除整个合集记录及可选本地文件
     */
    fun deleteCollection(collectionId: String, deleteFile: Boolean) {
        repository.clearTasks({ it.collectionId == collectionId }, deleteLocalFiles = deleteFile)
        _message.value = if (deleteFile) "已删除合集记录与本地文件" else "已从列表移除合集"
    }

    /**
     * 重试合集中未完成（失败或取消）的分集
     */
    fun retryCollection(context: Context, collectionId: String) {
        val allTasks = repository.tasks.value.filter { it.collectionId == collectionId }
        val toRetry = allTasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.WAITING_FOR_AUTH || it.status == TaskStatus.CANCELLED }
        toRetry.forEach { task ->
            repository.updateTaskProgress(task.id, 0f, "", "", TaskStatus.PENDING)
            DownloadService.startDownload(context, task.id)
        }
        _message.value = "已重新开始下载合集中的未完成项 (${toRetry.size} 集)"
    }

    /**
     * 智能批量清除历史记录
     * @param pageIndex 0: 全部(清除已完成/失败/取消), 1: 正在下载(清除残留失败/取消), 2: 已完成(清除已完成)
     */
    fun clearFinishedTasks(pageIndex: Int = 0) {
        val predicate: (com.omni.downloader.data.model.DownloadTask) -> Boolean = when (pageIndex) {
            2 -> { task -> task.status == TaskStatus.COMPLETED }
            1 -> { task -> task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED }
            else -> { task ->
                task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED
            }
        }
        val count = repository.clearTasks(predicate, deleteLocalFiles = false)
        _message.value = if (count > 0) "已清除 $count 条任务记录" else "暂无需要清除的历史记录"
    }

    /**
     * 终止所有未完成任务并彻底删除本地临时残留文件
     */
    fun cancelAndCleanAllActiveTasks(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val allTasks = repository.tasks.value
            val unfinished = allTasks.filter {
                it.status == TaskStatus.DOWNLOADING ||
                it.status == TaskStatus.PROCESSING ||
                it.status == TaskStatus.PENDING ||
                it.status == TaskStatus.FAILED ||
                it.status == TaskStatus.CANCELLED
            }
            if (unfinished.isEmpty()) return@launch

            // 1. 终止正在执行的底层进程并清空下载服务队列
            DownloadService.cancelAllDownloads(context)
            unfinished.forEach { task ->
                DownloadEngine.cancelTask(task.id)
            }

            // 2. 清理各任务关联的本地残留文件
            unfinished.forEach { task ->
                if (task.localFilePath.isNotBlank()) {
                    try {
                        if (task.localFilePath.startsWith("content://")) getApplication<android.app.Application>().contentResolver.delete(android.net.Uri.parse(task.localFilePath), null, null) else { val f = File(task.localFilePath); if (f.exists()) f.delete() }
                    } catch (ignored: Exception) {}
                }
            }

            // 3. 清理缓存目录中的 staging 临时目录
            try {
                val stagingDir = File(context.cacheDir, "staging")
                if (stagingDir.exists() && stagingDir.isDirectory) {
                    stagingDir.listFiles()?.forEach { file ->
                        try { file.delete() } catch (ignored: Exception) {}
                    }
                }
            } catch (ignored: Exception) {}

            // 4. 从数据仓库彻底批量清除记录
            val unfinishedIds = unfinished.map { it.id }.toSet()
            repository.clearTasks({ task -> task.id in unfinishedIds }, deleteLocalFiles = true)

            _message.value = "已终止 ${unfinished.size} 个任务并清理残留文件"
        }
    }

    fun updateEngine(context: Context) {
        viewModelScope.launch {
            _isUpdatingEngine.value = true
            val res = DownloadEngine.updateEngine(context)
            _isUpdatingEngine.value = false
            res.onSuccess { msg ->
                _message.value = "核心引擎更新成功: $msg"
            }.onFailure { err ->
                _errorDialogDetail.value = "更新引擎失败:\n${err.message}"
            }
        }
    }
}
