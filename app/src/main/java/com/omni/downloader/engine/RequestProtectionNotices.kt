package com.omni.downloader.engine

import android.os.FileObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal data class RequestProtectionWarning(val platform:String,val label:String,val id:String,val reason:String,val until:Long)

/** Observes atomic file replacement from both native and bundled Python workers.
 * Notices survive background execution and process restarts; confirming never
 * resumes a failed task or discards a server-specified waiting deadline. */
internal object RequestProtectionNotices {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val _warnings=MutableStateFlow<List<RequestProtectionWarning>>(emptyList())
    val warnings=_warnings.asStateFlow()
    private var guards=emptyMap<String,PixivRequestGuard>()
    private var observer:FileObserver?=null
    @Synchronized fun initialize(directory:File) {
        if(observer!=null)return
        directory.mkdirs()
        guards=mapOf("pixiv" to PixivRequestGuard(directory),"x" to PixivRequestGuard(directory,policy=XNetwork.policy))
        @Suppress("DEPRECATION")
        val watcher=object:FileObserver(directory.path,FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event:Int,path:String?) {
                if(path in setOf("pixiv.json","x.json"))scope.launch{refresh()}
            }
        }
        observer=watcher;watcher.startWatching();scope.launch{refresh()}
    }
    @Synchronized private fun refresh(){_warnings.value=guards.values.mapNotNull{runCatching{it.warning()}.getOrNull()}}
    suspend fun acknowledge(warning:RequestProtectionWarning)=withContext(Dispatchers.IO) {
        guards[warning.platform]?.acknowledge(warning.id);refresh()
    }
}
