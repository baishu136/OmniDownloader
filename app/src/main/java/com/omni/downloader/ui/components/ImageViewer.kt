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
import coil.compose.SubcomposeAsyncImage

/** Uses the existing GIF/WebP decoder; zooming never changes the saved resource. */
@Composable
fun ImageViewer(count: Int, initialIndex: Int = 0, model: (Int) -> Any?,
    onDismiss: () -> Unit, onSave: ((Int) -> Unit)? = null) {
    if (count <= 0) return
    var index by remember(count, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, count - 1)) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / $count", color = Color.White, modifier = Modifier.weight(1f))
                onSave?.let { save -> TextButton(onClick = { save(index) }) { Text("保存此图") } }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(index) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
                .pointerInput(index) { detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                        (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                } }, contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(model = model(index), contentDescription = "第 ${index + 1} 张图片",
                    contentScale = ContentScale.Fit,
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                    error = { Text("图片暂不可预览，可关闭后重试或保存原图", color = Color.White) },
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = index > 0, onClick = { index-- }) { Text("上一张") }
                Text("双指缩放 · 双击还原/放大", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = index < count - 1, onClick = { index++ }) { Text("下一张") }
            }
        }
    }
}
