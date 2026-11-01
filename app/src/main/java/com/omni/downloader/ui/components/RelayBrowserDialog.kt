package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.data.model.RelaySite

/** Optional manual verification/navigation. Media capture stays inside parsing. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RelayBrowserDialog(
    site: RelaySite,
    initialVideoUrl: String = "",
    onDismiss: () -> Unit,
    onCapturedDownload: (directUrl: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val dismiss by rememberUpdatedState(onDismiss)
    val captured by rememberUpdatedState(onCapturedDownload)
    val session = remember(site.id, initialVideoUrl) {
        RelayCaptureSession("${site.name} 视频", { url, title -> captured(url, title); dismiss() })
    }
    var view by remember(session) { mutableStateOf<WebView?>(null) }
    var cleanup by remember(session) { mutableStateOf<(() -> Unit)?>(null) }
    var progress by remember(session) { mutableIntStateOf(0) }
    var title by remember(session) { mutableStateOf(site.name) }
    var currentUrl by remember(session) { mutableStateOf(site.url) }
    var canGoBack by remember(session) { mutableStateOf(false) }
    fun back() { if (view?.canGoBack() == true) view?.goBack() else dismiss() }
    BackHandler { back() }
    DisposableEffect(session) {
        onDispose {
            session.close(); cleanup?.invoke()
            view?.let { it.stopLoading(); it.removeJavascriptInterface("OmniBridge"); it.destroy() }
            view = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            Surface(shadowElevation = 3.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { back() }) { Icon(if (canGoBack) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Close, "返回") }
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(Uri.parse(currentUrl).host.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (initialVideoUrl.isNotBlank()) IconButton(onClick = { view?.let { RelayAutomation.fill(it) } }) { Icon(Icons.Outlined.Bolt, "填入链接") }
                        IconButton(onClick = { view?.reload() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                        IconButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }
                            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "无法打开外部浏览器", Toast.LENGTH_SHORT).show() }
                        }) { Icon(Icons.Outlined.OpenInBrowser, "在外部浏览器打开") }
                    }
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }) { padding ->
            key(session) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).also { web ->
                        view = web
                        cleanup = RelayAutomation.bind(web, session, initialVideoUrl, false,
                            onLocation = { currentUrl = it; canGoBack = web.canGoBack() },
                            onTitle = { title = it }, onProgress = { progress = it })
                        web.settings.setSupportZoom(true); web.settings.builtInZoomControls = true; web.settings.displayZoomControls = false
                        web.loadUrl(RelayAutomation.initialUrl(site.url, initialVideoUrl))
                    }
                }, modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding())
            }
        }
    }
}
