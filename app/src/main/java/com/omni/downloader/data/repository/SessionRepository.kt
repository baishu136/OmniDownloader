package com.omni.downloader.data.repository

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 平台会话仓库。
 *
 * - Cookie 文本只以 Android Keystore 管理的 AES/GCM 密文落地；
 * - UI 只能读取“是否已导入 / 是否需要重新验证 / 导入时间”，拿不到 Cookie 值；
 * - yt-dlp 使用时生成应用私有目录下的临时 Netscape 文件，任务结束后删除；
 * - 原生 HTTP 使用内存中的 Cookie 请求头，按平台域生成，不跨站发送。
 */
class SessionRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _profiles = MutableStateFlow<Map<String, SessionInfo>>(emptyMap())
    val profiles: StateFlow<Map<String, SessionInfo>> = _profiles.asStateFlow()

    init {
        reload()
    }

    fun info(platform: String): SessionInfo? = synchronized(lock) { _profiles.value[platform] }

    fun isCookieEnabled(platform: String): Boolean = synchronized(lock) {
        prefs.getBoolean("cookie_enabled_${platform.hashCode()}", true)
    }

    /** An off/on cycle must never resurrect a snapshot made under an older policy. */
    fun requestGeneration(platform: String): String = synchronized(lock) {
        "${generation(platform)}:${prefs.getLong("cookie_revision_${platform.hashCode()}", 0)}:${isCookieEnabled(platform)}"
    }

    fun setCookieEnabled(platform: String, enabled: Boolean) {
        require(platform in SessionPlatforms.all)
        synchronized(lock) {
            if (isCookieEnabled(platform) == enabled) return
            prefs.edit().putBoolean("cookie_enabled_${platform.hashCode()}", enabled)
                .putLong("cookie_revision_${platform.hashCode()}", prefs.getLong("cookie_revision_${platform.hashCode()}", 0) + 1)
                .remove("validation_${platform.hashCode()}").apply()
        }
        reload()
    }

    /** 供原生 OkHttp 使用的 Cookie 请求头；仅返回未过期项。 */
    fun cookieHeader(platform: String, url: String = "https://${SessionPlatforms.domainsFor(platform).firstOrNull() ?: "invalid"}/"): String? = synchronized(lock) {
        if (!isCookieEnabled(platform)) return@synchronized null
        val stored = readStored(platform) ?: return@synchronized null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return@synchronized null
        val host = uri.host?.lowercase() ?: return@synchronized null
        val rows = NetscapeCookies.parse(stored.netscape, SessionPlatforms.domainsFor(platform)).filter { row ->
            (host == row.domain || (row.includeSubdomains && host.endsWith(".${row.domain}"))) &&
                (!row.secure || uri.scheme == "https") && (uri.path.orEmpty().ifBlank { "/" }).startsWith(row.path)
        }
        NetscapeCookies.header(rows).takeIf { it.isNotBlank() }
    }

    /** Must be called on the WebView UI thread before loading a platform page. */
    fun restoreBrowserCookies(platform: String, onRestored: () -> Unit = {}) =
        restoreBrowserCookies(platform, android.webkit.CookieManager.getInstance(), onRestored)

    fun restoreBrowserCookies(platform: String, manager: android.webkit.CookieManager, onRestored: () -> Unit = {}) {
        if (!isCookieEnabled(platform)) { onRestored(); return }
        val rows = synchronized(lock) { readStored(platform)?.let { NetscapeCookies.parse(it.netscape, SessionPlatforms.domainsFor(platform)) } }.orEmpty()
        BrowserCookieRestorer.restore(rows, manager, onRestored)
    }

    fun recordAccountValidation(platform: String, accountId: String, accountName: String, verified: Boolean) {
        synchronized(lock) {
            val data = JSONObject().put("accountId", accountId).put("accountName", accountName)
                .put("verified", verified).put("verifiedAt", System.currentTimeMillis()).put("generation", generation(platform))
            prefs.edit().putString("validation_${platform.hashCode()}", SessionCrypto.encrypt(data.toString())).apply()
        }
    }

    fun generation(platform: String): Int = synchronized(lock) {
        readStored(platform)?.generation ?: 0
    }

    /**
     * 导入 Netscape Cookies 文件。只保留与目标平台域相关的行，
     * 不把其它站点 Cookie 复制进应用存储。
     */
    suspend fun importNetscapeFile(platform: String, uri: Uri): Result<SessionInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size <= MAX_COOKIE_FILE_BYTES) { "Cookies 文件过大" }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("无法读取 Cookies 文件")

                val rows = NetscapeCookies.parse(text, SessionPlatforms.domainsFor(platform))
                require(rows.isNotEmpty()) { "文件中没有该平台的有效 Cookie" }

                val header = NetscapeCookies.header(rows)
                require(header.isNotBlank()) { "Cookie 已全部过期" }

                val stored = StoredSession(
                    platform = platform,
                    netscape = NetscapeCookies.serialize(rows),
                    header = header,
                    importedAt = System.currentTimeMillis(),
                    status = SessionStatus.IMPORTED,
                    generation = (readStored(platform)?.generation ?: 0) + 1
                )
                writeStored(stored)
                reload()
                _profiles.value.getValue(platform)
            }
        }

    /**
     * 导入内置浏览器捕获的 Cookie 请求头。
     * WebView 只能提供 name=value 头，无法还原完整 domain/path/expiry；
     * 这里按平台主域生成一年期会话项，并在 UI 中说明其局限。
     */
    suspend fun importCookieHeader(platform: String, header: String): Result<SessionInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pairs = CookieHeaderParser.parse(header)
                require(pairs.isNotEmpty()) { "未获取到有效 Cookie" }
                val domain = SessionPlatforms.domainsFor(platform).firstOrNull()
                    ?: error("未知平台")
                val expiresAt = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
                val rows = pairs.map { (name, value) ->
                    NetscapeCookies.CookieRow(
                        domain = domain,
                        includeSubdomains = true,
                        path = "/",
                        secure = true,
                        expiresAt = expiresAt,
                        name = name,
                        value = value,
                        httpOnly = true
                    )
                }
                val stored = StoredSession(
                    platform = platform,
                    netscape = NetscapeCookies.serialize(rows),
                    header = rows.joinToString("; ") { "${it.name}=${it.value}" },
                    importedAt = System.currentTimeMillis(),
                    status = SessionStatus.IMPORTED,
                    generation = (readStored(platform)?.generation ?: 0) + 1
                )
                writeStored(stored)
                reload()
                _profiles.value.getValue(platform)
            }
        }

    fun clear(platform: String) {
        if (platform == "哔哩哔哩") SettingsRepository.getInstance(appContext).clearBilibiliCookie()
        synchronized(lock) {
            prefs.edit().remove(keyFor(platform)).remove("validation_${platform.hashCode()}")
                .putLong("cookie_revision_${platform.hashCode()}", prefs.getLong("cookie_revision_${platform.hashCode()}", 0) + 1).apply()
        }
        reload()
    }

    fun markStatus(platform: String, status: SessionStatus) {
        synchronized(lock) {
            val stored = readStored(platform) ?: return
            if (stored.status == status) return
            writeStored(stored.copy(status = status))
        }
        reload()
    }

    /** 为 yt-dlp 生成临时 Netscape 文件；调用方必须在任务结束后删除。 */
    fun tempCookieFile(platform: String): File? {
        if (!isCookieEnabled(platform)) return null
        val stored = synchronized(lock) { readStored(platform) } ?: return null
        // Versions <= 1.7.1 saved domain cookies without their leading dot.
        // Re-serialize at the engine boundary, including already encrypted profiles;
        // never copy the legacy text or silently retry as an anonymous account.
        val rows = NetscapeCookies.parse(stored.netscape, SessionPlatforms.domainsFor(platform))
        require(rows.isNotEmpty()) { "[AUTH_REQUIRED] 已保存的会话没有有效 Cookie，请重新登录或导入" }
        val dir = File(appContext.cacheDir, "session").apply { mkdirs() }
        val file = File.createTempFile("cookies_${platform.hashCode()}_", ".txt", dir)
        try {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.writeText(NetscapeCookies.serialize(rows))
            return file
        } catch (error: Exception) {
            file.delete()
            throw IllegalStateException("[COOKIE_INVALID] 无法生成下载所需的会话文件", error)
        }
    }

    fun cleanupTempFiles() {
        runCatching { File(appContext.cacheDir, "session").deleteRecursively() }
    }

    /** 启动时清理上次异常退出留下的临时 Cookie。 */
    fun cleanupStaleTempFiles() = cleanupTempFiles()

    private fun reload() {
        val map = synchronized(lock) {
            SessionPlatforms.all.associateWith { platform ->
                val stored = readStored(platform)
                SessionInfo(
                    platform = platform,
                    cookieEnabled = isCookieEnabled(platform),
                    importedAt = stored?.importedAt ?: 0L,
                    status = stored?.status ?: SessionStatus.MISSING,
                    cookieCount = stored?.let {
                        it.netscape.lineSequence().count { line ->
                            line.isNotBlank() && (!line.startsWith("#") || line.startsWith("#HttpOnly_"))
                        }
                    } ?: 0
                )
            }
        }
        _profiles.value = map
    }

    private fun readStored(platform: String): StoredSession? {
        val encrypted = prefs.getString(keyFor(platform), null) ?: return null
        val plain = SessionCrypto.decrypt(encrypted) ?: run {
            // 密钥失效或数据损坏：删除不可用条目，避免反复失败
            prefs.edit().remove(keyFor(platform)).remove("validation_${platform.hashCode()}").apply()
            return null
        }
        return runCatching {
            val json = JSONObject(plain)
            StoredSession(
                platform = platform,
                netscape = json.optString("netscape"),
                header = json.optString("header"),
                importedAt = json.optLong("importedAt", 0L),
                status = runCatching {
                    SessionStatus.valueOf(json.optString("status", SessionStatus.IMPORTED.name))
                }.getOrDefault(SessionStatus.IMPORTED),
                generation = json.optInt("generation", 1)
            )
        }.getOrNull()
    }

    private fun writeStored(stored: StoredSession) {
        val json = JSONObject().apply {
            put("netscape", stored.netscape)
            put("header", stored.header)
            put("importedAt", stored.importedAt)
            put("status", stored.status.name)
            put("generation", stored.generation)
        }
        val encrypted = SessionCrypto.encrypt(json.toString())
        prefs.edit().putString(keyFor(stored.platform), encrypted).apply()
    }

    private fun keyFor(platform: String): String = "session_${platform.hashCode()}"

    private data class StoredSession(
        val platform: String,
        val netscape: String,
        val header: String,
        val importedAt: Long,
        val status: SessionStatus,
        val generation: Int
    )

    companion object {
        private const val PREFS_NAME = "omni_sessions_v1"
        private const val MAX_COOKIE_FILE_BYTES = 1024 * 1024

        @Volatile
        private var instance: SessionRepository? = null

        internal fun currentOrNull(): SessionRepository? = instance

        fun getInstance(context: Context): SessionRepository =
            instance ?: synchronized(this) {
                instance ?: SessionRepository(context).also { instance = it }
            }
    }
}

