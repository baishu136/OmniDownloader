package com.omni.downloader.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.model.ImagePost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

class XProfileRuntimeTest {
    @Test fun suppliedProfileReportsAnonymousAccessRestrictionsWithoutClaimingCompletion(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments();val url = args.getString("xProfileUrl")
        assumeTrue("Opt-in profile required", url != null)
        val context = instrument.targetContext
        assumeTrue("Anonymous verification only",com.omni.downloader.data.repository.SessionRepository.getInstance(context).cookieHeader(LinkNormalizer.PLATFORM_X).isNullOrBlank())
        val response = runCatching { SharedPython.run(context,"x-profile-page",url!!,LinkNormalizer.PLATFORM_X,args.getString("xProxy").orEmpty()) }
        val summary = JSONObject().put("profile","hazzi_808").put("anonymous",true)
        response.onSuccess { value ->
            assertEquals("x_profile",value.getString("route"));assertTrue(value.getString("profileUserId").matches(Regex("[0-9]+")))
            summary.put("success",true).put("complete",value.getBoolean("profileComplete")).put("images",value.getJSONArray("resources").length())
        }.onFailure { error ->
            val code = MediaErrors.codeOf(error)
            assertTrue(error.message,code in setOf(MediaErrorCode.AUTH_REQUIRED,MediaErrorCode.CHALLENGE_REQUIRED,MediaErrorCode.RATE_LIMITED))
            summary.put("success",false).put("code",code.code).put("complete",false)
        }
        instrument.sendStatus(0,android.os.Bundle().apply { putString("stream","OMNI_X_PROFILE_LIVE=$summary\n") })
    }
    @Test fun bundledPythonExecutesThePinnedAdapterAndOriginalPostPlanning(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation(); val context = instrument.targetContext
        DownloadEngine.ensureInitialized(context).getOrThrow()
        val script = File.createTempFile("profile-fixture-", ".py",context.cacheDir)
        val output = File.createTempFile("profile-fixture-", ".out",context.cacheDir)
        var process: Process? = null
        try {
            script.writeBytes(instrument.context.assets.open("x-profile-fixture.py").use { it.readBytes() })
            val base = File(context.noBackupFilesDir,"youtubedl-android"); val home = File(base,"packages/python/usr")
            val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir,"libpython.so").path,script.path)
                .redirectErrorStream(true).redirectOutput(output)
            builder.environment().apply {
                put("PYTHONHOME",home.path); put("PYTHONPATH",File(base,"yt-dlp/yt-dlp").path)
                put("LD_LIBRARY_PATH",File(home,"lib").path+":"+File(base,"packages/ffmpeg/usr/lib").path)
                put("SSL_CERT_FILE",File(home,"etc/tls/cert.pem").path);put("TMPDIR",context.cacheDir.path)
                put("PYTHONIOENCODING","utf-8")
            }
            process = builder.start()
            assertTrue(process!!.waitFor(40,TimeUnit.SECONDS))
            assertEquals(output.readText(),0,process!!.exitValue())
            val result = JSONObject(output.readText());assertEquals("1.32.20",result.getString("version"))
            val pages = result.getJSONArray("pages");val json = Json { ignoreUnknownKeys = true }
            val merged = XProfileInput.merge(json.decodeFromString<ImagePost>(pages.getJSONObject(0).toString()),json.decodeFromString<ImagePost>(pages.getJSONObject(1).toString()))
            assertTrue(merged.profileComplete);assertEquals(2,merged.resources.size)
            assertEquals(listOf("101","102"),XProfileInput.posts(merged).map { it.contentId })
        } finally { process?.destroy();script.delete();output.delete() }
    }
}
