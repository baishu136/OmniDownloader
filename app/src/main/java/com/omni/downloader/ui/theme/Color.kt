package com.omni.downloader.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// 设计令牌：品牌色与语义色
// 参照 Mihon 的克制型 Material 3 配色：低饱和主色 + 中性表面 + 明确的状态色
// ============================================================================

// 品牌主色（蓝紫）——用于选中态、关键操作与强调信息
val BrandPrimaryLight = Color(0xFF3B5BDB)
val BrandPrimaryDark = Color(0xFFB3C5FF)

// 语义色（深色模式下自动使用更亮的变体）
val SuccessGreen = Color(0xFF10B981)
val SuccessGreenDark = Color(0xFF4ADE80)
val WarningOrange = Color(0xFFF59E0B)
val WarningOrangeDark = Color(0xFFFBBF24)
val ErrorRed = Color(0xFFEF4444)
val ErrorRedDark = Color(0xFFFF8A80)

// ============================================================================
// 亮色配色
// ============================================================================
val LightPrimary = Color(0xFF3B5BDB)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE3FF)
val LightOnPrimaryContainer = Color(0xFF0A1A5C)

val LightSecondary = Color(0xFF5A5F72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE3E4F2)
val LightOnSecondaryContainer = Color(0xFF171A25)

val LightTertiary = Color(0xFF725572)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD7F6)
val LightOnTertiaryContainer = Color(0xFF2A132B)

val LightBackground = Color(0xFFF7F7FB)
val LightOnBackground = Color(0xFF1A1B21)
val LightSurface = Color(0xFFF7F7FB)
val LightOnSurface = Color(0xFF1A1B21)
val LightSurfaceVariant = Color(0xFFE7E7F0)
val LightOnSurfaceVariant = Color(0xFF46464F)
val LightOutline = Color(0xFF767680)
val LightOutlineVariant = Color(0xFFC8C8D2)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightInverseSurface = Color(0xFF2F3036)
val LightInverseOnSurface = Color(0xFFF1F0F7)
val LightInversePrimary = Color(0xFFB3C5FF)

// ============================================================================
// 暗色配色
// ============================================================================
val DarkPrimary = Color(0xFFB3C5FF)
val DarkOnPrimary = Color(0xFF0A2A6B)
val DarkPrimaryContainer = Color(0xFF2C4A9E)
val DarkOnPrimaryContainer = Color(0xFFDCE3FF)

val DarkSecondary = Color(0xFFC2C5DD)
val DarkOnSecondary = Color(0xFF2B2F3F)
val DarkSecondaryContainer = Color(0xFF414559)
val DarkOnSecondaryContainer = Color(0xFFDEE0F4)

val DarkTertiary = Color(0xFFE1BAD9)
val DarkOnTertiary = Color(0xFF41263F)
val DarkTertiaryContainer = Color(0xFF5A3D57)
val DarkOnTertiaryContainer = Color(0xFFFFD7F6)

val DarkBackground = Color(0xFF111318)
val DarkOnBackground = Color(0xFFE3E2E8)
val DarkSurface = Color(0xFF111318)
val DarkOnSurface = Color(0xFFE3E2E8)
val DarkSurfaceVariant = Color(0xFF2A2C33)
val DarkOnSurfaceVariant = Color(0xFFC6C6D0)
val DarkOutline = Color(0xFF90909A)
val DarkOutlineVariant = Color(0xFF46464F)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkInverseSurface = Color(0xFFE3E2E8)
val DarkInverseOnSurface = Color(0xFF2F3036)
val DarkInversePrimary = Color(0xFF3B5BDB)

// ============================================================================
// 纯黑 (AMOLED) 配色：仅替换背景与表面，保持主色一致
// ============================================================================
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF050507)
val AmoledSurfaceVariant = Color(0xFF16171C)

// ============================================================================
// 平台品牌色（供首页平台标签与卡片图标使用，保持不变）
// ============================================================================
val YouTubeRed = Color(0xFFFF0000)
val BilibiliPink = Color(0xFFFB7299)
val TwitterBlue = Color(0xFF1DA1F2)
val TikTokCyan = Color(0xFF00F2FE)
val DouyinBlack = Color(0xFF2B2B36)
val XiaohongshuRed = Color(0xFFFF2442)
val KuaishouOrange = Color(0xFFFF5000)
val InstagramPink = Color(0xFFE1306C)
val FacebookBlue = Color(0xFF1877F2)
val PinterestRed = Color(0xFFE60023)

// 兼容旧引用
val Indigo80 = DarkPrimary
val IndigoGrey80 = DarkSecondary
val Cyan80 = Color(0xFFA5E6F5)
val Indigo40 = LightPrimary
val IndigoGrey40 = LightSecondary
val Cyan40 = Color(0xFF0284C7)
