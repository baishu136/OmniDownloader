package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.*
import com.omni.downloader.data.repository.*
import com.omni.downloader.service.DownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BatchDownloads(context: Context, private val scope: CoroutineScope,
    private val drafts: InputDraftRepository? = null) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("omni_batch", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = CompletableDeferred<Unit>()
    private val editLock = Any()
    private var job: Job? = null
    private var observer: Job? = null
    private val processIds = ConcurrentHashMap.newKeySet<String>()
    private val leases = ConcurrentHashMap.newKeySet<String>()
    private val admission = Mutex()
    private val reservedTaskIds = mutableSetOf<String>()
    private val repository get() = TaskRepository.getInstance(context)
    private val settings get() = SettingsRepository.getInstance(context)
    private val sessions get() = SessionRepository.getInstance(context)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    val queue = BatchQueue(::submit, include = ::currentInput) { plan ->
        check(prefs.edit().putString("plan", SessionCrypto.encrypt(json.encodeToString(plan))).commit()) { "批次记录保存失败" }
    }
    private sealed interface Resolved {
        data class Video(val metadata: VideoMetadata) : Resolved
        data class Images(val post: ImagePost, val manifest: String) : Resolved
    }
    private val preparation = BatchPreparation(scope, ::resolve,
        scopeKey = { url ->
            val platform = LinkNormalizer.platformOf(url)
            listOf(platform, settings.proxyUrl.value, NetworkProxy.configuredUrl,
                if (platform in SessionPlatforms.all) sessions.requestGeneration(platform) else "").joinToString("\u0000")
        }, cancelProcess = DownloadEngine::cancelMetadata,
        onActive = { token, active ->
            if (active) {
                processIds.add(token)
                if (runCatching { DownloadService.analysisLease(context, token, true) }.isSuccess) leases.add(token)
            } else {
                processIds.remove(token)
                if (leases.remove(token)) runCatching { DownloadService.analysisLease(context, token, false) }
            }
        }, describe = { result -> when (result) {
            is Resolved.Video -> "已解析 · 视频"
            is Resolved.Images -> "已解析 · ${result.post.resources.size} 张图片"
        } })
    val preparationStates = preparation.states

    private fun currentInput(item: BatchItem): Boolean = drafts?.draft?.value?.let {
        BatchInput.current(item, it.batchLinks, it.batchInputIds)
    } ?: true

    init {
        scope.launch(Dispatchers.IO) {
            try { prefs.getString("plan", null)?.let { encoded ->
                val plain = SessionCrypto.decrypt(encoded) ?: error("无法读取批次记录")
                repository.awaitLoaded()
                queue.restore(json.decodeFromString<BatchPlan>(plain)) { repository.getTask(it) != null }
            } } catch (_: Exception) { _error.value = "上次批次记录无法读取，可以重新添加链接" }
            finally { loaded.complete(Unit); _ready.value = true }
        }
        if (drafts != null) observer = scope.launch(Dispatchers.IO) {
            loaded.await(); drafts.awaitLoaded(); repository.awaitLoaded()
            merge(drafts.draft.map { Unit }, repository.taskStructure.map { Unit }, queue.plan.map { Unit },
                settings.proxyUrl.map { Unit }, sessions.profiles.map { Unit }).collect {
                synchronized(editLock) { reconcileInputs() }
            }
        }
    }
    private fun assigned(): Set<String> = drafts?.draft?.value?.batchTasks.orEmpty()
        .filterValues { binding -> binding.taskIds.isNotEmpty() && binding.taskIds.all { repository.getTask(it) != null } }.keys

    private fun syncPreparation() {
        drafts?.draft?.value?.let { preparation.sync(it.batchLinks, assigned()) }
    }
    private fun reconcileInputs() {
        val store = drafts ?: return
        val current = store.draft.value
        // Upgrade old drafts without losing queued task identities. History can subsequently be cleared independently.
        val activeRows = queue.plan.value.items.filter { !it.inputRetired && it.url in current.batchLinks &&
            (current.batchInputIds[it.url] == null || current.batchInputIds[it.url] == it.id) }
        val migrated = activeRows.filter { it.taskIds.isNotEmpty() }
            .associate { it.url to BatchLinkTasks(it.id, it.taskIds) } + current.batchTasks
        val identities = current.batchLinks.associateWith { url -> current.batchInputIds[url] ?: migrated[url]?.rowId
            ?: activeRows.firstOrNull { it.url == url }?.id ?: UUID.randomUUID().toString() }
        val completed = BatchCompletion.completed(current.batchLinks, migrated) { repository.getTask(it)?.status }
        if (completed.isNotEmpty()) {
            preparation.remove(completed)
            queue.retireInputs(completed)
        }
        store.update { it.copy(batchLinks = it.batchLinks - completed,
            batchTasks = migrated.filterKeys { url -> url in it.batchLinks && url !in completed },
            batchInputIds = identities - completed) }
        syncPreparation()
    }
    fun addLinks(text: String, single: Boolean): List<String> = synchronized(editLock) {
        val store = checkNotNull(drafts)
        check(store.ready.value && _ready.value) { "正在读取草稿与批次记录" }
        reconcileInputs()
        val current = store.draft.value
        val links = if (text.isBlank()) current.batchLinks else BatchInput.merge(current.batchLinks, text, single)
        require(links.isNotEmpty()) { "请先填入链接" }
        val identities = links.associateWith { current.batchInputIds[it] ?: UUID.randomUUID().toString() }
        store.update { it.copy(batchLinks = links, batchInputIds = identities, batchText = if (single) it.batchText else "", batchEntry = if (single) "" else it.batchEntry) }
        syncPreparation()
        links
    }
    fun removeLink(url: String) = synchronized(editLock) { removeInputs(setOf(url), false) }
    fun clearInput() = synchronized(editLock) { removeInputs(drafts?.draft?.value?.batchLinks.orEmpty().toSet(), true) }
    private fun removeInputs(urls: Set<String>, clearText: Boolean) {
        preparation.remove(urls)
        queue.removeInputs(urls)
        drafts?.update { it.copy(batchLinks = it.batchLinks - urls, batchTasks = it.batchTasks - urls, batchInputIds = it.batchInputIds - urls,
            batchText = if (clearText) "" else it.batchText, batchEntry = if (clearText) "" else it.batchEntry) }
    }
    fun start(text: String, options: BatchOptions, preservePending: Boolean = false) {
        if (job?.isActive == true) return
        if (drafts != null && !_ready.value) { _error.value = "正在读取批次记录，请稍后重试"; return }
        val links = runCatching { BatchInput.links(text) }.getOrElse { _error.value = it.message; return }
        synchronized(editLock) {
            if (drafts != null && loaded.isCompleted) reconcileInputs()
            val current = drafts?.draft?.value
            launch(BatchPlan(links.map { BatchItem(id = current?.batchInputIds?.get(it) ?: current?.batchTasks?.get(it)?.rowId
                ?: UUID.randomUUID().toString(), url = it) }, options), preservePending)
        }
    }
    fun retry() { if (job?.isActive != true) launch(null) }
    fun cancel() {
        job?.cancel(); preparation.stop()
        processIds.toList().forEach(DownloadEngine::cancelMetadata)
    }
    fun close() { cancel(); observer?.cancel(); preparation.close() }
    fun clearHistory() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            loaded.await()
            try { synchronized(editLock) { reconcileInputs(); queue.clearHistory() }; _error.value = null }
            catch (error: Exception) { _error.value = LogSanitizer.sanitize(error.message.orEmpty()) }
        }
    }
    private fun launch(plan: BatchPlan?, preservePending: Boolean = false) {
        _error.value = null
        val token = UUID.randomUUID().toString()
        val held = runCatching { DownloadService.analysisLease(context, token, true) }.isSuccess
        job = scope.launch(Dispatchers.IO) {
            loaded.await(); drafts?.awaitLoaded(); repository.awaitLoaded()
            try {
                val next = synchronized(editLock) {
                    if (drafts != null) reconcileInputs()
                    val incoming = plan?.let { p -> p.copy(items = p.items.filter(::currentInput)) }
                    val previous = queue.plan.value.let { p -> p.copy(items = p.items.filter { it.inputRetired || currentInput(it) || drafts?.draft?.value?.batchInputIds?.get(it.url) == null }) }
                    val candidate = if (incoming != null && preservePending) BatchInput.preservePending(previous, incoming) else incoming ?: previous
                    if (drafts != null) {
                        val pending = candidate.items.filter { !it.inputRetired && it.status != BatchStatus.QUEUED }.map { it.url }
                        drafts.update {
                            val links = (it.batchLinks + pending).distinct()
                            require(links.size <= 100) { "加上未完成链接后超过 100 条，请先移除部分已填链接" }
                            it.copy(batchLinks = links, batchInputIds = candidate.items.filter { row -> !row.inputRetired }
                                .associate { row -> row.url to row.id } + it.batchInputIds)
                        }
                        syncPreparation()
                    } else preparation.sync(candidate.items.filter { !it.inputRetired }.map { it.url })
                    candidate
                }
                admission.withLock {
                    reservedTaskIds.clear(); reservedTaskIds.addAll(next.items.flatMap { it.taskIds })
                }
                queue.run(next)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = LogSanitizer.sanitize(error.message.orEmpty()) }
        }.also { it.invokeOnCompletion { if (held) runCatching { DownloadService.analysisLease(context, token, false) } } }
    }
    private suspend fun resolve(original: String, pid: String): Resolved {
        currentCoroutineContext().ensureActive()
        val proxy = settings.proxyUrl.value
        val deadline = if (LinkNormalizer.platformOf(original) == LinkNormalizer.PLATFORM_DOUYIN) DouyinResolutionBudget().deadlineNanos else null
        val recovered = UrlSniffer.resolveContent(original)
        val url = if (deadline != null) DouyinResolution.redirectTarget(recovered) else when (recovered) {
            is LinkNormalizer.RedirectResult.Content -> recovered.url
            is LinkNormalizer.RedirectResult.LoginRequired -> error("[AUTH_REQUIRED] 请登录后单独解析此链接")
            is LinkNormalizer.RedirectResult.ChallengeRequired -> error("[CHALLENGE_REQUIRED] 请完成验证后重试")
            is LinkNormalizer.RedirectResult.Unavailable -> error("链接暂时不可访问")
        }
        val router = MediaAnalysisRouter(
            video = { link: String -> DownloadEngine.fetchVideoMetadata(context, link, proxy, pid,
                refreshSources = true, resolutionDeadlineNanos = deadline).getOrThrow() },
            images = { link: String ->
                val raw = SharedPython.run(context, "images", MediaAnalysisRouter.imageInput(original, link), UrlSniffer.identifySite(link), proxy, taskId = pid).toString()
                Resolved.Images(json.decodeFromString<ImagePost>(raw), raw)
            })
        return when (val result = router.analyze(url, MediaPage.VIDEO)) {
            is ParsedContent.Video -> Resolved.Video(result.metadata)
            is ParsedContent.Images -> result.post.also { require(it.post.resources.isNotEmpty()) { "该作品没有可下载图片" } }
        }
    }
    private suspend fun submit(item: BatchItem, options: BatchOptions): List<String> {
        repository.awaitLoaded()
        synchronized(editLock) {
            drafts?.draft?.value?.let { draft ->
                if (!BatchInput.current(item, draft.batchLinks, draft.batchInputIds)) throw BatchLinkRemoved()
                draft.batchTasks[item.url]?.taskIds?.takeIf { it.isNotEmpty() && it.all { id -> repository.getTask(id) != null } }?.let { return it }
            }
        }
        val prepared = preparation.await(item.url)
        val row = drafts?.draft?.value?.batchTasks?.get(item.url)?.let { item.copy(id = it.rowId) } ?: item
        val tasks = when (val result = prepared.value) {
            is Resolved.Video -> BatchTaskFactory.videos(row, result.metadata, options)
            is Resolved.Images -> result.post.let { post ->
                listOf(DownloadTask("batch-${row.id}-images", post.url, post.title, post.author,
                    post.resources.first().url, DownloadType.IMAGE_ZIP, schemaVersion = 4,
                    mediaId = post.contentId, imagePlatform = post.platform, selectedImageIds = post.resources.map { it.id }))
            }
        }
        currentCoroutineContext().ensureActive()
        return admission.withLock {
            currentCoroutineContext().ensureActive()
            val ids = tasks.map { it.id }
            synchronized(editLock) {
                preparation.withCurrent(prepared) {
                    if (!currentInput(item)) throw BatchLinkRemoved()
                    require((reservedTaskIds + ids).size <= 500) { "每批最多 500 个下载任务，请分批添加" }
                    if (prepared.value is Resolved.Images) ImagePostSnapshots.put(context, org.json.JSONObject(prepared.value.manifest))
                    reservedTaskIds.addAll(ids)
                    repository.addTasks(tasks.filter { repository.getTask(it.id) == null })
                    drafts?.update { it.copy(batchTasks = it.batchTasks + (item.url to BatchLinkTasks(row.id, ids))) }
                }
            }
            for (task in tasks) if (repository.getTask(task.id)?.status == TaskStatus.PENDING) DownloadService.startDownload(context, task.id)
            ids
        }
    }
}
