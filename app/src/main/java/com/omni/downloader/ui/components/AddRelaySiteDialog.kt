package com.omni.downloader.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
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
                    text = "请输入您需要添加的第三方网页名称与网址：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("网站名称") },
                    placeholder = { Text("例如：备用解析工具") },
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
