package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.engine.YtDlpPlatforms
import com.omni.downloader.ui.localization.LocalAppStrings
import kotlinx.coroutines.CancellationException

@Composable
internal fun YtDlpPlatformCatalogButton(enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("ytdlp_platforms_open")) {
        Text(strings.ytDlpPlatformsTitle)
    }
    if (open && enabled) YtDlpPlatformCatalog(onDismiss = { open = false })
}

@Composable
private fun YtDlpPlatformCatalog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val strings = LocalAppStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<YtDlpPlatforms.Extractor>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context, attempt) {
        failed = false
        try {
            catalog = DownloadEngine.listYtDlpPlatforms(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val matches = remember(catalog, query) {
        catalog.orEmpty().filter { it.matches(query.trim()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.ytDlpPlatformsTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.ytDlpPlatformsDescription)
                when {
                    failed -> {
                        Text(strings.ytDlpPlatformsUnavailable)
                        TextButton(onClick = { attempt++ }) { Text(strings.retry) }
                    }
                    catalog == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(strings.ytDlpPlatformsSearch) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().testTag("ytdlp_platforms_search")
                        )
                        if (matches.isEmpty()) Text(strings.ytDlpPlatformsNoResults)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(matches, key = { it.id }) { entry ->
                                val platform = entry.platform
                                if (platform == null || platform.equals(entry.id, ignoreCase = true)) {
                                    Text(platform ?: entry.id)
                                } else {
                                    Column {
                                        Text(platform)
                                        Text(entry.id, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } }
    )
}
