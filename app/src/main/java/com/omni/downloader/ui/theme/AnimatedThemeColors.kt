package com.omni.downloader.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

internal data class ThemePalette(val colors: ColorScheme, val status: OmniStatusColors, val menuContainer: Color)

/** One clock retargets all colors in place, preserving pages and their remembered state. */
@Composable
internal fun animatedThemeColors(target: ColorScheme, targetStatus: OmniStatusColors, targetMenuContainer: Color): ThemePalette {
    val palette = remember(target, targetStatus, targetMenuContainer) { ThemePalette(target, targetStatus, targetMenuContainer) }
    val transition = updateTransition(palette, label = "theme-palette")
    @Composable fun color(name: String, value: (ColorScheme) -> Color): Color = transition.animateColor(
        transitionSpec = { tween(320, easing = FastOutSlowInEasing) }, label = name, targetValueByState = { value(it.colors) }
    ).value
    @Composable fun status(name: String, value: (OmniStatusColors) -> Color): Color = transition.animateColor(
        transitionSpec = { tween(320, easing = FastOutSlowInEasing) }, label = name, targetValueByState = { value(it.status) }
    ).value
    val colors = target.copy(
        primary = color("primary") { it.primary }, onPrimary = color("onPrimary") { it.onPrimary },
        primaryContainer = color("primaryContainer") { it.primaryContainer }, onPrimaryContainer = color("onPrimaryContainer") { it.onPrimaryContainer },
        inversePrimary = color("inversePrimary") { it.inversePrimary },
        secondary = color("secondary") { it.secondary }, onSecondary = color("onSecondary") { it.onSecondary },
        secondaryContainer = color("secondaryContainer") { it.secondaryContainer }, onSecondaryContainer = color("onSecondaryContainer") { it.onSecondaryContainer },
        tertiary = color("tertiary") { it.tertiary }, onTertiary = color("onTertiary") { it.onTertiary },
        tertiaryContainer = color("tertiaryContainer") { it.tertiaryContainer }, onTertiaryContainer = color("onTertiaryContainer") { it.onTertiaryContainer },
        background = color("background") { it.background }, onBackground = color("onBackground") { it.onBackground },
        surface = color("surface") { it.surface }, onSurface = color("onSurface") { it.onSurface },
        surfaceBright = color("surfaceBright") { it.surfaceBright }, surfaceDim = color("surfaceDim") { it.surfaceDim },
        surfaceContainer = color("surfaceContainer") { it.surfaceContainer },
        surfaceContainerHigh = color("surfaceContainerHigh") { it.surfaceContainerHigh },
        surfaceContainerHighest = color("surfaceContainerHighest") { it.surfaceContainerHighest },
        surfaceContainerLow = color("surfaceContainerLow") { it.surfaceContainerLow },
        surfaceContainerLowest = color("surfaceContainerLowest") { it.surfaceContainerLowest },
        surfaceVariant = color("surfaceVariant") { it.surfaceVariant }, onSurfaceVariant = color("onSurfaceVariant") { it.onSurfaceVariant },
        surfaceTint = color("surfaceTint") { it.surfaceTint }, inverseSurface = color("inverseSurface") { it.inverseSurface },
        inverseOnSurface = color("inverseOnSurface") { it.inverseOnSurface },
        error = color("error") { it.error }, onError = color("onError") { it.onError },
        errorContainer = color("errorContainer") { it.errorContainer }, onErrorContainer = color("onErrorContainer") { it.onErrorContainer },
        outline = color("outline") { it.outline }, outlineVariant = color("outlineVariant") { it.outlineVariant }, scrim = color("scrim") { it.scrim }
    )
    val menuContainer = transition.animateColor(
        transitionSpec = { tween(320, easing = FastOutSlowInEasing) }, label = "menu-container",
        targetValueByState = { it.menuContainer }
    ).value
    return ThemePalette(colors, OmniStatusColors(
        status("status-success") { it.success }, status("status-warning") { it.warning },
        status("status-error") { it.error }, status("status-info") { it.info }
    ), menuContainer)
}
