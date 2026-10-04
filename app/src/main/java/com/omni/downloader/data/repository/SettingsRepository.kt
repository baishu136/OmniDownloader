package com.omni.downloader.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.omni.downloader.data.model.RelaySite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("omni_downloader_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _proxyUrl = MutableStateFlow(prefs.getString(KEY_PROXY_URL, "") ?: "")
    val proxyUrl: StateFlow<String> = _proxyUrl.asStateFlow()

    private val _downloadPath = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_PATH, "") ?: "")
    val downloadPath: StateFlow<String> = _downloadPath.asStateFlow()

    private val _bilibiliCookie = MutableStateFlow(prefs.getString(KEY_BILIBILI_COOKIE, "") ?: "")
    val bilibiliCookie: StateFlow<String> = _bilibiliCookie.asStateFlow()

    private val _hasPromptedBilibiliLogin = MutableStateFlow(prefs.getBoolean(KEY_HAS_PROMPTED_BILIBILI_LOGIN, false))
    val hasPromptedBilibiliLogin: StateFlow<Boolean> = _hasPromptedBilibiliLogin.asStateFlow()

    private val _hasShownRelayCompatTip: MutableStateFlow<Boolean>
    val hasShownRelayCompatTip: StateFlow<Boolean>

    private val _relaySites: MutableStateFlow<List<RelaySite>>
    val relaySites: StateFlow<List<RelaySite>>

    private val _appLanguage: MutableStateFlow<String>
    val appLanguage: StateFlow<String>

    init {
        // 新版本配置规范迁移：彻底在软件配置内移除内置网页与旧预设
        val lastConfigVersion = prefs.getInt(KEY_APP_CONFIG_VERSION, 0)
        val initialSites: List<RelaySite>
        var compatTipShown = prefs.getBoolean(KEY_HAS_SHOWN_RELAY_COMPAT_TIP, false)

        if (lastConfigVersion < 39) {
            // 新版本配置生效：在软件配置中彻底移除旧的 relay_sites，并重置提示状态
            prefs.edit()
                .remove(KEY_RELAY_SITES)
                .remove(KEY_HAS_SHOWN_RELAY_COMPAT_TIP)
                .putInt(KEY_APP_CONFIG_VERSION, 39)
                .apply()
            initialSites = emptyList()
            compatTipShown = false
        } else {
            val savedSitesStr = prefs.getString(KEY_RELAY_SITES, null)
            initialSites = if (!savedSitesStr.isNullOrBlank()) {
                try {
                    val decoded = json.decodeFromString<List<RelaySite>>(savedSitesStr)
                    // 防御性过滤，确保无任何旧内置网页残留
                    val legacyPresetIds = setOf("snapany_bili", "x2twitter", "greenvideo", "twittersaver", "snapany_tiktok")
                    decoded.filter { site ->
                        site.id !in legacyPresetIds &&
                        !site.url.contains("snapany.com", ignoreCase = true) &&
                        !site.url.contains("x2twitter.com", ignoreCase = true) &&
                        !site.url.contains("greenvideo.cc", ignoreCase = true) &&
                        !site.url.contains("twittersaver.net", ignoreCase = true) &&
                        !site.name.contains("SnapAny", ignoreCase = true) &&
                        !site.name.contains("X2Twitter", ignoreCase = true) &&
                        !site.name.contains("GreenVideo", ignoreCase = true) &&
                        !site.name.contains("TwitterSaver", ignoreCase = true)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

        _hasShownRelayCompatTip = MutableStateFlow(compatTipShown)
        hasShownRelayCompatTip = _hasShownRelayCompatTip.asStateFlow()

        _relaySites = MutableStateFlow(initialSites)
        relaySites = _relaySites.asStateFlow()

        val saved = prefs.getString(KEY_APP_LANGUAGE, null)
        val initial = if (saved.isNullOrBlank() || saved == "system") {
            getDefaultSystemLanguage()
        } else {
            saved
        }
        _appLanguage = MutableStateFlow(initial)
        appLanguage = _appLanguage.asStateFlow()

        // 启动时自动注入保存的 B站 凭证
        com.omni.downloader.engine.BilibiliDirectExtractor.customUserCookie = _bilibiliCookie.value
    }

    fun setBilibiliCookie(cookie: String) {
        val clean = cookie.trim()
        _bilibiliCookie.value = clean
        prefs.edit().putString(KEY_BILIBILI_COOKIE, clean).apply()
        com.omni.downloader.engine.BilibiliDirectExtractor.customUserCookie = clean
    }

    fun clearBilibiliCookie() {
        _bilibiliCookie.value = ""
        prefs.edit().remove(KEY_BILIBILI_COOKIE).apply()
        com.omni.downloader.engine.BilibiliDirectExtractor.customUserCookie = ""
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

    fun setHasPromptedBilibiliLogin(prompted: Boolean = true) {
        _hasPromptedBilibiliLogin.value = prompted
        prefs.edit().putBoolean(KEY_HAS_PROMPTED_BILIBILI_LOGIN, prompted).apply()
    }

    fun setHasShownRelayCompatTip(shown: Boolean = true) {
        _hasShownRelayCompatTip.value = shown
        prefs.edit().putBoolean(KEY_HAS_SHOWN_RELAY_COMPAT_TIP, shown).apply()
    }

    fun setAppLanguage(language: String) {
        val clean = language.trim()
        _appLanguage.value = clean
        prefs.edit().putString(KEY_APP_LANGUAGE, clean).apply()
    }

    fun addRelaySite(site: RelaySite) {
        val current = _relaySites.value.toMutableList()
        current.removeAll { it.id == site.id || it.url == site.url }
        current.add(site)
        _relaySites.value = current
        saveRelaySites(current)
    }

    fun removeRelaySite(siteId: String) {
        val current = _relaySites.value.filter { it.id != siteId }
        _relaySites.value = current
        saveRelaySites(current)
    }

    private fun saveRelaySites(sites: List<RelaySite>) {
        try {
            if (sites.isEmpty()) {
                prefs.edit().remove(KEY_RELAY_SITES).apply()
            } else {
                val jsonStr = json.encodeToString(sites)
                prefs.edit().putString(KEY_RELAY_SITES, jsonStr).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val KEY_PROXY_URL = "key_proxy_url"
        private const val KEY_DOWNLOAD_PATH = "key_download_path"
        private const val KEY_BILIBILI_COOKIE = "key_bilibili_cookie"
        private const val KEY_HAS_PROMPTED_BILIBILI_LOGIN = "key_has_prompted_bilibili_login"
        private const val KEY_APP_LANGUAGE = "key_app_language"
        private const val KEY_RELAY_SITES = "key_relay_sites"
        private const val KEY_HAS_SHOWN_RELAY_COMPAT_TIP = "key_has_shown_relay_compat_tip"
        private const val KEY_APP_CONFIG_VERSION = "key_app_config_version"

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
