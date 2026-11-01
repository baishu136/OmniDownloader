package com.omni.downloader

import android.app.Application
import android.content.ComponentCallbacks2
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class OmniApp : Application(), ImageLoaderFactory {

    companion object {
        var appVersion: String = "1.5.9"
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 专用的图像解码轻量调度器：限制最大并发为 3，彻底消除列表快速滚动时海量解码协程打满 CPU 导致的系统发热降频
    private val imageDecoderDispatcher by lazy {
        Executors.newFixedThreadPool(3) { r ->
            Thread(r, "OmniImageDecoder").apply { priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()
    }

    private var imageLoaderInstance: ImageLoader? = null

    override fun newImageLoader(): ImageLoader {
        return imageLoaderInstance ?: synchronized(this) {
            imageLoaderInstance ?: ImageLoader.Builder(this)
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
                        .maxSizePercent(0.20) // 20% 堆内存安全上限，留足余量给 UI 与音频处理
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(50L * 1024 * 1024)
                        .build()
                }
                .decoderDispatcher(imageDecoderDispatcher)
                .allowRgb565(true) // 优先使用 RGB_565，显存占用降低 50%，大幅削减 GC 垃圾回收频次
                .respectCacheHeaders(false)
                .crossfade(false)
                .build().also { imageLoaderInstance = it }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            imageLoaderInstance?.memoryCache?.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.5.2"
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