enum class SessionStatus {
    MISSING,
    IMPORTED,
    NEEDS_AUTH
}

data class SessionInfo(
    val platform: String,
    val importedAt: Long,
    val status: SessionStatus,
    val cookieCount: Int,
    val cookieEnabled: Boolean = true
)

object SessionPlatforms {
    const val YOUTUBE = "YouTube"
    const val X = "X (Twitter)"
    const val XHS = "小红书"
    const val HEYBOX = "小黑盒"
    const val PIXIV = "Pixiv"

    val all = listOf(YOUTUBE, X, XHS, HEYBOX, PIXIV, "抖音", "哔哩哔哩", "Pinterest", "Instagram", "Facebook", "TikTok", "快手")

    fun domainsFor(platform: String): Set<String> = when (platform) {
        YOUTUBE -> setOf("youtube.com", "google.com", "youtu.be")
        X -> setOf("x.com", "twitter.com")
        XHS -> setOf("xiaohongshu.com")
        HEYBOX -> setOf("xiaoheihe.cn", "heybox.cn", "heybox.com")
        PIXIV -> setOf("pixiv.net")
        "抖音" -> setOf("douyin.com")
        "哔哩哔哩" -> setOf("bilibili.com")
        "Pinterest" -> setOf("pinterest.com")
        "Instagram" -> setOf("instagram.com")
        "Facebook" -> setOf("facebook.com")
        "TikTok" -> setOf("tiktok.com")
        "快手" -> setOf("kuaishou.com")
        else -> emptySet()
    }
}

