package com.omni.downloader.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Retarget colors in place: do not crossfade/recreate pages or their remembered state. */
@Composable
internal fun animatedThemeColors(target: ColorScheme): ColorScheme {
    val transition = updateTransition(target, label = "theme-palette")
    @Composable fun color(name: String, value: (ColorScheme) -> Color): Color = transition.animateColor(
        transitionSpec = { tween(320, easing = FastOutSlowInEasing) }, label = name, targetValueByState = { value(it) }
    ).value
    return target.copy(
        primary = color("primary") { it.primary }, onPrimary = color("onPrimary") { it.onPrimary },
        primaryContainer = color("primaryContainer") { it.primaryContainer }, onPrimaryContainer = color("onPrimaryContainer") { it.onPrimaryContainer },
        inversePrimary = color("inversePrimary") { it.inversePrimary },
        secondary = color("secondary") { it.secondary }, onSecondary = color("onSecondary") { it.onSecondary },
        secondaryContainer = color("secondaryContainer") { it.secondaryContainer }, onSecondaryContainer = color("onSecondaryContainer") { it.onSecondaryContainer },
        tertiary = color("tertiary") { it.tertiary }, onTertiary = color("onTertiary") { it.onTertiary },
        tertiaryContainer = color("tertiaryContainer") { it.tertiaryContainer }, onTertiaryContainer = color("onTertiaryContainer") { it.onTertiaryContainer },
        background = color("background") { it.background }, onBackground = color("onBackground") { it.onBackground },
        surface = color("surface") { it.surface }, onSurface = color("onSurface") { it.onSurface },
        surfaceVariant = color("surfaceVariant") { it.surfaceVariant }, onSurfaceVariant = color("onSurfaceVariant") { it.onSurfaceVariant },
        surfaceTint = color("surfaceTint") { it.surfaceTint }, inverseSurface = color("inverseSurface") { it.inverseSurface },
        inverseOnSurface = color("inverseOnSurface") { it.inverseOnSurface },
        error = color("error") { it.error }, onError = color("onError") { it.onError },
        errorContainer = color("errorContainer") { it.errorContainer }, onErrorContainer = color("onErrorContainer") { it.onErrorContainer },
        outline = color("outline") { it.outline }, outlineVariant = color("outlineVariant") { it.outlineVariant }, scrim = color("scrim") { it.scrim }
    )
}
