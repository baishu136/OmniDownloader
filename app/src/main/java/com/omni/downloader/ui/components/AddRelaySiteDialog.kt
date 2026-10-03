package com.omni.downloader.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.downloader.data.model.RelaySite
import com.omni.downloader.data.repository.SettingsRepository
import java.util.UUID

/**
 * 添加备用中转解析网站弹窗
 */
@Composable
fun AddRelaySiteDialog(
    onDismiss: () -> Unit,
    onAddSite: (RelaySite) -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "添加备用中转网站",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "快捷预设（点击直接填入）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(SettingsRepository.DEFAULT_PRESET_RELAY_SITES) { preset ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable {
                                nameInput = preset.name
                                urlInput = preset.url
                            }
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("网站名称") },
                    placeholder = { Text("例如：X2Twitter / 快存") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Language, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("网站完整网址") },
                    placeholder = { Text("https://...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Link, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = nameInput.trim()
                    var cleanUrl = urlInput.trim()

                    if (cleanName.isBlank() || cleanUrl.isBlank()) {
                        Toast.makeText(context, "请完整填写网站名称与网址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                        cleanUrl = "https://$cleanUrl"
                    }

                    val domain = try {
                        android.net.Uri.parse(cleanUrl).host ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    val safeIconUrl = if (domain.isNotBlank()) "https://icon.horse/icon/$domain" else ""

                    val site = RelaySite(
                        id = UUID.randomUUID().toString().replace("-", "").take(8),
                        name = cleanName,
                        url = cleanUrl,
                        iconUrl = safeIconUrl
                    )
                    onAddSite(site)
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("确认添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
