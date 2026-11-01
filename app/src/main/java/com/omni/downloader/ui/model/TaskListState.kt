package com.omni.downloader.ui.model

import androidx.compose.runtime.Immutable
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus

@Immutable
internal sealed class TaskDisplayItem {
    data class Single(val task: DownloadTask) : TaskDisplayItem()
    data class Collection(val collectionId: String, val collectionTitle: String,
        val tasks: List<DownloadTask>) : TaskDisplayItem()
}

@Immutable
internal data class TaskListState(
    val pages: List<List<TaskDisplayItem>> = List(3) { emptyList() },
    val hasClearableTasks: List<Boolean> = List(3) { false }
)

/** Run on Default, once per structural snapshot, never during composition. */
internal fun buildTaskListState(tasks: List<DownloadTask>): TaskListState {
    val collections = tasks.filter { it.collectionId != null }.groupBy { it.collectionId!! }
    val seen = HashSet<String>()
    val all = ArrayList<TaskDisplayItem>()
    val active = ArrayList<TaskDisplayItem>()
    val completed = ArrayList<TaskDisplayItem>()
    for (task in tasks) {
        val id = task.collectionId
        val item = if (id == null) TaskDisplayItem.Single(task) else {
            if (!seen.add(id)) continue
            val group = collections.getValue(id).sortedBy { if (it.episodeIndex > 0) it.episodeIndex else Int.MAX_VALUE }
            TaskDisplayItem.Collection(id, task.collectionTitle ?: group.first().title, group)
        }
        all.add(item)
        val members = when (item) {
            is TaskDisplayItem.Single -> listOf(item.task)
            is TaskDisplayItem.Collection -> item.tasks
        }
        if (members.any { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }) active.add(item)
        if (members.all { it.status == TaskStatus.COMPLETED }) completed.add(item)
    }
    return TaskListState(
        pages = listOf(all, active, completed),
        hasClearableTasks = listOf(tasks.isNotEmpty(), tasks.any { it.status != TaskStatus.COMPLETED }, tasks.any { it.status == TaskStatus.COMPLETED })
    )
}
