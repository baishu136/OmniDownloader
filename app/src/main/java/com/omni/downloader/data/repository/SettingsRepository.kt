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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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
        // 新版本配置规范迁移：仅在首次升级到版本 40 时清理旧版内置固定 ID，绝不误伤用户保存的任何网站
        val lastConfigVersion = prefs.getInt(KEY_APP_CONFIG_VERSION, 0)
        val initialSites: List<RelaySite>
        val compatTipShown: Boolean

        val savedSitesStr = prefs.getString(KEY_RELAY_SITES, null)
        val rawSites = if (!savedSitesStr.isNullOrBlank()) {
            try {
                json.decodeFromString<List<RelaySite>>(savedSitesStr)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        } else {
            emptyList()
        }

        if (lastConfigVersion < 40) {
            // 一次性迁移清理：仅针对历史版本写死的固定 ID 进行剔除
            val legacyPresetIds = setOf("snapany_bili", "x2twitter", "greenvideo", "twittersaver", "snapany_tiktok")
            val filteredSites = rawSites.filter { site -> site.id !in legacyPresetIds }

            val editor = prefs.edit()
            if (filteredSites.isEmpty()) {
                editor.remove(KEY_RELAY_SITES)
                compatTipShown = false
                editor.remove(KEY_HAS_SHOWN_RELAY_COMPAT_TIP)
            } else {
                try {
                    editor.putString(KEY_RELAY_SITES, json.encodeToString(filteredSites))
                } catch (ignored: Exception) {}
                compatTipShown = prefs.getBoolean(KEY_HAS_SHOWN_RELAY_COMPAT_TIP, true)
                editor.putBoolean(KEY_HAS_SHOWN_RELAY_COMPAT_TIP, compatTipShown)
            }
            editor.putInt(KEY_APP_CONFIG_VERSION, 40).commit()
            initialSites = filteredSites
        } else {
            // 正常启动：完整信任并读取用户本地存储的所有网站，绝不执行任何关键字黑名单过滤！
            compatTipShown = prefs.getBoolean(KEY_HAS_SHOWN_RELAY_COMPAT_TIP, false)
            initialSites = rawSites
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
        current.removeAll {
            it.id == site.id || it.url.trim().equals(site.url.trim(), ignoreCase = true)
        }
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
                prefs.edit().remove(KEY_RELAY_SITES).commit()
            } else {
                val jsonStr = json.encodeToString(sites)
                prefs.edit().putString(KEY_RELAY_SITES, jsonStr).commit()
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
