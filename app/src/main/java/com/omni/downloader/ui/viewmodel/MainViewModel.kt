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
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.engine.UrlSniffer
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TaskRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    val activeTasks: StateFlow<List<DownloadTask>> = repository.tasks
        .map { list ->
            list.filter {
                it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val proxyUrl: StateFlow<String> = settingsRepository.proxyUrl
    val downloadPath: StateFlow<String> = settingsRepository.downloadPath
    val appLanguage: StateFlow<String> = settingsRepository.appLanguage
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

    private val _selectedVideoFormat = MutableStateFlow<FormatOption?>(null)
    val selectedVideoFormat: StateFlow<FormatOption?> = _selectedVideoFormat.asStateFlow()

    private val _selectedAudioFormat = MutableStateFlow(AudioFormat.MP3)
    val selectedAudioFormat: StateFlow<AudioFormat> = _selectedAudioFormat.asStateFlow()

    private val _isUpdatingEngine = MutableStateFlow(false)
    val isUpdatingEngine: StateFlow<Boolean> = _isUpdatingEngine.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateInputUrl(url: String) {
        _inputUrl.value = url
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
                _inputUrl.value = extracted
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

        viewModelScope.launch {
            _isAnalyzing.value = true
            val proxy = settingsRepository.proxyUrl.value
            val result = DownloadEngine.fetchVideoMetadata(getApplication(), extracted, proxy)
            _isAnalyzing.value = false

            result.onSuccess { data ->
                _metadata.value = data
                _selectedMultiMediaIndex.value = 0
                val activeMeta = if (data.multiMediaList.isNotEmpty()) data.multiMediaList[0] else data
                _selectedVideoFormat.value = activeMeta.availableVideoFormats.firstOrNull()
                // 默认下载类型始终锁定为原画视频 (VIDEO_WITH_AUDIO)，
                // 彻底杜绝普通视频或短动效在粘贴自动下载时被私自执行 GIF 转码
                _selectedDownloadType.value = DownloadType.VIDEO_WITH_AUDIO
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
            }.onFailure { err ->
                val detail = err.message ?: "未知异常"
                _errorDialogDetail.value = detail
                _message.value = "解析失败，点击弹窗查看详情"
            }
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
        val format = _selectedVideoFormat.value
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
            audioFormat = audioFormat
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
            audioFormat = AudioFormat.MP3
        )
        repository.addTask(task)
        DownloadService.startDownload(context, task.id)
        _message.value = "已从备用站捕获并加入下载队列: $safeTitle"
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

            val task = DownloadTask(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                url = sub.url,
                title = sub.title,
                author = sub.author.ifBlank { meta.author },
                thumbnailUrl = sub.thumbnailUrl.ifBlank { meta.thumbnailUrl },
                downloadType = finalType,
                selectedResolution = resolutionLabel,
                audioFormat = audioFormat,
                collectionId = collectionId,
                collectionTitle = if (isCollection) collectionTitle else null,
                episodeIndex = if (isCollection) index + 1 else 0,
                episodeTotal = if (isCollection) totalEpisodes else 0
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
        val allTasks = repository.tasks.value.filter { it.collectionId == collectionId }
        allTasks.forEach { task ->
            repository.removeTask(task.id, deleteFile)
        }
        _message.value = if (deleteFile) "已删除合集记录与本地文件" else "已从列表移除合集"
    }

    /**
     * 重试合集中未完成（失败或取消）的分集
     */
    fun retryCollection(context: Context, collectionId: String) {
        val allTasks = repository.tasks.value.filter { it.collectionId == collectionId }
        val toRetry = allTasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
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
                        val f = File(task.localFilePath)
                        if (f.exists()) f.delete()
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
