package com.omni.downloader.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

val LocalTabActive = compositionLocalOf { true }

/** Keep visited pages in composition, measuring and placing only the selected one.
 * Hidden pages retain inputs/scroll state but have no drawing, hit targets, or
 * lifecycle-aware subscriptions. Fade-through uses one placed page and one layer,
 * with no simultaneous old/new lists or timed prewarming.
 */
@Composable
internal fun RetainedTabHost(
    selectedTab: Int,
    tabCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    require(selectedTab in 0 until tabCount)
    val focus = LocalFocusManager.current
    LaunchedEffect(selectedTab) { focus.clearFocus() }
    var displayedTab by remember { mutableStateOf(selectedTab) }
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(selectedTab) {
        if (displayedTab != selectedTab) {
            opacity.animateTo(0f, tween(OmniMotion.TAB_FADE_OUT_MS, easing = FastOutLinearInEasing))
            displayedTab = selectedTab
        }
        opacity.animateTo(1f, tween(OmniMotion.TAB_FADE_IN_MS, easing = LinearOutSlowInEasing))
    }
    val visited = remember { mutableSetOf<Int>() }
    visited.add(selectedTab)
    Box(modifier.graphicsLayer { alpha = opacity.value }) {
        repeat(tabCount) { tab ->
            if (tab in visited) key(tab) {
                val pageContent: @Composable () -> Unit = remember(tab, content) { { content(tab) } }
                RetainedTab(placed = tab == displayedTab, active = tab == selectedTab && tab == displayedTab, content = pageContent)
            }
        }
        if (displayedTab != selectedTab) {
            // Block outgoing-page taps during fade-out; the navigation bar remains responsive.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
    }
}

@Composable
private fun RetainedTab(placed: Boolean, active: Boolean, content: @Composable () -> Unit) {
    val effectiveActive = active && LocalTabActive.current
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { TabLifecycleOwner(parent) }
    DisposableEffect(owner, parent) {
        val observer = LifecycleEventObserver { _, _ -> owner.update() }
        parent.lifecycle.addObserver(observer)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.destroy()
        }
    }
    SideEffect { owner.setActive(effectiveActive) }
    val measurePolicy = remember(placed) {
        MeasurePolicy { measurables, constraints ->
            val page = if (placed) measurables.single().measure(constraints) else null
            layout(constraints.maxWidth, constraints.maxHeight) { page?.placeRelative(0, 0) }
        }
    }
    CompositionLocalProvider(
        LocalTabActive provides effectiveActive,
        LocalLifecycleOwner provides owner
    ) {
        Layout(
            content = { Box(Modifier.fillMaxSize()) { content() } },
            modifier = Modifier.fillMaxSize(),
            measurePolicy = measurePolicy
        )
    }
}

private class TabLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var active = false
    override val lifecycle: Lifecycle get() = registry

    fun setActive(value: Boolean) {
        active = value
        update()
    }

    fun update() {
        val parentState = parent.lifecycle.currentState
        registry.currentState = if (active || parentState < Lifecycle.State.CREATED) {
            parentState
        } else Lifecycle.State.CREATED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

/** Indeterminate animations must leave composition while their tab is hidden. */
@Composable
internal fun TabProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp
) {
    if (LocalTabActive.current) {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else Spacer(modifier)
}
