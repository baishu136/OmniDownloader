package com.omni.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
import android.annotation.SuppressLint
import android.webkit.CookieManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.ui.localization.LocalAppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 通用平台登录页：使用应用内 WebView 完成登录，再从 CookieManager 读取请求头。
 * WebView 只能提供 name=value 头，无法还原完整 domain/path/expiry，
 * 因此 SessionRepository 会按平台主域生成会话项，并在设置页说明该限制。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformLoginSheet(
    platform: String,
    onDismissRequest: () -> Unit,
    onCookieCaptured: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val config = remember(platform) { loginConfigFor(platform) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var captured by remember(platform) { mutableStateOf(false) }
    var desktop by rememberSaveable(platform) { mutableStateOf(BrowserIdentity.prefersDesktop(platform)) }
    var browser by remember { mutableStateOf<LoginBrowserController?>(null) }
    var notice by remember(platform) { mutableStateOf(if (platform == com.omni.downloader.data.repository.SessionPlatforms.PIXIV)
        "请使用能查看目标作品的 Pixiv 账号登录，完成后点击下方保存会话，再重新解析作品。" else "") }
    var currentHost by remember(platform) { mutableStateOf(android.net.Uri.parse(config.startUrl).host.orEmpty()) }
    BackHandler { if (browser?.goBack() != true) onDismissRequest() }

    fun captureIfLoggedIn(force: Boolean): Boolean {
        if (captured) return true
        val cookie = CookieManager.getInstance().getCookie(config.cookieUrl).orEmpty()
        if (!canCaptureLoginCookie(config, cookie, manual = force)) {
            if (force) notice = "尚未取得会话，请先完成网页登录后再保存"
            return false
        }
        CookieManager.getInstance().flush()
        captured = true
        onCookieCaptured(cookie)
        return true
    }

    LaunchedEffect(captured) {
        if (captured || !config.automaticCapture || config.loginMarkers.isEmpty()) return@LaunchedEffect
        while (isActive && !captured) {
            if (captureIfLoggedIn(force = false)) return@LaunchedEffect
            delay(1000)
        }
    }

    Dialog(
        onDismissRequest = { if (browser?.goBack() != true) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = platform,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentHost + if (desktop) " · 桌面网站" else " · 手机版",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = strings.cancel)
                    }
                }

                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { desktop = !desktop }) { Text(if (desktop) "切换手机版" else "切换桌面版") }
                    TextButton(onClick = { browser?.reloadStart() }) { Text("重新加载登录页") }
                }
                if (notice.isNotBlank()) Text(notice, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { context ->
                        LoginBrowserController(context, platform, config.startUrl, desktop,
                            onProgress = { progress -> loadProgress = progress / 100f; isLoading = progress < 100 },
                            onPageFinished = { captureIfLoggedIn(force = false) },
                            onNotice = { notice = it },
                            onLocation = { currentHost = android.net.Uri.parse(it).host.orEmpty() }
                        ).also { browser = it }.container
                    },
                    update = { browser?.setDesktop(desktop) },
                    onRelease = { browser?.close(); browser = null }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { captureIfLoggedIn(force = true) }) {
                        Text(if (!config.automaticCapture) "我已登录，保存会话" else strings.sessionLoginDone)
                    }
                }
            }
        }
    }
}
