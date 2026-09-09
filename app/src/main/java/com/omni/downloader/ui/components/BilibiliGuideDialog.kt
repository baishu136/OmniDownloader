package com.omni.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.theme.SuccessGreen

@Composable
fun BilibiliGuideDialog(
    onLoginClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Hd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(32.dp)
                )
            }
        },
        title = {
            Text(
                text = strings.bilibiliGuideTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = strings.bilibiliGuideMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onLoginClick,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = strings.bilibiliGuideLogin, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = strings.bilibiliGuideLater)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
