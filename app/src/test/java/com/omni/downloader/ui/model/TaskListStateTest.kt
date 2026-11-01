package com.omni.downloader.ui.model

import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test

class TaskListStateTest {
    private fun task(id: String, status: TaskStatus = TaskStatus.COMPLETED, collection: String? = null, episode: Int = 0) =
        DownloadTask(id, "https://example.org", id, downloadType = DownloadType.VIDEO_WITH_AUDIO,
            status = status, collectionId = collection, episodeIndex = episode)

    @Test fun groupsPreserveOrderAndSortEpisodesAndFilterAtCollectionLevel() {
        val state = buildTaskListState(listOf(task("single"), task("p2", collection = "c", episode = 2),
            task("active", TaskStatus.DOWNLOADING), task("p1", TaskStatus.PENDING, "c", 1)))
        assertEquals(3, state.pages[0].size)
        val group = state.pages[0][1] as TaskDisplayItem.Collection
        assertEquals(listOf("p1", "p2"), group.tasks.map { it.id })
        assertEquals(2, state.pages[1].size)
        assertEquals(1, state.pages[2].size)
        assertTrue(state.hasClearableTasks.all { it })
    }

    @Test fun emptyAndFailedCollectionsHaveCorrectFilters() {
        assertTrue(buildTaskListState(emptyList()).pages.all { it.isEmpty() })
        val state = buildTaskListState(listOf(task("a", TaskStatus.FAILED, "c"), task("b", collection = "c")))
        assertEquals(1, state.pages[0].size)
        assertTrue(state.pages[1].isEmpty())
        assertTrue(state.pages[2].isEmpty())
        assertTrue(state.hasClearableTasks.all { it })
    }
}
