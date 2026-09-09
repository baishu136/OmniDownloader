package com.omni.downloader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.omni.downloader.data.model.AudioFormat
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSelectorSheet(
    metadata: VideoMetadata,
    selectedMultiMediaIndex: Int = 0,
    selectedType: DownloadType,
    selectedVideoFormat: FormatOption?,
    selectedAudioFormat: AudioFormat,
    onSelectMultiMedia: (Int) -> Unit = {},
    onSelectType: (DownloadType) -> Unit,
    onSelectVideoFormat: (FormatOption) -> Unit,
    onSelectAudioFormat: (AudioFormat) -> Unit,
    onConfirmDownload: (Boolean) -> Unit,
    onDownloadAllMultiMedia: () -> Unit = {},
    onDownloadSelectedMultiMedia: (Set<Int>, Boolean) -> Unit = { _, _ -> },
    onSaveCoverDirectly: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val strings = com.omni.downloader.ui.localization.LocalAppStrings.current
    val isMultiVideo = metadata.multiMediaList.size > 1
    var isCollectionMode by remember { mutableStateOf(false) }
    var selectedEpisodes by remember(metadata) { mutableStateOf(metadata.multiMediaList.indices.toSet()) }
    var saveCoverWithDownload by remember { mutableStateOf(false) }

    val currentMedia = if (metadata.multiMediaList.isNotEmpty()) {
        metadata.multiMediaList.getOrElse(selectedMultiMediaIndex) { metadata }
    } else {
        metadata
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
            // 视频头部概览
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentMedia.thumbnailUrl.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(width = 110.dp, height = 66.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        AsyncImage(
                            model = currentMedia.thumbnailUrl,
                            contentDescription = "封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .clickable { onSaveCoverDirectly() },
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = strings.saveCoverAction,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentMedia.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = currentMedia.siteName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (currentMedia.isGif) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "GIF 动图",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (currentMedia.durationText.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentMedia.durationText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentMedia.author.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentMedia.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 多视频 / 合集切换与选择模式
            if (isMultiVideo) {
                Spacer(modifier = Modifier.height(14.dp))
                // 分段切换栏：下载单集 VS 下载合集
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isCollectionMode = false },
                        color = if (!isCollectionMode) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (!isCollectionMode) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (!isCollectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.downloadSingleMode,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (!isCollectionMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isCollectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isCollectionMode = true },
                        color = if (isCollectionMode) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (isCollectionMode) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isCollectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${strings.downloadCollectionMode} (${metadata.multiMediaList.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCollectionMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCollectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isCollectionMode) {
                    // 单集选择模式横栏
                    val sectionTitle = if (metadata.siteName == "哔哩哔哩") {
                        strings.switchBilibiliPartPrompt
                    } else {
                        strings.switchMultiVideoPrompt
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(metadata.multiMediaList) { index, sub ->
                            val isSelected = index == selectedMultiMediaIndex
                            val chipName = if (sub.title.contains(" - P")) "P${index + 1}" else String.format(strings.videoPartLabel, index + 1)
                            MultiMediaChip(
                                label = chipName,
                                duration = sub.durationText,
                                isGif = sub.isGif,
                                isSelected = isSelected,
                                onClick = { onSelectMultiMedia(index) }
                            )
                        }
                    }
                } else {
                    // 合集批量多选模式
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(strings.selectedEpisodesCount, selectedEpisodes.size, metadata.multiMediaList.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedEpisodes = metadata.multiMediaList.indices.toSet()
                                }
                            ) {
                                Text(
                                    text = strings.selectAll,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedEpisodes = metadata.multiMediaList.indices.filter { it !in selectedEpisodes }.toSet()
                                }
                            ) {
                                Text(
                                    text = strings.invertSelection,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable {
                                    selectedEpisodes = emptySet()
                                }
                            ) {
                                Text(
                                    text = strings.deselectAll,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // 合集逐集勾选列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(metadata.multiMediaList) { index, item ->
                            val isChecked = index in selectedEpisodes
                            val pNum = if (item.title.contains(" - P")) "P${index + 1}" else "${index + 1}"
                            EpisodeCheckItem(
                                indexLabel = pNum,
                                title = item.title,
                                duration = item.durationText,
                                isChecked = isChecked,
                                onToggle = {
                                    selectedEpisodes = if (isChecked) {
                                        selectedEpisodes - index
                                    } else {
                                        selectedEpisodes + index
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // 模式切换 Tab 卡片头部：左侧为“下载模式”，同行最右侧为“保存封面”可开关的选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.downloadType,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (currentMedia.thumbnailUrl.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { saveCoverWithDownload = !saveCoverWithDownload }
                            .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = strings.saveCover,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (saveCoverWithDownload) FontWeight.Bold else FontWeight.Normal,
                            color = if (saveCoverWithDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = saveCoverWithDownload,
                            onCheckedChange = { saveCoverWithDownload = it },
                            modifier = Modifier.scale(0.75f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeTabChip(
                    modifier = Modifier.weight(1f),
                    title = strings.tabVideo,
                    icon = Icons.Default.Videocam,
                    isSelected = selectedType == DownloadType.VIDEO_WITH_AUDIO,
                    onClick = { onSelectType(DownloadType.VIDEO_WITH_AUDIO) }
                )
                ModeTabChip(
                    modifier = Modifier.weight(1f),
                    title = strings.tabMute,
                    icon = Icons.Default.VolumeOff,
                    isSelected = selectedType == DownloadType.VIDEO_ONLY,
                    onClick = { onSelectType(DownloadType.VIDEO_ONLY) }
                )
                ModeTabChip(
                    modifier = Modifier.weight(1f),
                    title = strings.tabAudio,
                    icon = Icons.Default.Audiotrack,
                    isSelected = selectedType == DownloadType.AUDIO_ONLY,
                    onClick = { onSelectType(DownloadType.AUDIO_ONLY) }
                )
                ModeTabChip(
                    modifier = Modifier.weight(1f),
                    title = strings.tabGif,
                    icon = Icons.Default.Gif,
                    isSelected = selectedType == DownloadType.GIF,
                    onClick = { onSelectType(DownloadType.GIF) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 模式对应选项内容区域
            when (selectedType) {
                DownloadType.VIDEO_WITH_AUDIO -> {
                    Text(
                        text = strings.selectResolutionWithAudio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentMedia.availableVideoFormats.forEach { format ->
                            FormatRowItem(
                                format = format,
                                isSelected = selectedVideoFormat?.formatId == format.formatId,
                                onClick = { onSelectVideoFormat(format) }
                            )
                        }
                    }
                }
                DownloadType.VIDEO_ONLY -> {
                    Text(
                        text = strings.selectResolutionMute,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentMedia.availableVideoFormats.forEach { format ->
                            FormatRowItem(
                                format = format,
                                isSelected = selectedVideoFormat?.formatId == format.formatId,
                                onClick = { onSelectVideoFormat(format) }
                            )
                        }
                    }
                }
                DownloadType.AUDIO_ONLY -> {
                    Text(
                        text = strings.selectAudioFormat,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFormat.values().forEach { audio ->
                            AudioFormatRowItem(
                                audio = audio,
                                isSelected = selectedAudioFormat == audio,
                                onClick = { onSelectAudioFormat(audio) }
                            )
                        }
                    }
                }
                DownloadType.GIF -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.gifEngineTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = strings.gifEngineDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                else -> {}
            }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // 固定吸底操作栏 (Sticky Footer)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // 确认下载按钮
                    if (isCollectionMode) {
                        val count = selectedEpisodes.size
                        val resLabel = selectedVideoFormat?.resolutionLabel ?: ""
                        val coverSuffix = if (saveCoverWithDownload) " + ${strings.badgeCover}" else ""
                        val batchBtnText = when (selectedType) {
                            DownloadType.AUDIO_ONLY -> String.format(strings.batchDownloadAudio, count, selectedAudioFormat.ext.uppercase()) + coverSuffix
                            DownloadType.GIF -> String.format(strings.batchDownloadGif, count) + coverSuffix
                            else -> String.format(strings.batchDownloadVideos, count, resLabel) + coverSuffix
                        }

                        Button(
                            onClick = {
                                if (selectedEpisodes.isNotEmpty()) {
                                    onDownloadSelectedMultiMedia(selectedEpisodes, saveCoverWithDownload)
                                }
                            },
                            enabled = selectedEpisodes.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.DownloadForOffline, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedEpisodes.isNotEmpty()) batchBtnText else strings.pleaseSelectEpisode,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onDownloadSelectedMultiMedia(metadata.multiMediaList.indices.toSet(), saveCoverWithDownload)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = String.format(strings.downloadAllCount, metadata.multiMediaList.size) + coverSuffix,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Button(
                            onClick = { onConfirmDownload(saveCoverWithDownload) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            val resLabel = selectedVideoFormat?.resolutionLabel ?: ""
                            val currentEpLabel = if (currentMedia.title.contains(" - P")) {
                                val pNum = currentMedia.title.substringAfter(" - P").substringBefore(" ")
                                "P$pNum · "
                            } else ""
                            val coverSuffix = if (saveCoverWithDownload) " + ${strings.badgeCover}" else ""
                            val buttonText = when (selectedType) {
                                DownloadType.VIDEO_WITH_AUDIO -> "${strings.startDownload} ($currentEpLabel$resLabel$coverSuffix)"
                                DownloadType.VIDEO_ONLY -> "${strings.tabMute} ($currentEpLabel$resLabel$coverSuffix)"
                                DownloadType.AUDIO_ONLY -> "${strings.tabAudio} ($currentEpLabel${selectedAudioFormat.ext.uppercase()}$coverSuffix)"
                                DownloadType.GIF -> "${strings.startDownload} (GIF$coverSuffix)"
                                else -> strings.startDownload
                            }
                            Text(
                                text = buttonText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (metadata.multiMediaList.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { isCollectionMode = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = String.format(strings.switchToCollectionMode, metadata.multiMediaList.size),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiMediaChip(
    label: String,
    duration: String,
    isGif: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(width = if (isSelected) 1.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (duration.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isGif) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Text(
                        text = "GIF",
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTabChip(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FormatRowItem(
    format: FormatOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(width = if (isSelected) 1.5.dp else 0.8.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = format.resolutionLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (format.fps > 30) {
                    val codecPart = if (format.note.isNotEmpty()) " · ${format.note}" else ""
                    Text(
                        text = "${format.fps} FPS · ${format.ext.uppercase()}$codecPart",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (format.note.isNotEmpty()) {
                    Text(
                        text = format.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (format.approximateSize.isNotEmpty()) {
            Text(
                text = format.approximateSize,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AudioFormatRowItem(
    audio: AudioFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(width = if (isSelected) 1.5.dp else 0.8.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = audio.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EpisodeCheckItem(
    indexLabel: String,
    title: String,
    duration: String,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            color = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = indexLabel,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isChecked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isChecked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (duration.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
