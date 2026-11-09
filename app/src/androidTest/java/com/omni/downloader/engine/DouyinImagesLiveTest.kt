package com.omni.downloader.engine

import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DouyinImagesLiveTest {
    @Test fun userShareReturnsItsOwnImagesThroughProductionAnalysis(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("dyImageUrl")
        assumeTrue("Opt-in user link required", url != null)
        val context = instrument.targetContext
        val settings = SettingsRepository.getInstance(context)
        val oldProxy = settings.proxyUrl.value
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        val result = JSONObject().put("success", false).put("mode", "live-app-analysis")
        try {
            settings.setProxyUrl(args.getString("testProxy").orEmpty())
            instrument.runOnMainSync {
                model = MainViewModel(context.applicationContext as android.app.Application); store.put("dy-images-qa", model)
                model.updateImageInput(url!!); model.analyzeImages()
            }
            withTimeout(150_000) { while (model.imageBusy.value || model.isAnalyzing.value) delay(100) }
            val post = model.imagePost.value ?: error(model.imageError.value.ifBlank { model.errorDialogDetail.value ?: "No image result" })
            assertEquals("7567352444847574309", post.contentId)
            assertEquals(21, post.resources.size)
            assertTrue(post.resources.all { it.url.startsWith("http") })
            result.put("mediaId", post.contentId).put("imageCount", post.resources.size).put("success", true)
            val runtime = SharedPython.run(context, "probe", url!!, "抖音", settings.proxyUrl.value)
            result.put("python", runtime.optString("python")).put("socks", runtime.optString("socks"))
        } catch (error: Exception) {
            result.put("errorCode", MediaErrors.codeOf(error).code).put("error", LogSanitizer.sanitize(error.message.orEmpty()))
            throw error
        } finally {
            File(context.cacheDir, "dy-images-live-result.json").writeText(result.toString())
            instrument.sendStatus(0, Bundle().apply { putString("stream", "OMNI_DY_IMAGES=$result\n") })
            instrument.runOnMainSync { store.clear() }; settings.setProxyUrl(oldProxy)
        }
    }
}
