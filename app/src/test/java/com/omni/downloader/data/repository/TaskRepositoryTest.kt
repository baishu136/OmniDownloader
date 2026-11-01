package com.omni.downloader.data.repository

import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun task(id: String, status: TaskStatus = TaskStatus.DOWNLOADING) =
        DownloadTask(id, "https://example.org/" + id, id, downloadType = DownloadType.VIDEO_WITH_AUDIO, status = status)

    @Test fun progressIsLosslessWithoutRebuildingStructure() = runTest {
        val repo = TaskRepository(folder.newFile(), backgroundScope)
        runCurrent()
        repo.addTasks((0 until 10000).map { task(it.toString()) })
        val structure = repo.taskStructure.value
        val liveA = repo.getTaskProgressFlow("0")
        repeat(1000) { n ->
            repo.updateTaskProgress("0", n / 10f, "A", "1s", TaskStatus.DOWNLOADING)
            repo.updateTaskProgress("9999", n / 20f, "B", "2s", TaskStatus.DOWNLOADING)
        }
        assertSame(structure, repo.taskStructure.value)
        assertEquals(99.9f, liveA.value.progress, 0.001f)
        assertEquals(49.95f, repo.getTask("9999")!!.progress, 0.001f)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(99.9f, repo.tasks.value.first { it.id == "0" }.progress, 0.001f)
        assertEquals(49.95f, repo.tasks.value.first { it.id == "9999" }.progress, 0.001f)
    }

    @Test fun existingProgressObserversSurviveAddingTaskAndCompletion() = runTest {
        val repo = TaskRepository(folder.newFile(), backgroundScope)
        runCurrent()
        val flow = repo.getTaskProgressFlow("a")
        repo.addTask(task("a"))
        assertSame(flow, repo.getTaskProgressFlow("a"))
        repo.markCompleted("a", "/video.mp4")
        assertEquals(TaskStatus.COMPLETED, flow.value.status)
        assertEquals(100f, flow.value.progress)
        assertEquals("/video.mp4", repo.taskStructure.value.single().localFilePath)
    }

    @Test fun hydrationReplaysNewTasksDeletesAndClears() = runTest {
        val file = folder.newFile()
        file.writeText(Json.encodeToString(listOf(task("remove", TaskStatus.COMPLETED), task("keep", TaskStatus.COMPLETED), task("active"))))
        val repo = TaskRepository(file, backgroundScope)
        repo.addTask(task("new"))
        repo.removeTask("remove")
        repo.clearTasks({ it.id == "active" })
        runCurrent()
        assertEquals(listOf("new", "keep"), repo.tasks.value.map { it.id })
        assertEquals(TaskStatus.DOWNLOADING, repo.getTask("new")!!.status)
    }

    @Test fun interruptedTasksAreSanitizedOffStartupPath() = runTest {
        val file = folder.newFile()
        file.writeText(Json.encodeToString(listOf(task("old"))))
        val repo = TaskRepository(file, backgroundScope)
        assertTrue(repo.tasks.value.isEmpty())
        repo.awaitLoaded()
        assertEquals(TaskStatus.CANCELLED, repo.getTask("old")!!.status)
    }

    @Test fun serializedWriterPersistsNewestStateAndNoTemporaryFileRemains() = runTest {
        val file = folder.newFile()
        val repo = TaskRepository(file, backgroundScope)
        runCurrent()
        repo.addTasks((0 until 100).map { task(it.toString()) })
        repeat(100) { repo.markFailed(it.toString(), "error " + it) }
        repo.markCompleted("99", "/finished.mp4")
        runCurrent()
        val restored = Json.decodeFromString<List<DownloadTask>>(file.readText())
        assertEquals(100, restored.size)
        assertEquals("/finished.mp4", restored.first { it.id == "99" }.localFilePath)
        assertEquals("error 0", restored.first { it.id == "0" }.errorMessage)
        assertFalse(java.io.File(file.path + ".tmp").exists())
    }

    @Test fun localFileDeletionRunsInRepositoryScope() = runTest {
        val media = folder.newFile()
        val repo = TaskRepository(folder.newFile(), backgroundScope)
        runCurrent()
        repo.addTask(task("a", TaskStatus.COMPLETED).copy(localFilePath = media.path))
        assertEquals(1, repo.clearTasks({ true }, deleteLocalFiles = true))
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(media.exists())
        runCurrent()
        assertFalse(media.exists())
    }
}
