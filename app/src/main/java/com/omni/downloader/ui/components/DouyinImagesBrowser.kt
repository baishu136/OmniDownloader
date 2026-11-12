package com.omni.downloader.ui.components

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.downloader.data.repository.SessionRepository
import com.omni.downloader.engine.LinkNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import org.json.JSONTokener
import org.json.JSONObject

/** Visible, user-triggered first-party extraction; no Javascript interface or CAPTCHA solving. */
@Composable fun DouyinImagesBrowser(startUrl: String, onDismiss: () -> Unit, onCaptured: (String, String) -> Unit) =
    DouyinContentBrowser(startUrl, false, onDismiss, onCaptured)

@Composable fun DouyinVideoBrowser(startUrl: String, onDismiss: () -> Unit, onCaptured: (String, String) -> Unit,
    autoContinue: Boolean = false) = DouyinContentBrowser(startUrl, true, onDismiss, onCaptured, autoContinue)

@Composable private fun DouyinContentBrowser(startUrl: String, video: Boolean, onDismiss: () -> Unit, onCaptured: (String, String) -> Unit,
    autoContinue: Boolean = false) {
    val context = LocalContext.current
    val sessions = remember(context) { SessionRepository.getInstance(context) }
    val profiles by sessions.profiles.collectAsState()
    val enabled = profiles[LinkNormalizer.PLATFORM_DOUYIN]?.cookieEnabled != false
    val profile = remember(startUrl, context) { DouyinBrowserProfile.current(context) }
    val initialSession = remember(startUrl) { sessions.requestGeneration(LinkNormalizer.PLATFORM_DOUYIN) }
    val id = remember(startUrl) { LinkNormalizer.contentId(startUrl, LinkNormalizer.PLATFORM_DOUYIN) }
    val kind = if (video) "视频" else "图文"
    val canonical = "https://www.douyin.com/${if (video) "video" else "note"}/$id"
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<LoginBrowserController?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savingSession by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("请完成登录或平台验证，打开目标${kind}后点击提取。") }
    val deliver: suspend (JSONObject) -> Unit = { item ->
        savingSession = true
        try {
            if (enabled && video && profile != null) {
                profile.cookies.getCookie(canonical)?.takeIf { it.isNotBlank() }?.let {
                    sessions.importCookieHeader(LinkNormalizer.PLATFORM_DOUYIN, it).getOrThrow()
                }
            }
            onCaptured(item.toString(), canonical)
        } catch (failure: Exception) {
            savingSession = false
            throw failure
        }
    }
    LaunchedEffect(profiles[LinkNormalizer.PLATFORM_DOUYIN]) {
        if (!savingSession && sessions.requestGeneration(LinkNormalizer.PLATFORM_DOUYIN) != initialSession) onDismiss()
    }
    val currentDeliver by rememberUpdatedState(deliver)
    LaunchedEffect(browser, autoContinue, enabled) {
        if (!video || !autoContinue || !enabled || id == null) return@LaunchedEffect
        notice = "请完成平台验证；目标视频就绪后会自动继续提取。"
        var readyAt = 0L
        var signature = ""
        var changedAt = 0L
        while (currentCoroutineContext().isActive) {
            delay(500)
            val web = browser?.current ?: continue
            if (busy || LinkNormalizer.contentId(web.url.orEmpty(), LinkNormalizer.PLATFORM_DOUYIN) != id) continue
            val state = web.evaluateValue(DOUYIN_PAGE_STATE)?.let { runCatching { JSONObject(it).optString("status") }.getOrNull() }
            if (state != "normal") { readyAt = 0L; continue }
            web.evaluateValue(DouyinVideoBrowserScript.extract(id, requestDetail = false))
            val output = web.evaluateValue("window.__omniVideoResult || null")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            if (!output.optBoolean("ok")) continue
            val item = output.getJSONObject("item")
            val metadata = withContext(Dispatchers.Default) { com.omni.downloader.engine.DouyinVideoParser.parseItem(item.toString(), canonical) }
            val now = android.os.SystemClock.elapsedRealtime()
            if (readyAt == 0L) readyAt = now
            val next = metadata.availableVideoFormats.toString()
            if (signature != next) { signature = next; changedAt = now }
            val wait = if (com.omni.downloader.engine.DouyinQualityPolicy.needsPage(metadata)) 15_000 else 2000
            if (now - readyAt >= wait && now - changedAt >= 1200) {
                busy = true
                try { currentDeliver(item) }
                catch (e: Exception) { notice = com.omni.downloader.engine.LogSanitizer.sanitize(e.message.orEmpty()); busy = false }
                return@LaunchedEffect
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("抖音${kind}浏览器", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (!enabled) {
                    Text("抖音 Cookie 已禁用。浏览器辅助需要使用网页登录状态。", Modifier.padding(16.dp))
                    Button(shape = MaterialTheme.shapes.medium, onClick = { sessions.setCookieEnabled(LinkNormalizer.PLATFORM_DOUYIN, true) }) { Text("开启 Cookie 并继续") }
                } else if (id == null) Text("尚未确认目标作品ID，请返回重新解析分享链接。", Modifier.padding(16.dp))
                else {
                    Text(notice, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
                        LoginBrowserController(it, LinkNormalizer.PLATFORM_DOUYIN, canonical, true,
                            onProgress = {}, onPageFinished = {}, onNotice = { value -> notice = value },
                            documentStartScript = "window.__omniCaptureId='$id';\n" + it.assets.open("browser/douyin-${if (video) "video" else "images"}-capture.js").bufferedReader().use { file -> file.readText() },
                            browserProfile = profile
                        ).also { browser = it }.container
                    }, onRelease = { browser?.close(); browser = null })
                    Button(shape = MaterialTheme.shapes.medium, enabled = !busy, modifier = Modifier.padding(12.dp), onClick = {
                        val web = browser?.current ?: return@Button
                        if (LinkNormalizer.platformOf(web.url.orEmpty()) != LinkNormalizer.PLATFORM_DOUYIN ||
                            LinkNormalizer.contentId(web.url.orEmpty(), LinkNormalizer.PLATFORM_DOUYIN) != id) {
                            notice = "当前页面不是目标作品，请返回目标${kind}页后提取。"; return@Button
                        }
                        busy = true
                        scope.launch {
                            try {
                                web.evaluateValue(if (video) DouyinVideoBrowserScript.extract(id) else DouyinBrowserScript.extract(id))
                                var result: String? = null
                                repeat(24) {
                                    if (result == null) {
                                        delay(500)
                                        result = web.evaluateValue("window.${if (video) "__omniVideoResult" else "__omniImageResult"} || null")
                                    }
                                }
                                if (LinkNormalizer.contentId(web.url.orEmpty(), LinkNormalizer.PLATFORM_DOUYIN) != id) error("页面已离开目标作品，请返回后重试")
                                val json = JSONObject(result ?: error("提取超时，请确认目标${kind}已正常加载"))
                                if (!json.optBoolean("ok")) error("网页仍未提供目标${kind}，请完成验证并播放目标作品后重试")
                                val item = json.getJSONObject("item")
                                check(item.optString("aweme_id", item.optString("awemeId")) == id) { "目标作品ID不匹配" }
                                currentDeliver(item)
                            } catch (e: Exception) { notice = com.omni.downloader.engine.LogSanitizer.sanitize(e.message.orEmpty()) }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "正在读取目标${kind}…" else "提取此作品${kind}") }
                }
            }
        }
    }
}

