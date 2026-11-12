package com.omni.downloader.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Fill only the icon drawing; keep M3 indicator, ripple and touch targets intact. */
@Composable internal fun NavigationFillIcon(outline: ImageVector, filled: ImageVector, label: String,
    selected: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(if (selected) 1f else 0f,
        tween(OmniMotion.TAB_DURATION, easing = OmniMotion.Standard), label = "navigationIconFill")
    Box(modifier.size(24.dp).semantics { contentDescription = label }) {
        Icon(outline, contentDescription = null, modifier = Modifier.size(24.dp).drawWithContent {
            clipRect(bottom = size.height * (1f - progress)) { this@drawWithContent.drawContent() }
        })
        Icon(filled, contentDescription = null, modifier = Modifier.size(24.dp).drawWithContent {
            clipRect(top = size.height * (1f - progress)) { this@drawWithContent.drawContent() }
        })
    }
}
