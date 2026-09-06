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
import com.omni.downloader.data.model.VideoMetadata
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.engine.UrlSniffer
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TaskRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    val proxyUrl: StateFlow<String> = settingsRepository.proxyUrl
    val downloadPath: StateFlow<String> = settingsRepository.downloadPath
    val appLanguage: StateFlow<String> = settingsRepository.appLanguage
    val bilibiliCookie: StateFlow<String> = settingsRepository.bilibiliCookie

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
                if (activeMeta.isGif) {
                    _selectedDownloadType.value = DownloadType.GIF
                } else {
                    _selectedDownloadType.value = DownloadType.VIDEO_WITH_AUDIO
                }
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

        targetList.forEach { sub ->
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
                author = sub.author,
                thumbnailUrl = sub.thumbnailUrl,
                downloadType = finalType,
                selectedResolution = resolutionLabel,
                audioFormat = audioFormat
            )

            repository.addTask(task)
            DownloadService.startDownload(context, task.id)

            if (saveCover && sub.thumbnailUrl.isNotBlank()) {
                val coverTask = DownloadTask(
                    id = UUID.randomUUID().toString().replace("-", "").take(12),
                    url = sub.url,
                    title = "${sub.title} (封面)",
                    author = sub.author,
                    thumbnailUrl = sub.thumbnailUrl,
                    downloadType = DownloadType.COVER,
                    selectedResolution = "原图封面",
                    audioFormat = AudioFormat.MP3
                )
                repository.addTask(coverTask)
                DownloadService.startDownload(context, coverTask.id)
            }
        }

        _showFormatSheet.value = false
        _message.value = if (saveCover) "已同时添加 ${targetList.size} 个视频及对应封面下载任务" else "已同时添加 ${targetList.size} 个视频独立下载任务"
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
