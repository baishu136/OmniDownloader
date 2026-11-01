package com.omni.downloader.data.repository

import android.content.Context
import androidx.compose.runtime.Immutable
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Immutable
data class TaskProgress(
    val progress: Float = 0f,
    val speedText: String = "",
    val etaText: String = "",
    val status: TaskStatus = TaskStatus.PENDING
)

/** ID-indexed current state; lists are published only for structural changes or a
 * coalesced compatibility snapshot. JSON and file operations never hold the UI lock.
 */
class TaskRepository internal constructor(
    private val storageFile: File,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var current = linkedMapOf<String, DownloadTask>()
    private val progressFlows = HashMap<String, MutableStateFlow<TaskProgress>>()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private val _taskStructure = MutableStateFlow<List<DownloadTask>>(emptyList())
    val taskStructure = _taskStructure.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val snapshotRequests = Channel<Unit>(Channel.CONFLATED)
    // Replay operations issued during asynchronous hydration, preserving new tasks,
    // deletes, and cancellation instead of overwriting them with older disk data.
    private var startupChanges: MutableList<(LinkedHashMap<String, DownloadTask>) -> Unit>? = mutableListOf()

    init {
        repositoryScope.launch {
            val restored = try {
                val data = if (storageFile.exists()) storageFile.readText() else ""
                if (data.isBlank()) emptyList() else json.decodeFromString<List<DownloadTask>>(data)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            synchronized(lock) {
                val merged = linkedMapOf<String, DownloadTask>()
                restored.forEach { task ->
                    merged[task.id] = if (task.status.isActive()) {
                        task.copy(status = TaskStatus.CANCELLED, errorMessage = "任务因应用退出已中止")
                    } else task
                }
                startupChanges?.forEach { it(merged) }
                startupChanges = null
                current = merged
                publishStructureLocked()
            }
            loaded.complete(Unit)
        }
        // Exactly one writer. A request during a write guarantees another pass with
        // the newest snapshot. Atomic replacement also survives process interruption.
        repositoryScope.launch {
            loaded.await()
            for (request in saveRequests) {
                try {
                    val data = json.encodeToString(snapshot())
                    val temp = File(storageFile.path + ".tmp")
                    temp.writeText(data)
                    try {
                        Files.move(temp.toPath(), storageFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        repositoryScope.launch {
            for (request in snapshotRequests) {
                delay(250)
                synchronized(lock) { _tasks.value = current.values.toList() }
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()
    fun getTask(taskId: String): DownloadTask? = synchronized(lock) { current[taskId] }
    private fun snapshot() = synchronized(lock) { current.values.toList() }

    fun getTaskProgressFlow(taskId: String): StateFlow<TaskProgress> = synchronized(lock) {
        progressFlows.getOrPut(taskId) { MutableStateFlow(current[taskId].toProgress()) }
    }

    private fun DownloadTask?.toProgress() = TaskProgress(
        this?.progress ?: 0f, this?.speedText ?: "", this?.etaText ?: "", this?.status ?: TaskStatus.PENDING
    )

    private fun publishStructureLocked() {
        val snapshot = current.values.toList()
        _tasks.value = snapshot
        _taskStructure.value = snapshot
        current.forEach { (id, task) ->
            progressFlows[id]?.value = task.toProgress()
        }
        progressFlows.keys.retainAll(current.keys)
    }

    private fun change(action: (LinkedHashMap<String, DownloadTask>) -> Unit) {
        synchronized(lock) {
            action(current)
            startupChanges?.add(action)
            publishStructureLocked()
        }
        saveRequests.trySend(Unit)
    }

    fun addTask(task: DownloadTask) = addTasks(listOf(task))

    fun addTasks(newTasks: List<DownloadTask>) {
        if (newTasks.isEmpty()) return
        val added = newTasks.toList()
        change { map ->
            val ordered = linkedMapOf<String, DownloadTask>()
            added.forEach { ordered[it.id] = it }
            map.forEach { (id, task) -> if (id !in ordered) ordered[id] = task }
            map.clear()
            map.putAll(ordered)
        }
    }

    fun updateTaskProgress(taskId: String, progress: Float, speed: String, eta: String, status: TaskStatus) {
        var statusChanged = false
        synchronized(lock) {
            val old = current[taskId] ?: return
            val updated = old.copy(progress = progress, speedText = speed, etaText = eta, status = status)
            if (old == updated) return
            current[taskId] = updated
            startupChanges?.add { map -> map[taskId]?.let {
                map[taskId] = it.copy(progress = progress, speedText = speed, etaText = eta, status = status)
            } }
            progressFlows[taskId]?.value = updated.toProgress()
            statusChanged = old.status != status
            if (statusChanged) publishStructureLocked()
            else snapshotRequests.trySend(Unit)
        }
        if (statusChanged) saveRequests.trySend(Unit)
    }

    fun markCompleted(taskId: String, localPath: String) = change { map ->
        map[taskId]?.let { map[taskId] = it.copy(status = TaskStatus.COMPLETED,
            progress = 100f, speedText = "", etaText = "", localFilePath = localPath, errorMessage = "") }
    }

    fun markFailed(taskId: String, error: String) = change { map ->
        map[taskId]?.let { map[taskId] = it.copy(status = TaskStatus.FAILED,
            speedText = "", etaText = "", errorMessage = error) }
    }

    fun removeTask(taskId: String, deleteLocalFile: Boolean = false) {
        clearTasks({ it.id == taskId }, deleteLocalFile)
    }

    fun clearTasks(predicate: (DownloadTask) -> Boolean, deleteLocalFiles: Boolean = false): Int {
        var count = 0
        change { map ->
            val removed = map.values.filter(predicate)
            count = removed.size
            removed.forEach { map.remove(it.id) }
            if (deleteLocalFiles) {
                val paths = removed.map { it.localFilePath }.filter { it.isNotEmpty() }
                repositoryScope.launch { paths.forEach { path ->
                    try { File(path).delete() } catch (e: Exception) { e.printStackTrace() }
                } }
            }
        }
        return count
    }

    companion object {
        @Volatile private var INSTANCE: TaskRepository? = null
        fun getInstance(context: Context): TaskRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: TaskRepository(File(context.applicationContext.filesDir, "download_tasks_v1.json"))
                .also { INSTANCE = it }
        }
    }
}

private fun TaskStatus.isActive() = this == TaskStatus.PENDING ||
    this == TaskStatus.DOWNLOADING || this == TaskStatus.PROCESSING
