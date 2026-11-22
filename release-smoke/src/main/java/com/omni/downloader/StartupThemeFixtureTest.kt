package com.omni.downloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

/** Configure an isolated QA installation before a black-box cold-launch recording. */
class StartupThemeFixtureTest {
    @Test fun configureTheme() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("mode") ?: "system"
        require(mode in setOf("system", "light", "dark"))
        val context = instrument.targetContext
        val prefs = context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)
        val previous = prefs.getString("key_theme_mode", "system")
        assertTrue(prefs.edit().putString("key_theme_mode", mode)
            .putBoolean("key_has_prompted_bilibili_login", true).commit())
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        val device = UiDevice.getInstance(instrument)
        assertTrue(device.wait(Until.hasObject(By.text("OmniDownloader")), 15000))
        device.waitForIdle()
        instrument.waitForIdleSync()
        instrument.sendStatus(0, Bundle().apply { putString("previousTheme", previous) })
    }
}
