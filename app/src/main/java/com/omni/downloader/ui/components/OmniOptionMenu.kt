package com.omni.downloader.ui.components

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.min

/** Cherry Mobile's Android ActionMenu geometry, implemented with Compose rather than an RN runtime. */
internal object OptionMenuPlacement {
    data class Position(val offset: IntOffset, val above: Boolean)
    fun position(anchor: IntRect, window: IntSize, panel: IntSize, margin: Int, gap: Int): Position {
        val below = window.height - margin - anchor.bottom - gap
        val above = anchor.top - margin - gap
        val opensAbove = below < panel.height && above > below
        val left = (anchor.right - panel.width).coerceIn(margin, (window.width - margin - panel.width).coerceAtLeast(margin))
        val preferredTop = if (opensAbove) anchor.top - panel.height - gap else anchor.bottom + gap
        val top = preferredTop.coerceIn(margin, (window.height - margin - panel.height).coerceAtLeast(margin))
        return Position(IntOffset(left, top), opensAbove)
    }
}

private class MenuInteraction(val open: Boolean, val choose: (() -> Unit) -> Unit)
private val LocalMenuInteraction = staticCompositionLocalOf<MenuInteraction> { error("Menu option requires OmniOptionMenu") }

/** Opaque anchored panel: expand from 32dp, slide/fade the content, retain it until the exit finishes. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun OmniOptionMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val currentExpanded by rememberUpdatedState(expanded)
    fun close(action: (() -> Unit)? = null) {
        if (currentExpanded && !closing) {
            closing = true; afterClose = action; dismiss()
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            closing = false; afterClose = null; visible = true
            progress.animateTo(1f, tween(350, easing = CubicBezierEasing(.34f, 1.25f, .64f, 1f)))
        } else {
            progress.animateTo(0f, tween(250, easing = CubicBezierEasing(.22f, 1f, .36f, 1f)))
            visible = false
            val action = afterClose; afterClose = null
            action?.invoke()
        }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val dimensions = listOf(configuration.screenWidthDp, configuration.screenHeightDp, configuration.fontScale, imeBottom)
    var previousDimensions by remember { mutableStateOf(dimensions) }
    LaunchedEffect(dimensions) {
        if (dimensions != previousDimensions && expanded) close()
        previousDimensions = dimensions
    }
    if (expanded || visible) {
        var opensAbove by remember { mutableStateOf(false) }
        val placement = remember(density) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val position = OptionMenuPlacement.position(anchorBounds, windowSize, popupContentSize,
                        with(density) { 16.dp.roundToPx() }, with(density) { 8.dp.roundToPx() })
                    if (opensAbove != position.above) opensAbove = position.above
                    return position.offset
                }
            }
        }
        val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
        val background = if (dark) MaterialTheme.colorScheme.surfaceVariant else Color.White
        val maxHeight = minOf(480.dp, (configuration.screenHeightDp.dp - 32.dp - with(density) { imeBottom.toDp() }).coerceAtLeast(32.dp))
        Popup(popupPositionProvider = placement, onDismissRequest = { close() },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
            CompositionLocalProvider(LocalMenuInteraction provides MenuInteraction(expanded && !closing) { close(it) }) {
                Box(Modifier.widthIn(max = (configuration.screenWidthDp.dp - 32.dp).coerceAtLeast(32.dp))
                    .width(208.dp).heightIn(max = maxHeight)
                    .testTag("omni_option_menu")
                    .semantics { paneTitle = "选项菜单"; if (!expanded || closing) invisibleToUser() }
                    .graphicsLayer {
                        val p = progress.value
                        alpha = (p * 4f).coerceIn(0f, 1f)
                        shape = MenuRevealShape(p, opensAbove, 32.dp.toPx(), 20.8.dp.toPx())
                        clip = true
                        shadowElevation = 24.dp.toPx()
                        ambientShadowColor = Color.Black.copy(alpha = if (dark) .6f else .15f)
                        spotShadowColor = ambientShadowColor
                    }.background(background)) {
                    Column(Modifier.graphicsLayer {
                        val p = progress.value
                        alpha = p.coerceIn(0f, 1f)
                        translationX = 40.dp.toPx() * (1f - p)
                        scaleX = .97f + .03f * p; scaleY = scaleX
                        if (Build.VERSION.SDK_INT >= 31) {
                            val blur = (1f - p).coerceAtLeast(0f) * 2.dp.toPx()
                            renderEffect = if (blur > .01f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL).asComposeRenderEffect() else null
                        }
                    }.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
                }
            }
        }
    }
}

private class MenuRevealShape(private val progress: Float, private val above: Boolean,
    private val restingSize: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val width = restingSize + (size.width - restingSize) * progress
        val height = restingSize + (size.height - restingSize) * progress
        val top = if (above) size.height - height else 0f
        return Outline.Rounded(RoundRect(0f, top, width, top + height, CornerRadius(min(radius, min(width, height) / 2f))))
    }
}

/** One wrapping, 56dp touch target; choice checkmarks occupy the trailing edge. */
@Composable
fun OmniOptionMenuItem(label: String, onClick: () -> Unit, selected: Boolean? = null,
    enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null) {
    val menu = LocalMenuInteraction.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && menu.open
    val click = { menu.choose(onClick) }
    val action = if (selected == null) Modifier.clickable(interactionSource = interaction, indication = null,
        enabled = active, role = Role.Button, onClick = click)
    else Modifier.selectable(selected = selected, interactionSource = interaction, indication = null,
        enabled = active, role = Role.RadioButton, onClick = click)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.4.dp))
        .background(if (pressed && active) MaterialTheme.colorScheme.onSurface.copy(alpha = .08f) else Color.Transparent)
        .then(action).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp)
        .graphicsLayer { alpha = if (enabled) 1f else .4f },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        leadingIcon?.let { icon -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)), contentAlignment = androidx.compose.ui.Alignment.Center) { icon() } }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (selected != null) Box(Modifier.size(20.dp)) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
