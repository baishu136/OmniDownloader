package com.omni.downloader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.omni.downloader.ui.viewmodel.MainViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.omni.downloader.ui.components.ImageInputPanel
import com.omni.downloader.data.model.ImagePost
import com.omni.downloader.data.model.ImageResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

internal class HomeImageResults(
    val post: ImagePost?, val selected: Set<String>, val error: String,
    val onSelection: (Set<String>) -> Unit, val onPreview: (Int) -> Unit,
    val onSave: (Boolean) -> Unit, val imageModel: (Int) -> Any,
    val onBrowser: (() -> Unit)?
)

@Composable internal fun rememberHomeImageResults(viewModel: MainViewModel, active: Boolean): HomeImageResults {
    val post by viewModel.imagePost.collectAsStateWithLifecycle()
    val error by viewModel.imageError.collectAsStateWithLifecycle()
    val browserUrl by viewModel.imageBrowserUrl.collectAsStateWithLifecycle()
    var selected by remember(post) { mutableStateOf(post?.resources?.map { it.id }?.toSet().orEmpty()) }
    var preview by remember(post) { mutableStateOf<Int?>(null) }
    var browser by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sessions = remember(context) { com.omni.downloader.data.repository.SessionRepository.getInstance(context) }
    val profiles by sessions.profiles.collectAsStateWithLifecycle()
    val currentPost = post
    fun request(index: Int): ImageRequest {
        val current = requireNotNull(currentPost); val image = current.resources[index]
        return ImageRequest.Builder(context).data(image.url)
            .addHeader("Referer", when (current.platform) { "Pixiv" -> "https://www.pixiv.net/"; "小红书" -> "https://www.xiaohongshu.com/"; "哔哩哔哩" -> "https://www.bilibili.com/"; "快手" -> "https://www.kuaishou.com/"; else -> current.url })
            .apply { if (profiles[current.platform]?.cookieEnabled != false) sessions.cookieHeader(current.platform, image.url)?.let { addHeader("Cookie", it) } }.build()
    }
    if (active && currentPost != null) preview?.let { index ->
        com.omni.downloader.ui.components.ImageViewer(currentPost!!.resources.size, index, { request(it) },
            onDismiss = { preview = null }, onSave = { viewModel.downloadImages(setOf(currentPost!!.resources[it].id), false) })
    }
    if (active && browser && browserUrl.isNotBlank()) com.omni.downloader.ui.components.DouyinImagesBrowser(browserUrl,
        onDismiss = { browser = false }, onCaptured = { item, url -> browser = false; viewModel.acceptBrowserImages(item, url) })
    return HomeImageResults(currentPost, selected, error, { selected = it }, { preview = it },
        { viewModel.downloadImages(selected, it) }, { request(it) },
        if (error.isNotBlank() && browserUrl.isNotBlank()) ({ browser = true }) else null)
}

internal fun androidx.compose.foundation.lazy.LazyListScope.homeImageItems(state: HomeImageResults) {
    if (state.error.isNotBlank()) item("home_image_error") {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        state.onBrowser?.let { OutlinedButton(onClick = it) { Text("验证目标图文并提取") } }
    }
    state.post?.let { post ->
        item("home_image_title") {
            Text(post.title, style = MaterialTheme.typography.titleMedium)
            Text("已选 ${state.selected.size} / ${post.resources.size}", Modifier.testTag("images_selection_count"))
        }
        item("home_image_actions") { ImageSelectionActions(state.selected.isNotEmpty(),
            { state.onSelection(post.resources.map { it.id }.toSet()) }, { state.onSelection(emptySet()) }, state.onSave) }
        items(post.resources.size, key = { "home_image:${post.resources[it].id}" }) { index ->
            ImageSelectionCard(post.resources[index], index, post.resources[index].id in state.selected, state.imageModel(index),
                { state.onSelection(if (post.resources[index].id in state.selected) state.selected - post.resources[index].id else state.selected + post.resources[index].id) },
                { state.onPreview(index) }, Modifier.fillMaxWidth())
        }
    }
}

