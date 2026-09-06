package com.omni.downloader

import android.app.Application
import android.util.Log
import com.omni.downloader.engine.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmniApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 后台异步预热初始化核心下载引擎，避免阻塞启动并提前解压
        appScope.launch {
            try {
                Log.d("OmniApp", "正在预热核心下载引擎...")
                val result = DownloadEngine.ensureInitialized(applicationContext)
                result.onSuccess {
                    Log.d("OmniApp", "核心下载引擎预热成功")
                }.onFailure { e ->
                    Log.e("OmniApp", "核心下载引擎预热遇到问题: ${e.message}", e)
                }
            } catch (e: Exception) {
                Log.e("OmniApp", "预热异常", e)
            }
        }
    }
}
