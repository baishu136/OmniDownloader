package com.omni.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

/** Uses the existing GIF/WebP decoder; zooming never changes the saved resource. */
@Composable
fun ImageViewer(count: Int, initialIndex: Int = 0, model: @Composable (Int) -> Any?,
    onDismiss: () -> Unit, onSave: ((Int) -> Unit)? = null, itemLabel: (Int)->String? = {null}, saveLabel: ((Int)->String)? = null) {
    if (count <= 0) return
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    var index by remember(count, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, count - 1)) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / $count" + (itemLabel(index)?.let { " · $it" } ?: ""), color = Color.White, modifier = Modifier.weight(1f))
                onSave?.let { save -> TextButton(onClick = { save(index) }) { Text(saveLabel?.invoke(index) ?: strings.saveThisImage) } }
                TextButton(onClick = onDismiss) { Text(strings.close) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(index) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
                .pointerInput(index) { detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                        (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                } }, contentAlignment = Alignment.Center) {
                val image = model(index)
                if (image == null) CircularProgressIndicator()
                else {
                    var state by remember(index,image){mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)}
                    AsyncImage(model=image,contentDescription=strings.imageDesc.format(index+1),contentScale=ContentScale.Fit,
                        onState={state=it},
                        modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y})
                    if(state is AsyncImagePainter.State.Loading)CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp).size(28.dp))
                    if(state is AsyncImagePainter.State.Error)Text(strings.imagePreviewUnavailable,color=Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = index > 0, onClick = { index-- }) { Text(strings.previousImage) }
                Text(strings.zoomHint, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = index < count - 1, onClick = { index++ }) { Text(strings.nextImage) }
            }
        }
    }
}
