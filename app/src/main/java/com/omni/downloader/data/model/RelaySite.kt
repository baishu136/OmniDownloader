package com.omni.downloader.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 备用中转解析网站数据模型
 */
@Immutable
@Serializable
data class RelaySite(
    val id: String,
    val name: String,
    val url: String,
    val iconUrl: String = ""
)

