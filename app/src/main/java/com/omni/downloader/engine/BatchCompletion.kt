package com.omni.downloader.engine

import com.omni.downloader.data.model.TaskStatus

object BatchCompletion {
    /** A missing, failed or cancelled episode must never be mistaken for a completed collection. */
    fun completed(urls: List<String>, bindings: Map<String, BatchLinkTasks>, status: (String) -> TaskStatus?): Set<String> =
        urls.filterTo(linkedSetOf()) { url ->
            bindings[url]?.taskIds?.let { ids -> ids.isNotEmpty() && ids.all { status(it) == TaskStatus.COMPLETED } } == true
        }
}
