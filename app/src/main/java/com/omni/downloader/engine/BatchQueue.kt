package com.omni.downloader.engine

import com.omni.downloader.data.model.AudioFormat
import com.omni.downloader.data.model.DownloadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable enum class BatchStatus { PENDING, RESOLVING, QUEUED, FAILED, CANCELLED }
@Serializable data class BatchOptions(val type: DownloadType = DownloadType.VIDEO_WITH_AUDIO,
    val audio: AudioFormat = AudioFormat.MP3, val allEpisodes: Boolean = false)
@Serializable data class BatchItem(val id: String = UUID.randomUUID().toString(), val url: String,
    val status: BatchStatus = BatchStatus.PENDING, val note: String = "", val taskIds: List<String> = emptyList())
@Serializable data class BatchPlan(val items: List<BatchItem> = emptyList(), val options: BatchOptions = BatchOptions())

object BatchInput {
    fun merge(existing: List<String>, text: String, single: Boolean = false): List<String> {
        val added = links(text)
        require(!single || added.size == 1) { "逐条添加每次只接受一条链接，多条分享文本请使用智能识别" }
        return (existing + added).distinct().also {
            require(it.size <= 100) { "每批最多 100 条链接，请分批添加" }
            require(it.sumOf { link -> link.length + 1 } <= 100_000) { "链接文本过长，请分批添加" }
        }
    }
    fun preservePending(previous: BatchPlan, incoming: BatchPlan): BatchPlan {
        val known = previous.items.associateBy { it.url }
        val rows = (previous.items.filter { it.status != BatchStatus.QUEUED } + incoming.items.map { known[it.url] ?: it }).distinctBy { it.url }
        require(rows.size <= 100) { "加上未完成链接后超过 100 条，请先继续处理原批次" }
        return incoming.copy(items = rows)
    }
    fun links(text: String): List<String> {
        require(text.length <= 100_000) { "文本过长，请分批添加" }
        val pattern = Regex("""https?://[^\s<>"'，。；、（）【】！？「」『』]+|(?<![A-Za-z0-9])(?:BV[A-Za-z0-9]{10}|av\d+)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
        val clean = text.replace("\u200B", "").replace("\uFEFF", "")
        val links = pattern.findAll(clean).map { match ->
            val value = match.value.trimEnd('.', ',', ';', '!', ')', ']', '}')
            val normalizedCase = if (value.contains("://")) value.substringBefore("://").lowercase() + "://" + value.substringAfter("://")
                else if (value.startsWith("bv", true)) "BV" + value.substring(2) else value.lowercase()
            LinkNormalizer.normalize(normalizedCase).contentUrl
        }
            .filter { runCatching { URI(it).let { u -> u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null } }.getOrDefault(false) }
            .distinct().toList()
        require(links.isNotEmpty()) { "没有找到有效链接" }
        require(links.size <= 100) { "每批最多 100 条链接，请分批添加" }
        return links
    }
}

/** One failing link must not block others. QUEUED means submitted, not downloaded. */
class BatchQueue(private val submit: suspend (BatchItem, BatchOptions) -> List<String>,
    private val save: (BatchPlan) -> Unit = {}) {
    private val _plan = MutableStateFlow(BatchPlan())
    val plan = _plan.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    fun restore(plan: BatchPlan, taskExists: (String) -> Boolean = { true }) {
        check(!_running.value)
        _plan.value = plan.copy(items = plan.items.map { item ->
            when {
                item.status in setOf(BatchStatus.RESOLVING, BatchStatus.PENDING) -> item.copy(status = BatchStatus.CANCELLED, note = "上次批次已中断，可继续")
                item.status == BatchStatus.QUEUED && (item.taskIds.isEmpty() || item.taskIds.any { !taskExists(it) }) ->
                    item.copy(status = BatchStatus.CANCELLED, note = "部分任务记录已缺失，可继续补充；已有任务不会重复添加")
                else -> item
            }
        })
    }
    private fun publish(plan: BatchPlan) { _plan.value = plan; save(plan) }
    private fun update(id: String, change: (BatchItem) -> BatchItem) =
        publish(_plan.value.copy(items = _plan.value.items.map { if (it.id == id) change(it) else it }))
    suspend fun run(newPlan: BatchPlan? = null) {
        check(_running.compareAndSet(false, true)) { "批次正在运行" }
        try {
            if (newPlan != null) publish(newPlan)
            val pending = _plan.value.items.filter { it.status != BatchStatus.QUEUED }
            for (item in pending) {
                currentCoroutineContext().ensureActive()
                update(item.id) { it.copy(status = BatchStatus.RESOLVING, note = "正在解析") }
                try {
                    val ids = submit(item, _plan.value.options)
                    require(ids.isNotEmpty()) { "未生成下载任务" }
                    update(item.id) { it.copy(status = BatchStatus.QUEUED, note = "已加入 ${ids.size} 个下载任务", taskIds = ids) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { update(item.id) { it.copy(status = BatchStatus.FAILED, note = LogSanitizer.sanitize(error.message.orEmpty()).take(500)) } }
            }
        } finally {
            try { publish(_plan.value.copy(items = _plan.value.items.map {
                if (it.status in setOf(BatchStatus.RESOLVING, BatchStatus.PENDING)) it.copy(status = BatchStatus.CANCELLED, note = "未入队，可继续处理") else it
            })) } finally { _running.value = false }
        }
    }
}