/**
 * Netscape Cookies 解析与序列化。纯函数，便于单元测试。
 */
object NetscapeCookies {

    data class CookieRow(
        val domain: String,
        val includeSubdomains: Boolean,
        val path: String,
        val secure: Boolean,
        val expiresAt: Long,
        val name: String,
        val value: String,
        val httpOnly: Boolean = false
    ) {
        fun isExpired(now: Long): Boolean = expiresAt > 0 && expiresAt * 1000L < now

        fun toNetscapeLine(): String {
            val prefix = if (httpOnly) "#HttpOnly_" else ""
            // MozillaCookieJar requires TRUE iff the serialized domain starts with '.'.
            // Keep the internal domain dot-free for host matching and WebView restore.
            val host = domain.trimStart('.')
            val serializedDomain = if (includeSubdomains) ".$host" else host
            return buildString {
                append(prefix).append(serializedDomain).append('\t')
                append(if (includeSubdomains) "TRUE" else "FALSE").append('\t')
                append(path.ifBlank { "/" }).append('\t')
                append(if (secure) "TRUE" else "FALSE").append('\t')
                append(expiresAt).append('\t')
                append(name).append('\t')
                append(value)
            }
        }
    }

    fun parse(text: String, allowedDomains: Set<String>, now: Long = System.currentTimeMillis()): List<CookieRow> {
        if (allowedDomains.isEmpty()) return emptyList()
        val rows = linkedMapOf<String, CookieRow>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r')
            if (line.isBlank()) return@forEach
            val httpOnly = line.startsWith("#HttpOnly_")
            if (line.startsWith("#") && !httpOnly) return@forEach
            val payload = if (httpOnly) line.removePrefix("#HttpOnly_") else line
            val parts = payload.split('\t')
            if (parts.size != 7 || parts.any { it.contains('\r') || it.contains('\n') || it.contains('\u0000') }) return@forEach

            val domain = parts[0].trim().removePrefix(".").lowercase()
            if (!domainMatches(domain, allowedDomains)) return@forEach
            val name = parts[5].trim()
            val value = parts[6]
            if (name.isBlank()) return@forEach

            val expiresAt = parts[4].trim().toLongOrNull() ?: 0L
            // Netscape 的过期时间是秒，调用方传入毫秒
            if (expiresAt > 0 && expiresAt * 1000L < now) return@forEach

            val row = CookieRow(
                domain = domain,
                includeSubdomains = parts[1].equals("TRUE", ignoreCase = true),
                path = parts[2].ifBlank { "/" },
                secure = parts[3].equals("TRUE", ignoreCase = true),
                expiresAt = expiresAt,
                name = name,
                value = value,
                httpOnly = httpOnly
            )
            rows["${row.domain}|${row.path}|${row.name}"] = row
        }
        return rows.values.toList()
    }

    fun header(rows: List<CookieRow>): String =
        rows.joinToString("; ") { "${it.name}=${it.value}" }

    fun serialize(rows: List<CookieRow>): String =
        buildString {
            append("# Netscape HTTP Cookie File\n")
            rows.forEach { append(it.toNetscapeLine()).append('\n') }
        }

    private fun domainMatches(domain: String, allowed: Set<String>): Boolean =
        allowed.any { item -> domain == item || domain.endsWith(".$item") }
}

/** 解析 `name=value; name2=value2` 形式的 Cookie 请求头。 */
object CookieHeaderParser {
    fun parse(header: String): List<Pair<String, String>> {
        if (header.isBlank()) return emptyList()
        return header.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val index = trimmed.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
    }
}

/**
 * Android Keystore 支持的 AES/GCM 加密。密钥不可导出，随应用卸载失效。
 */
internal object SessionCrypto {

    private const val KEY_ALIAS = "omni_session_key_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String? = runCatching {
        val separator = payload.indexOf(':')
        require(separator > 0)
        val iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
