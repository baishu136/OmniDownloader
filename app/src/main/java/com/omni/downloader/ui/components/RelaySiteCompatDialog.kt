package com.omni.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次添加第三方网页时的兼容性与免责声明提示弹窗
 */
@Composable
fun RelaySiteCompatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = strings.relayCompatTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TipItem(
                    title = strings.relayCompatS1Title,
                    description = strings.relayCompatS1Desc
                )

                TipItem(
                    title = strings.relayCompatS2Title,
                    description = strings.relayCompatS2Desc
                )

                TipItem(
                    title = strings.relayCompatS3Title,
                    description = strings.relayCompatS3Desc
                )
            }
        },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.medium,
                onClick = onConfirm,

            ) {
                Text(strings.relayCompatAccept)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,

            ) {
                Text(strings.cancel)
            }
        }
    )
}

@Composable
private fun TipItem(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
