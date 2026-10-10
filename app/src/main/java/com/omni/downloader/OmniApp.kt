package com.omni.downloader

import android.app.Application
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.omni.downloader.engine.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmniApp : Application(), ImageLoaderFactory {

    companion object {
        var appVersion: String = "1.4.8"
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB 磁盘缓存
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false) // 避免切换标签页或列表滑动时图片交叉淡入动画抢占主线程合成算力
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        try {
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.4.8"
        } catch (_: Exception) {}

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
