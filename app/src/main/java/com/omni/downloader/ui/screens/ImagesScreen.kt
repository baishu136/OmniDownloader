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
import coil.decode.BitmapFactoryDecoder
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import com.omni.downloader.ui.viewmodel.MainViewModel
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.localization.localizeError
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
    val onSave: (Boolean) -> Unit, val imageModel: @Composable (Int) -> Any?,
    val onBrowser: (() -> Unit)?, val scanning: Boolean,
    val onStop: () -> Unit, val onContinue: () -> Unit,
    val animationFormat: String, val onAnimationFormat: (String)->Unit,val saving:Boolean=false
)

@Composable internal fun rememberHomeImageResults(viewModel: MainViewModel, active: Boolean): HomeImageResults {
    val post by viewModel.imagePost.collectAsStateWithLifecycle()
    val error by viewModel.imageError.collectAsStateWithLifecycle()
    val browserUrl by viewModel.imageBrowserUrl.collectAsStateWithLifecycle()
    val busy by viewModel.imageBusy.collectAsStateWithLifecycle()
    val saving by viewModel.imageSaving.collectAsStateWithLifecycle()
    val animationFormat by viewModel.artistAnimationFormat.collectAsStateWithLifecycle()
    var selected by remember(post?.contentId, post?.url) { mutableStateOf(post?.resources?.map { it.id }?.toSet().orEmpty()) }
    var known by remember(post?.contentId, post?.url) { mutableStateOf(selected) }
    LaunchedEffect(post) {
        val current = post?.resources?.map { it.id }?.toSet().orEmpty()
        selected = selected.intersect(current) + (current - known); known = current
    }
    var preview by remember(post?.contentId, post?.url) { mutableStateOf<Int?>(null) }
    var browser by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val sessions = remember(context) { com.omni.downloader.data.repository.SessionRepository.getInstance(context) }
    val profiles by sessions.profiles.collectAsStateWithLifecycle()
    val proxy by viewModel.proxyUrl.collectAsStateWithLifecycle()
    val currentPost = post
    val platform = currentPost?.platform.orEmpty()
    val session = profiles[platform]
    val requests = remember(context, platform, currentPost?.url, session,proxy) {
        val cookies=lazy{sessions.imageCookieSnapshot(platform)}
        ImageRequestCache(context, when (platform) { "Pixiv" -> "https://www.pixiv.net/"; "小红书" -> "https://www.xiaohongshu.com/"; "哔哩哔哩" -> "https://www.bilibili.com/"; "快手" -> "https://www.kuaishou.com/"; else -> currentPost?.url.orEmpty() },
            accountScope={"$platform|${cookies.value.first}"}) { url ->
            if (session?.cookieEnabled != false) cookies.value.second(url) else null
        }
    }
    if (active && currentPost != null) preview?.let { index ->
        com.omni.downloader.ui.components.ImageViewer(currentPost!!.resources.size, index, { rememberImageRequest(requests,currentPost!!.resources[it],false) },
            onDismiss = { preview = null }, onSave = { viewModel.downloadImages(setOf(currentPost!!.resources[it].id), false) },
            itemLabel = { if(currentPost!!.resources[it].kind=="UGOIRA") strings.ugoiraCoverHint else null },
            saveLabel = { if(currentPost!!.resources[it].kind=="UGOIRA") strings.downloadAnimation else strings.saveThisImage })
    }
    if (active && browser && browserUrl.isNotBlank()) com.omni.downloader.ui.components.DouyinImagesBrowser(browserUrl,
        onDismiss = { browser = false }, onCaptured = { item, url -> browser = false; viewModel.acceptBrowserImages(item, url) })
    return HomeImageResults(currentPost, selected, strings.localizeError(error), { selected = it }, { preview = it },
        { viewModel.downloadImages(selected, it) }, { rememberImageRequest(requests,requireNotNull(currentPost).resources[it],true) },
        if (error.isNotBlank() && browserUrl.isNotBlank()) ({ browser = true }) else null,
        busy, viewModel::stopProfileAnalysis, { viewModel.startAnalyze() },animationFormat,{ viewModel.artistAnimationFormat.value=it },saving=saving)
}

