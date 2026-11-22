package com.omni.downloader.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/** Check the actual bundled CLI plugin offline before allowing X extraction. */
internal object XGuardRuntime {
    private var verified:String?=null
    @Synchronized fun ensureReady(context:Context) {
        val root=File(context.noBackupFilesDir,"youtubedl-android")
        val engine=File(root,"yt-dlp/yt-dlp")
        val marker=File(root,"packages/python/usr/lib/python3.12/site-packages/.omni-extras-sha256")
        val key="${engine.length()}:${engine.lastModified()}:${marker.readText()}"
        if(verified==key)return
        val request=YoutubeDLRequest("omni-x-guard:probe").apply{
            addOption("-J");addOption("--ignore-config");addOption("--no-cache-dir");addOption("--no-check-formats")
        }
        val result=YoutubeDL.getInstance().execute(request).out.trim()
        check(runCatching{JSONObject(result).optBoolean("_omni_x_guard_ready")}.getOrDefault(false)) {"[ENGINE_UNAVAILABLE] X 请求保护自检失败，未发起平台请求"}
        verified=key
    }
}
