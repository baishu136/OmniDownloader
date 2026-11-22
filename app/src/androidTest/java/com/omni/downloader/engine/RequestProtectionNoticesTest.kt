package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs in the dedicated QA installation; synthetic denials and no network. */
class RequestProtectionNoticesTest {
    @Test fun nativeAndPythonWarningsReachUiAndAcknowledgmentAllowsManualRetry():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val root=File(context.filesDir,"network_guard");val state=File(root,"pixiv.json")
        val original=state.takeIf{it.isFile}?.readBytes()
        val guard=PixivRequestGuard(root)
        try{
            guard.block(401);val first=guard.warning()!!
            val shown=withTimeout(8000){RequestProtectionNotices.warnings.first{list->list.any{it.id==first.id}}}.single{it.id==first.id}
            assertTrue(shown.until<=System.currentTimeMillis())
            RequestProtectionNotices.acknowledge(shown);assertNull(guard.warning());guard.acquire("api")
            val base=File(context.noBackupFilesDir,"youtubedl-android");val home=File(base,"packages/python/usr")
            val process=ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libpython.so","-c",
                "from omni_pixiv_guard import PixivGuard; import sys; PixivGuard(sys.argv[1]).block(429)",root.path)
                .apply{environment().apply{put("PYTHONHOME",home.path);put("LD_LIBRARY_PATH","${home.path}/lib")}}.redirectErrorStream(true).start()
            try{assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(process.inputStream.bufferedReader().readText(),0,process.exitValue())}finally{process.destroyForcibly()}
            val second=guard.warning()!!;assertNotEquals(first.id,second.id)
            val fromPython=withTimeout(8000){RequestProtectionNotices.warnings.first{list->list.any{it.id==second.id}}}.single{it.id==second.id}
            RequestProtectionNotices.acknowledge(fromPython);assertNull(guard.warning());guard.acquire("media")
        }finally{
            guard.warning()?.let{RequestProtectionNotices.acknowledge(it)}
            if(original!=null)state.writeBytes(original) else state.delete()
        }
    }
}