internal fun androidx.compose.foundation.lazy.LazyListScope.homeImageItems(state: HomeImageResults, columns: Int) {
    if (state.error.isNotBlank()) item("home_image_error") {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        state.onBrowser?.let { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = it) { Text(LocalAppStrings.current.verifyImagesAndExtract) } }
    }
    state.post?.let { post ->
        item("home_image_title") {
            val strings = LocalAppStrings.current
            Text(post.title, style = MaterialTheme.typography.titleMedium)
            if(post.route=="pixiv_artist") {
                val animations=remember(post.resources){post.resources.count { it.kind=="UGOIRA" }}
                val stage = if(state.scanning) strings.statusAnalyzingShort else if(post.artistComplete) strings.statusCompletedShort else strings.statusIncompleteShort
                Text(strings.artistProgress.format(stage, post.artistOffset, post.artistArtworkIds.size, post.resources.size-animations, animations),
                    style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("pixiv_artist_status"))
                Row {
                    if(state.scanning) TextButton(onClick=state.onStop,modifier=Modifier.testTag("pixiv_artist_stop")){Text(strings.stopAnalyzing)}
                    else if(!post.artistComplete) TextButton(onClick=state.onContinue,modifier=Modifier.testTag("pixiv_artist_continue")){Text(strings.continueAnalyzing)}
                    if(animations>0) ArtistAnimationFormat(state.animationFormat,state.onAnimationFormat)
                }
            }
            if (post.route == "x_profile") {
                val count = remember(post.resources) { post.resources.map { it.id.substringBefore(':') }.distinct().size }
                Text(when {
                    state.scanning -> strings.xProfileReading.format(count, post.resources.size)
                    post.profileComplete -> strings.xProfileComplete.format(count, post.resources.size)
                    else -> strings.xProfileIncomplete.format(count)
                }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("x_profile_status"))
                if (state.scanning) TextButton(onClick = state.onStop, modifier = Modifier.testTag("x_profile_stop")) { Text(strings.stopAnalyzing) }
                else if (!post.profileComplete) TextButton(onClick = state.onContinue, modifier = Modifier.testTag("x_profile_continue")) { Text(strings.continueAnalyzing) }
            }
            Text(strings.selectedCount.format(state.selected.size, post.resources.size), Modifier.testTag("images_selection_count"))
        }
        item("home_image_actions") {
            val strings = LocalAppStrings.current
            ImageSelectionActions(state.selected.isNotEmpty(),
            { state.onSelection(post.resources.map { it.id }.toSet()) }, { state.onSelection(emptySet()) }, state.onSave,
            if (post.route == "x_profile") strings.zipByPost else if(post.route=="pixiv_artist") strings.zipByArtwork else strings.zipArchive,saving=state.saving) }
        val rows = (post.resources.size + columns - 1) / columns
        items(rows, key = { "home_photo_row:${post.resources[it * columns].id}" }, contentType = { "photo_row" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    val image = post.resources.getOrNull(index)
                    if (image == null) Spacer(Modifier.weight(1f)) else key(image.id) {
                        ImageSelectionCard(image, index, image.id in state.selected, state.imageModel(index),
                            { state.onSelection(if (image.id in state.selected) state.selected - image.id else state.selected + image.id) },
                            { state.onPreview(index) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable private fun ArtistAnimationFormat(value: String,onValue: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    val labels=mapOf("ugoira_gif" to "GIF","ugoira_mp4" to "MP4","ugoira_zip" to strings.originalFramesZip)
    Box {
        TextButton(onClick={expanded=true},modifier=Modifier.testTag("pixiv_artist_format")){Text(strings.animationFormat.format(labels[value]))}
        com.omni.downloader.ui.components.OmniOptionMenu(expanded,{expanded=false}) {
            labels.forEach { (key,label) -> com.omni.downloader.ui.components.OmniOptionMenuItem(label,{expanded=false;onValue(key)},selected=value==key) }
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
                        Text(LocalAppStrings.current.selectedCount.format(selected.size, current.resources.size), modifier = Modifier.testTag("images_selection_count"))
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
@Composable private fun ImageSelectionActions(hasSelection: Boolean, onAll: () -> Unit, onClear: () -> Unit, onSave: (Boolean) -> Unit, archiveLabel: String? = null,saving:Boolean=false) {
    val strings = LocalAppStrings.current
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag("images_actions")) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAll) { Text(strings.selectAll) }
                TextButton(onClick = onClear) { Text(strings.clearSelection) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = hasSelection, onClick = { onSave(true) }, modifier = Modifier.testTag("images_zip")) { Text(archiveLabel ?: strings.zipArchive) }
                Button(shape = MaterialTheme.shapes.medium, enabled = hasSelection && !saving, onClick = { onSave(false) }, modifier = Modifier.testTag("images_save")) { Text(if(saving) strings.savingEllipsis else strings.save) }
            }
        }
    }
}

@Composable private fun ImageSelectionCard(
    image: ImageResource, index: Int, selected: Boolean, model: Any?,
    onToggle: () -> Unit, onPreview: () -> Unit, modifier: Modifier = Modifier,
) {
    var loadedAspect by remember(image.id, image.url) { mutableFloatStateOf(0f) }
    val strings = LocalAppStrings.current
    val knownAspect = if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else null
    val aspect = loadedAspect.takeIf { it > 0f && it.isFinite() } ?: knownAspect
    Card(modifier.testTag("image_card_$index")
        .semantics { contentDescription = if(image.kind=="UGOIRA") strings.selectAnimationDesc.format(index+1) else strings.selectImageDesc.format(index + 1) }
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp).testTag("image_area_$index"), contentAlignment = Alignment.Center) {
            // Only the fitted image opens preview. Letterboxing, dimensions,
            // checkbox and the remaining card space toggle the parent selection.
            val previewSize = if (aspect == null) Modifier.fillMaxSize() else {
                val width = minOf(maxWidth, maxHeight * aspect)
                Modifier.size(width, minOf(maxHeight, width / aspect))
            }
            AsyncImage(model = model, contentDescription = strings.imageDesc.format(index + 1),
                modifier = previewSize.testTag("image_preview_$index").clickable(role = Role.Button, onClickLabel = strings.previewImageDesc, onClick = onPreview),
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (loadedAspect == 0f && size.width > 0f && size.height > 0f) {
                        val actual = size.width / size.height
                        // Correct missing/rotated metadata once; ignore rounding differences.
                        if (knownAspect == null || kotlin.math.abs(actual-knownAspect) > knownAspect*0.03f) loadedAspect = actual
                    }
                }, contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().padding(6.dp).testTag("image_info_$index")) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.testTag("image_checkbox_$index"))
            Text(if(image.kind=="UGOIRA") strings.ugoiraIndexLabel.format(index+1) else strings.imageIndexLabel.format(index + 1, image.width, image.height))
        }
    }
}

