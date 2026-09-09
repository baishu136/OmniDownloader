package com.omni.downloader.data.repository

import android.content.Context
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class TaskRepository private constructor(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val storageFile by lazy {
        File(context.filesDir, "download_tasks_v1.json")
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    init {
        loadTasksFromDisk()
    }

    private fun loadTasksFromDisk() {
        try {
            if (storageFile.exists()) {
                val content = storageFile.readText()
                if (content.isNotBlank()) {
                    val list = json.decodeFromString<List<DownloadTask>>(content)
                    // 处于进行中的任务若因上次退出未完成，标记为已中断或取消
                    val sanitized = list.map { task ->
                        if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PROCESSING || task.status == TaskStatus.PENDING) {
                            task.copy(status = TaskStatus.CANCELLED, errorMessage = "任务因应用退出已中止")
                        } else task
                    }
                    _tasks.value = sanitized
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveTasksToDisk() {
        repositoryScope.launch {
            try {
                val data = json.encodeToString(_tasks.value)
                storageFile.writeText(data)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addTask(task: DownloadTask) {
        val current = _tasks.value.toMutableList()
        current.add(0, task)
        _tasks.value = current
        saveTasksToDisk()
    }

    fun addTasks(newTasks: List<DownloadTask>) {
        if (newTasks.isEmpty()) return
        val current = _tasks.value.toMutableList()
        current.addAll(0, newTasks)
        _tasks.value = current
        saveTasksToDisk()
    }

    fun updateTaskProgress(
        taskId: String,
        progress: Float,
        speed: String,
        eta: String,
        status: TaskStatus
    ) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = current[index]
            current[index] = old.copy(
                progress = progress,
                speedText = speed,
                etaText = eta,
                status = status
            )
            _tasks.value = current
            // 完成或失败时触发持久化
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
                saveTasksToDisk()
            }
        }
    }

    fun markCompleted(taskId: String, localPath: String) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = current[index]
            current[index] = old.copy(
                status = TaskStatus.COMPLETED,
                progress = 100f,
                speedText = "",
                etaText = "",
                localFilePath = localPath
            )
            _tasks.value = current
            saveTasksToDisk()
        }
    }

    fun markFailed(taskId: String, error: String) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = current[index]
            current[index] = old.copy(
                status = TaskStatus.FAILED,
                speedText = "",
                etaText = "",
                errorMessage = error
            )
            _tasks.value = current
            saveTasksToDisk()
        }
    }

    fun removeTask(taskId: String, deleteLocalFile: Boolean = false) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val task = current[index]
            if (deleteLocalFile && task.localFilePath.isNotEmpty()) {
                try {
                    val file = File(task.localFilePath)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (ignored: Exception) {
                }
            }
            current.removeAt(index)
            _tasks.value = current
            saveTasksToDisk()
        }
    }

    /**
     * 批量清除符合条件的任务记录（单次更新 StateFlow 与单次落盘，避免并发竞态）
     */
    fun clearTasks(predicate: (DownloadTask) -> Boolean, deleteLocalFiles: Boolean = false): Int {
        val current = _tasks.value.toMutableList()
        val toRemove = current.filter(predicate)
        if (toRemove.isEmpty()) return 0

        if (deleteLocalFiles) {
            toRemove.forEach { task ->
                if (task.localFilePath.isNotEmpty()) {
                    try {
                        val file = File(task.localFilePath)
                        if (file.exists()) {
                            file.delete()
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }
        }

        current.removeAll(toRemove.toSet())
        _tasks.value = current
        saveTasksToDisk()
        return toRemove.size
    }

    companion object {
        @Volatile
        private var INSTANCE: TaskRepository? = null

        fun getInstance(context: Context): TaskRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
