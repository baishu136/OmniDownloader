package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class BatchDownloads(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = CompletableDeferred<Unit>()
    private var job: Job? = null
    private val processIds = ConcurrentHashMap.newKeySet<String>()
    private val admission = Mutex()
    private val reservedTaskIds = mutableSetOf<String>()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val queue = BatchQueue(::submit) { plan ->
        check(prefs.edit().putString("plan", SessionCrypto.encrypt(json.encodeToString(plan))).commit()) { "批次记录保存失败" }
    }
    init { scope.launch(Dispatchers.IO) {
        try { prefs.getString("plan", null)?.let { encoded ->
            val plain = SessionCrypto.decrypt(encoded) ?: error("无法读取批次记录")
            val repository = TaskRepository.getInstance(this@BatchDownloads.context)
            repository.awaitLoaded()
            queue.restore(json.decodeFromString<BatchPlan>(plain)) { repository.getTask(it) != null }
        } } catch (_: Exception) { _error.value = "上次批次记录无法读取，可以重新添加链接" }
        finally { loaded.complete(Unit) }
    } }
    fun start(text: String, options: BatchOptions, preservePending: Boolean = false) {
        if (job?.isActive == true) return
        val links = runCatching { BatchInput.links(text) }.getOrElse { _error.value = it.message; return }
        launch(BatchPlan(links.map { BatchItem(url = it) }, options), preservePending)
    }
    fun retry() { if (job?.isActive != true) launch(null) }
    fun cancel() {
        job?.cancel()
        processIds.toList().forEach(DownloadEngine::cancelMetadata)
    }
    fun clearHistory() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            loaded.await()
            try { queue.clearHistory(); _error.value = null }
            catch (error: Exception) { _error.value = LogSanitizer.sanitize(error.message.orEmpty()) }
        }
    }
    private fun launch(plan: BatchPlan?, preservePending: Boolean = false) {
        _error.value = null
        val token = UUID.randomUUID().toString()
        val held = runCatching { DownloadService.analysisLease(context, token, true) }.isSuccess
        job = scope.launch(Dispatchers.IO) {
            loaded.await()
            try {
                val next = if (plan != null && preservePending) BatchInput.preservePending(queue.plan.value, plan) else plan
                admission.withLock {
                    reservedTaskIds.clear()
                    reservedTaskIds.addAll((next ?: queue.plan.value).items.flatMap { it.taskIds })
                }
                queue.run(next)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = LogSanitizer.sanitize(error.message.orEmpty()) }
        }.also { it.invokeOnCompletion { if (held) runCatching { DownloadService.analysisLease(context, token, false) } } }
    }
    private suspend fun submit(item: BatchItem, options: BatchOptions): List<String> {
        val repository = TaskRepository.getInstance(context)
        repository.awaitLoaded()
        val pid = "batch-parse-${UUID.randomUUID()}"; processIds.add(pid)
        try {
            currentCoroutineContext().ensureActive()
            val proxy = SettingsRepository.getInstance(context).proxyUrl.value
            val url = when (val resolved = UrlSniffer.resolveContent(item.url)) {
                is LinkNormalizer.RedirectResult.Content -> resolved.url
                is LinkNormalizer.RedirectResult.LoginRequired -> error("[AUTH_REQUIRED] 请登录后单独解析此链接")
                is LinkNormalizer.RedirectResult.ChallengeRequired -> error("[CHALLENGE_REQUIRED] 请完成验证后重试")
                is LinkNormalizer.RedirectResult.Unavailable -> error("链接暂时不可访问")
            }
            val router = MediaAnalysisRouter(
                video = { link: String -> DownloadEngine.fetchVideoMetadata(context, link, proxy, pid).getOrThrow() },
                images = { link: String ->
                    val raw = SharedPython.run(context, "images", MediaAnalysisRouter.imageInput(item.url, link), UrlSniffer.identifySite(link), proxy, taskId = pid)
                    ImagePostSnapshots.put(context, raw)
                    json.decodeFromString<ImagePost>(raw.toString())
                })
            val tasks = when (val result = router.analyze(url, MediaPage.VIDEO)) {
                is ParsedContent.Video -> BatchTaskFactory.videos(item, result.metadata, options)
                is ParsedContent.Images -> result.post.let { post ->
                    require(post.resources.isNotEmpty()) { "该作品没有可下载图片" }
                    listOf(DownloadTask("batch-${item.id}-images", post.url, post.title, post.author,
                        post.resources.first().url, DownloadType.IMAGE_ZIP, schemaVersion = 4,
                        mediaId = post.contentId, imagePlatform = post.platform, selectedImageIds = post.resources.map { it.id }))
                }
            }
            currentCoroutineContext().ensureActive()
            return admission.withLock {
                currentCoroutineContext().ensureActive()
                val ids = tasks.map { it.id }
                require((reservedTaskIds + ids).size <= 500) { "每批最多 500 个下载任务，请分批添加" }
                reservedTaskIds.addAll(ids)
                // Reserve before submission: another completed resolver must count these tasks too.
                val missing = tasks.filter { repository.getTask(it.id) == null }
                repository.addTasks(missing)
                for (task in tasks) if (repository.getTask(task.id)?.status == TaskStatus.PENDING) DownloadService.startDownload(context, task.id)
                ids
            }
        } finally { processIds.remove(pid); DownloadEngine.cancelMetadata(pid) }
    }
}