/** A bounded, account-scoped request cache. Cookie decryption never runs during layout. */
internal class ImageRequestCache(private val context: android.content.Context, private val referer: String,
    private val accountScope:()->String={""},
    private val cookieFor: (String) -> String?) {
    private val cache = android.util.LruCache<String,ImageRequest>(160)
    private val decoder = BitmapFactoryDecoder.Factory(maxParallelism = 3)
    private val mutex=kotlinx.coroutines.sync.Mutex()
    private fun key(image: ImageResource, thumbnail: Boolean) = "$thumbnail:${image.id}:${image.url}:${image.previewUrl}"
    fun peek(image: ImageResource, thumbnail: Boolean): ImageRequest? = cache.get(key(image,thumbnail))
    suspend fun request(image: ImageResource, thumbnail: Boolean): ImageRequest = withContext(Dispatchers.IO) {
        mutex.lock()
        try{build(image,thumbnail)}finally{mutex.unlock()}
    }
    private fun build(image:ImageResource,thumbnail:Boolean):ImageRequest {
        val key=key(image,thumbnail);cache.get(key)?.let{return it}
        val url=if(thumbnail)com.omni.downloader.engine.ImagePreviewPolicy.thumbnail(image) else image.url
        val cookie=cookieFor(url)
        val diskKey=com.omni.downloader.engine.ImagePreviewPolicy.cacheKey(url,referer,cookie,com.omni.downloader.engine.NetworkProxy.configuredUrl,accountScope())
        return ImageRequest.Builder(context).data(url)
            .diskCacheKey(diskKey).memoryCacheKey("$diskKey:${if(thumbnail)"thumb" else "full"}")
            .crossfade(false).addHeader("Referer",referer)
            .apply{
                cookie?.let{addHeader("Cookie",it)}
                if(thumbnail){size(640,640);scale(Scale.FIT);precision(Precision.INEXACT);decoderFactory(decoder)}
                else{allowRgb565(false);placeholderMemoryCacheKey(build(image,true).memoryCacheKey)}
            }.build().also{cache.put(key,it)}
    }
}

@Composable private fun rememberImageRequest(cache: ImageRequestCache, image: ImageResource, thumbnail: Boolean): ImageRequest? {
    val request by produceState(cache.peek(image,thumbnail),cache,image.id,image.url,image.previewUrl,thumbnail) {
        value = cache.request(image,thumbnail)
    }
    return request
}
