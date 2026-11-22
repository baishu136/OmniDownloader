package com.omni.downloader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.omni.downloader.R

/** Read just the launch preference; avoid constructing settings, accounts or download services. */
internal object LaunchTheme {
    fun readMode(context: Context): String = context
        .getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)
        .getString("key_theme_mode", "system").let { normalize(it) }

    fun normalize(mode: String?): String = mode.takeIf { it in setOf("light", "dark") } ?: "system"

    fun startingStyle(mode: String): Int = when (normalize(mode)) {
        "light" -> R.style.Theme_OmniDownloader_Starting_Light
        "dark" -> R.style.Theme_OmniDownloader_Starting_Dark
        else -> R.style.Theme_OmniDownloader_Starting
    }

    fun contentStyle(mode: String): Int = when (normalize(mode)) {
        "light" -> R.style.Theme_OmniDownloader_Light
        "dark" -> R.style.Theme_OmniDownloader_Dark
        else -> R.style.Theme_OmniDownloader
    }

    fun persistForNextLaunch(activity: Activity, mode: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Persist a stable resource name in the OS without recreating the live Compose page.
            val style = if (normalize(mode) == "system") Resources.ID_NULL else startingStyle(mode)
            activity.splashScreen.setSplashScreenTheme(style)
        }
    }
}