/** One lazy scroll surface with a single sticky selection/action bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ImagePostGrid(
    post: ImagePost?,
    selected: Set<String>,
    onSelection: (Set<String>) -> Unit,
    onPreview: (Int) -> Unit,
    onSave: (archive: Boolean) -> Unit,
    imageModel: (Int) -> Any,
    header: @Composable ColumnScope.() -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(post?.platform, post?.contentId) { list.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Same adaptive cell minimum and spacing as the former grid. Compose
        // 1.6's native list sticky header keeps one real toolbar, without copies.
        val columns = (((maxWidth - 32.dp + 10.dp).value / 150.dp.value).toInt()).coerceAtLeast(1)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("images_grid"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "images_header", contentType = "header") {
                Column(Modifier.fillMaxWidth().testTag("images_header"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    header()
                    post?.let { current ->
                        Text(current.title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                        Text("已选 ${current.resources.size} / ${selected.size}", modifier = Modifier.testTag("images_selection_count"))
                    }
                }
            }
            post?.let { current ->
                stickyHeader(key = "images_actions") {
                    ImageSelectionActions(selected.isNotEmpty(),
                        onAll = { onSelection(current.resources.map { it.id }.toSet()) },
                        onClear = { onSelection(emptySet()) }, onSave = onSave)
                }
                val rowCount = (current.resources.size + columns - 1) / columns
                items(count = rowCount, key = { "photo_row:${current.resources[it * columns].id}" }, contentType = { "photo_row" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(columns) { column ->
                            val index = row * columns + column
                            val image = current.resources.getOrNull(index)
                            if (image == null) Spacer(Modifier.weight(1f)) else key(image.id) {
                                ImageSelectionCard(image, index, image.id in selected, imageModel(index),
                                    onToggle = { onSelection(if (image.id in selected) selected - image.id else selected + image.id) },
                                    onPreview = { onPreview(index) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ImageSelectionActions(hasSelection: Boolean, onAll: () -> Unit, onClear: () -> Unit, onSave: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag("images_actions")) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAll) { Text("全选") }
                TextButton(onClick = onClear) { Text("清空选择") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = hasSelection, onClick = { onSave(true) }, modifier = Modifier.testTag("images_zip")) { Text("打包 ZIP") }
                Button(enabled = hasSelection, onClick = { onSave(false) }, modifier = Modifier.testTag("images_save")) { Text("保存") }
            }
        }
    }
}

@Composable private fun ImageSelectionCard(
    image: ImageResource, index: Int, selected: Boolean, model: Any,
    onToggle: () -> Unit, onPreview: () -> Unit, modifier: Modifier = Modifier,
) {
    var loadedAspect by remember(image.id, image.url) { mutableFloatStateOf(0f) }
    val aspect = loadedAspect.takeIf { it > 0f && it.isFinite() }
        ?: if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else null
    Card(modifier.testTag("image_card_$index")
        .semantics { contentDescription = "选择第 ${index + 1} 张图片" }
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp).testTag("image_area_$index"), contentAlignment = Alignment.Center) {
            // Only the fitted image opens preview. Letterboxing, dimensions,
            // checkbox and the remaining card space toggle the parent selection.
            val previewSize = if (aspect == null) Modifier.fillMaxSize() else {
                val width = minOf(maxWidth, maxHeight * aspect)
                Modifier.size(width, minOf(maxHeight, width / aspect))
            }
            AsyncImage(model = model, contentDescription = "第 ${index + 1} 张图片",
                modifier = previewSize.testTag("image_preview_$index").clickable(role = Role.Button, onClickLabel = "预览图片", onClick = onPreview),
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) loadedAspect = size.width / size.height
                }, contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().padding(6.dp).testTag("image_info_$index")) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.testTag("image_checkbox_$index"))
            Text("${index + 1} · ${image.width}×${image.height}")
        }
    }
}
