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

    private val _relaySites: MutableStateFlow<List<RelaySite>>
    val relaySites: StateFlow<List<RelaySite>>

    private val _appLanguage: MutableStateFlow<String>
    val appLanguage: StateFlow<String>

    init {
        val savedSitesStr = prefs.getString(KEY_RELAY_SITES, null)
        val initialSites = if (!savedSitesStr.isNullOrBlank()) {
            try {
                json.decodeFromString<List<RelaySite>>(savedSitesStr)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
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
            val jsonStr = json.encodeToString(sites)
            prefs.edit().putString(KEY_RELAY_SITES, jsonStr).apply()
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

        /**
         * 预设常用第三方中转解析站点
         */
        val DEFAULT_PRESET_RELAY_SITES = listOf(
            RelaySite(
                id = "snapany_bili",
                name = "SnapAny (哔哩哔哩)",
                url = "https://snapany.com/zh/bilibili",
                iconUrl = "https://icon.horse/icon/snapany.com"
            ),
            RelaySite(
                id = "x2twitter",
                name = "X2Twitter",
                url = "https://x2twitter.com/zh-cn3",
                iconUrl = "https://icon.horse/icon/x2twitter.com"
            ),
            RelaySite(
                id = "greenvideo",
                name = "GreenVideo",
                url = "https://greenvideo.cc/",
                iconUrl = "https://icon.horse/icon/greenvideo.cc"
            ),
            RelaySite(
                id = "twittersaver",
                name = "TwitterSaver",
                url = "https://twittersaver.net/zh-cn",
                iconUrl = "https://icon.horse/icon/twittersaver.net"
            ),
            RelaySite(
                id = "snapany_tiktok",
                name = "SnapAny (TikTok)",
                url = "https://snapany.com/zh/tiktok",
                iconUrl = "https://icon.horse/icon/snapany.com"
            )
        )

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