private suspend fun WebView.evaluateValue(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { value ->
        if (continuation.isActive) continuation.resume(runCatching {
            if (value.length > 8_388_608 || value == "null") null else JSONTokener(value).nextValue() as? String
        }.getOrNull())
    }
}

internal object DouyinBrowserScript {
    fun extract(id: String): String {
        require(Regex("\\d{10,24}").matches(id))
        return """
        (() => {
          window.__omniImageResult = null;
          const target = '$id';
          const find = (value, depth=0) => {
            if(!value || typeof value !== 'object' || depth>25) return null;
            if(String(value.aweme_id || value.awemeId || '')===target && (value.images?.length || value.image_post_info?.images?.length)) return value;
            for(const child of Object.values(value)) { const item=find(child,depth+1);if(item)return item; }
            return null;
          };
          const finish = item => {
            window.__omniImageResult = JSON.stringify(item ? {ok:true,item:{aweme_id:target,desc:item.desc,author:{nickname:item.author?.nickname || ''},images:item.images || item.image_post_info?.images}} : {ok:false});
          };
          let state=window.__omniCapturedImages || window._ROUTER_DATA;
          try { if(!state) state=JSON.parse(decodeURIComponent(document.getElementById('RENDER_DATA')?.textContent || '{}')); } catch(e) {}
          const item=find(state);
          if(item)finish(item);
          else fetch('/aweme/v1/web/aweme/detail/?aid=6383&device_platform=webapp&aweme_id='+target,{credentials:'same-origin'})
            .then(r=>r.ok?r.json():null).then(data=>finish(find(data))).catch(()=>finish(null));
          return null;
        })()
        """.trimIndent()
    }
}
