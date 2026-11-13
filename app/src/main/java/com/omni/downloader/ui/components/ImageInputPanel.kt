package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omni.downloader.ui.localization.LocalAppStrings

@Composable
internal fun ImageInputPanel(
    input: String,
    busy: Boolean,
    pathLabel: String,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit,
    onChoosePath: () -> Unit,
    onResetPath: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var pathsExpanded by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = input, onValueChange = onInput,
        modifier = Modifier.fillMaxWidth().testTag("images_link_input"),
        label = { Text("粘贴单个作品链接或分享文字") }, maxLines = 3,
        trailingIcon = {
            if (input.isNotEmpty()) IconButton(onClick = { onInput("") }, modifier = Modifier.testTag("images_clear")) {
                Icon(Icons.Outlined.Clear, contentDescription = strings.clear)
            } else IconButton(onClick = onPaste, modifier = Modifier.testTag("images_paste")) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = strings.paste)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank() && !busy) onAnalyze() }),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { pathsExpanded = true },
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("images_save_path")
                    .semantics { contentDescription = "保存路径：$pathLabel" }) {
                Text(pathLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = TextDecoration.Underline)
            }
            OmniOptionMenu(expanded = pathsExpanded, onDismissRequest = { pathsExpanded = false }) {
                OmniOptionMenuItem(label = "选择文件夹", onClick = { pathsExpanded = false; onChoosePath() })
                OmniOptionMenuItem(label = "恢复默认目录", onClick = { pathsExpanded = false; onResetPath() })
            }
        }
        Button(shape = MaterialTheme.shapes.medium, onClick = onAnalyze, enabled = !busy && input.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("images_analyze")) {
            Text(if (busy) "正在解析…" else "解析图文")
        }
    }
}
