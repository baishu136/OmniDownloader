package com.omni.downloader.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.downloader.data.model.RelaySite
import kotlinx.coroutines.delay

/** The same bounded media capture pipeline as the browser, without visible controls. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SilentRelayEngine(
    site: RelaySite,
    videoUrl: String,
    onSuccess: (directUrl: String, title: String) -> Unit,
    onError: (errorMessage: String) -> Unit,
    onProgress: (statusMessage: String) -> Unit
) {
    val success by rememberUpdatedState(onSuccess)
    val error by rememberUpdatedState(onError)
    val progress by rememberUpdatedState(onProgress)
    val session = remember(site.id, videoUrl) {
        RelayCaptureSession("${site.name} 视频", { url, title -> success(url, title) }, { progress(it) }, { error(it) })
    }
    var view by remember(session) { mutableStateOf<WebView?>(null) }
    var cleanup by remember(session) { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(session) {
        delay(25000)
        if (!session.isCompleted) session.fail("中转站响应超时，可点击「窗口排查」完成验证或手动解析")
    }
    DisposableEffect(session) {
        onDispose {
            session.close(); cleanup?.invoke()
            view?.let { it.stopLoading(); it.removeJavascriptInterface("OmniBridge"); it.destroy() }
            view = null
        }
    }
    key(session) {
        Box(Modifier.size(360.dp, 640.dp).offset(x = (-9999).dp).alpha(0.001f)) {
            AndroidView(factory = { context ->
                WebView(context).also { web ->
                    view = web
                    cleanup = RelayAutomation.bind(web, session, videoUrl, autoSubmit = true)
                    web.loadUrl(RelayAutomation.initialUrl(site.url, videoUrl))
                }
            })
        }
    }
}