package com.omni.downloader.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)

    private val _proxyUrl = MutableStateFlow(prefs.getString(KEY_PROXY_URL, "") ?: "")
    val proxyUrl: StateFlow<String> = _proxyUrl.asStateFlow()

    private val _downloadPath = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_PATH, "") ?: "")
    val downloadPath: StateFlow<String> = _downloadPath.asStateFlow()

    private val _appLanguage: MutableStateFlow<String>
    val appLanguage: StateFlow<String>

    init {
        val saved = prefs.getString(KEY_APP_LANGUAGE, null)
        val initial = if (saved.isNullOrBlank() || saved == "system") {
            getDefaultSystemLanguage()
        } else {
            saved
        }
        _appLanguage = MutableStateFlow(initial)
        appLanguage = _appLanguage.asStateFlow()
    }

    fun setProxyUrl(url: String) {
        val clean = url.trim()
        _proxyUrl.value = clean
        prefs.edit().putString(KEY_PROXY_URL, clean).apply()
    }

    fun setDownloadPath(path: String) {
        val clean = path.trim()
        _downloadPath.value = clean
        prefs.edit().putString(KEY_DOWNLOAD_PATH, clean).apply()
    }

    fun resetDownloadPath() {
        _downloadPath.value = ""
        prefs.edit().remove(KEY_DOWNLOAD_PATH).apply()
    }

    fun setAppLanguage(language: String) {
        val clean = language.trim()
        _appLanguage.value = clean
        prefs.edit().putString(KEY_APP_LANGUAGE, clean).apply()
    }

    companion object {
        private const val KEY_PROXY_URL = "key_proxy_url"
        private const val KEY_DOWNLOAD_PATH = "key_download_path"
        private const val KEY_APP_LANGUAGE = "key_app_language"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getDefaultSystemLanguage(): String {
            val defaultLocale = Locale.getDefault()
            val lang = defaultLocale.language.lowercase()
            val country = defaultLocale.country.uppercase()
            val script = defaultLocale.script.lowercase()

            return when {
                lang == "zh" && (country == "TW" || country == "HK" || country == "MO" || script.contains("hant")) -> "zh-TW"
                lang == "zh" -> "zh-CN"
                lang == "ja" -> "ja"
                lang == "en" -> "en"
                else -> "zh-CN"
            }
        }

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
