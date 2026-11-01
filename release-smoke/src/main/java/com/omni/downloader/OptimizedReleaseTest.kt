package com.omni.downloader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Black-box validation of the actual obfuscated Release APK. No references to
 * app or Compose class names, which are intentionally rewritten/removed by R8.
 * Run on an isolated test installation after clearing its data for cold extraction.
 */
class OptimizedReleaseTest {
    @Test fun optimizedApkRestoresHistoryNavigatesAndProcessesMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("key_has_prompted_bilibili_login", true)
            .putBoolean("key_has_shown_relay_compat_tip", true)
            .putInt("key_app_config_version", 40)
            .putString("key_app_language", "en").commit()
        val history = JSONArray().put(JSONObject().put("id", "size-history")
            .put("url", "https://example.org/video.mp4").put("title", "Size smoke history")
            .put("downloadType", "VIDEO_WITH_AUDIO").put("status", "COMPLETED").put("progress", 100))
        File(context.filesDir, "download_tasks_v1.json").writeText(history.toString())
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        if (!device.wait(Until.hasObject(By.text("Home")), 15000)) {
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "release-smoke-window.xml"))
            fail("Home navigation missing; foreground=" + device.currentPackageName + "; target=" + context.packageName)
        }
        fun navigate(label: String) {
            val item = device.findObjects(By.text(label)).maxByOrNull { it.visibleBounds.centerY() }
            assertNotNull("Navigation missing: " + label, item)
            item!!.click()
            device.waitForIdle()
        }
        repeat(3) {
            navigate("Tasks")
            assertTrue("Persisted task serializer failed after R8", device.wait(Until.hasObject(By.text("Size smoke history")), 5000))
            navigate("Settings")
            val settingsDeadline = SystemClock.elapsedRealtime() + 5000
            var settingsVisible = false
            while (!settingsVisible && SystemClock.elapsedRealtime() < settingsDeadline) {
                settingsVisible = device.findObjects(By.text("Settings")).any { it.visibleBounds.centerY() < device.displayHeight / 2 }
                if (!settingsVisible) Thread.sleep(50)
            }
            assertTrue("Settings page did not become visible", settingsVisible)
            navigate("Home")
        }

        navigate("Settings")
        for (title in listOf("Appearance & language", "Downloads & storage", "Network proxy", "Relay sites", "Accounts", "Download engine", "About")) {
            assertTrue("Missing category: " + title, device.wait(Until.hasObject(By.text(title)), 5000))
            device.findObject(By.text(title)).click()
            assertTrue("Missing child-page back action: " + title, device.wait(Until.hasObject(By.descContains("Back")), 5000))
            Thread.sleep(250) // Let the sequential fade-through place the child page.
            device.pressBack()
            assertTrue("Child did not return to settings root", device.wait(Until.gone(By.descContains("Back")), 5000))
            Thread.sleep(250)
        }
        navigate("Home")

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        assertFalse("Unused Aria2c executable still packaged", File(nativeDir, "libaria2c.so").exists())
        val runtime = File(context.noBackupFilesDir, "youtubedl-android")
        val pythonHome = File(runtime, "packages/python/usr")
        val ffmpegLib = File(runtime, "packages/ffmpeg/usr/lib")
        val ytdlp = File(runtime, "yt-dlp/yt-dlp")
        val deadline = SystemClock.elapsedRealtime() + 30000
        while ((!ytdlp.exists() || !File(pythonHome, "lib/libpython3.12.so.1.0").exists() ||
                !File(ffmpegLib, "libavcodec.so.61.19.101").exists()) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("Python runtime was stripped", File(pythonHome, "lib/libpython3.12.so.1.0").exists())
        assertTrue("FFmpeg runtime was stripped", File(ffmpegLib, "libavcodec.so.61.19.101").exists())
        assertTrue("yt-dlp resource was stripped", ytdlp.exists())
        assertFalse("Cold extraction unexpectedly installed Aria2c", File(runtime, "packages/aria2c").exists())
        if (InstrumentationRegistry.getArguments().getString("skipNativeExecution") == "true") {
            println("SIZE_SMOKE_ARM64_UI_OK history, navigation, cold extraction, no Aria2c")
            return
        }
        val work = File(context.cacheDir, "size-smoke").apply { mkdirs() }
        fun runNative(vararg args: String): String {
            val pb = ProcessBuilder(args.toList()).directory(work).redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", File(pythonHome, "lib").path + ":" + ffmpegLib.path + ":" + nativeDir.path)
                put("PYTHONHOME", pythonHome.path)
                put("SSL_CERT_FILE", File(pythonHome, "etc/tls/cert.pem").path)
                put("TMPDIR", context.cacheDir.path)
                put("PATH", (get("PATH") ?: "") + ":" + nativeDir.path)
            }
            val outputFile = File(work, "command-output.txt")
            pb.redirectOutput(outputFile)
            val process = pb.start()
            val done = process.waitFor(45, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            val output = outputFile.readText()
            assertTrue("Native command timed out: " + args.joinToString(" "), done)
            assertEquals("Native command failed: " + output, 0, process.exitValue())
            return output
        }
        val python = File(nativeDir, "libpython.so").path
        val ffmpeg = File(nativeDir, "libffmpeg.so").path
        val ffprobe = File(nativeDir, "libffprobe.so").path
        assertTrue(runNative(python, "--version").contains("Python 3."))
        assertTrue(runNative(python, ytdlp.path, "--version").trim().isNotEmpty())
        val input = File(work, "input.mp4")
        runNative(ffmpeg, "-y", "-f", "lavfi", "-i", "testsrc=size=96x64:rate=6",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1",
            "-c:v", "mpeg4", "-c:a", "aac", input.path)
        assertTrue(input.length() > 1000)
        val mp3 = File(work, "audio.mp3")
        runNative(ffmpeg, "-y", "-i", input.path, "-vn", "-c:a", "libmp3lame", mp3.path)
        val gif = File(work, "preview.gif")
        runNative(ffmpeg, "-y", "-i", input.path, "-vf", "fps=4,scale=64:-1", gif.path)
        val muted = File(work, "muted.mp4")
        runNative(ffmpeg, "-y", "-i", input.path, "-an", "-c:v", "copy", muted.path)
        assertTrue(mp3.length() > 1000)
        assertTrue(gif.length() > 1000)
        assertTrue(runNative(ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", muted.path).trim() == "video")

        // Verify yt-dlp's built-in downloader still works without optional Aria2c.
        val socket = ServerSocket(0)
        val running = AtomicBoolean(true)
        val server = Thread {
            while (running.get()) try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: ""
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = input.readBytes()
                    val out = connection.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                    if (!request.startsWith("HEAD")) out.write(body)
                    out.flush()
                }
            } catch (_: Exception) { if (!running.get()) break }
        }.apply { start() }
        try {
            val downloaded = File(work, "downloaded.mp4")
            runNative(python, ytdlp.path, "--ignore-config", "--no-playlist", "--no-part", "--no-progress",
                "-o", downloaded.path, "http://127.0.0.1:" + socket.localPort + "/input.mp4")
            assertArrayEquals("Downloaded bytes differ", input.readBytes(), downloaded.readBytes())
        } finally {
            running.set(false)
            socket.close()
            server.join(2000)
        }
        println("SIZE_SMOKE_OK history, navigation, cold Python/FFmpeg/yt-dlp, MP3, GIF, mute, built-in HTTP download")
    }
}
