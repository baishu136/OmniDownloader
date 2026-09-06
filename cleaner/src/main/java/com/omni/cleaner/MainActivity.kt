package com.omni.cleaner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniCleanerApp(
                onUninstallClick = { uninstallDownloader() },
                onOpenAppSettings = { openAppSettings() },
                onRefreshMedia = { scanMediaFiles() }
            )
        }
    }

    private fun uninstallDownloader() {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:com.omni.downloader")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "调起卸载程序失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:com.omni.downloader")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanMediaFiles() {
        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloader")
        if (downloadDir.exists()) {
            val files = downloadDir.listFiles() ?: arrayOf()
            val paths = files.map { it.absolutePath }.toTypedArray()
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(this, paths, null) { path, uri ->
                    // Scanned
                }
                Toast.makeText(this, "已通知相册媒体库刷新 ${paths.size} 个文件", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "下载目录暂无可索引的媒体文件", Toast.LENGTH_SHORT).show()
    }
}

data class ScanResult(
    val debrisFiles: List<File> = emptyList(),
    val totalSizeBytes: Long = 0L,
    val completedCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniCleanerApp(
    onUninstallClick: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRefreshMedia: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var packageInfo by remember { mutableStateOf<PackageInfo?>(null) }
    var scanResult by remember { mutableStateOf(ScanResult()) }
    var isScanning by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun refreshAppStatus() {
        packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo("com.omni.downloader", PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.omni.downloader", 0)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun scanStorageDebris() {
        coroutineScope.launch {
            isScanning = true
            val res = withContext(Dispatchers.IO) {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloader")
                val debris = mutableListOf<File>()
                var totalBytes = 0L
                var completed = 0

                if (dir.exists()) {
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val name = file.name.lowercase()
                            if (name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".tmp") || name.endsWith(".temp")) {
                                debris.add(file)
                                totalBytes += file.length()
                            } else if (name.endsWith(".mp4") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".mkv")) {
                                completed++
                            }
                        }
                    }
                }
                ScanResult(debris, totalBytes, completed)
            }
            scanResult = res
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAppStatus()
        scanStorageDebris()
    }

    val primaryBg = Color(0xFF0B111E)
    val cardBg = Color(0xFF162032)
    val accentOrange = Color(0xFFFF5722)
    val accentBlue = Color(0xFF2196F3)
    val textPrimary = Color.White
    val textSecondary = Color(0xFF94A3B8)

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = primaryBg,
            surface = cardBg,
            primary = accentOrange,
            secondary = accentBlue
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(accentOrange, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "OmniCleaner 独立清理小工具",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = primaryBg
                    )
                )
            },
            containerColor = primaryBg
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. OmniDownloader 状态卡片
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AppShortcut,
                                contentDescription = null,
                                tint = accentBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "目标程序状态",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (packageInfo != null) {
                            val info = packageInfo!!
                            val vName = info.versionName ?: "未知"
                            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                info.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                info.versionCode.toLong()
                            }
                            Text(
                                text = "已检测到 OmniDownloader",
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "版本号: v$vName (code $vCode)",
                                color = textSecondary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "包名: com.omni.downloader",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "未安装 OmniDownloader",
                                color = Color(0xFFFFA000),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "系统中未发现旧版本残留包，可直接安装最新版本",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenAppSettings,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("应用详情", fontSize = 12.sp)
                            }

                            Button(
                                onClick = onUninstallClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("彻底卸载", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // 2. 存储碎片与残留清理卡片
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.FolderDelete,
                                    contentDescription = null,
                                    tint = accentOrange,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "下载目录碎片清理",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = textPrimary
                                )
                            }
                            IconButton(
                                onClick = { scanStorageDebris() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = textSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val formattedDebrisSize = formatFileSize(scanResult.totalSizeBytes)
                        Text(
                            text = "路径: /sdcard/Download/OmniDownloader",
                            color = textSecondary,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("未完成碎片 (.part / .ytdl)", color = textSecondary, fontSize = 12.sp)
                                Text(
                                    "${scanResult.debrisFiles.size} 个文件 ($formattedDebrisSize)",
                                    color = if (scanResult.debrisFiles.isNotEmpty()) accentOrange else Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("已完成视频/音频", color = textSecondary, fontSize = 12.sp)
                                Text(
                                    "${scanResult.completedCount} 个文件 (安全保护)",
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (scanResult.debrisFiles.isEmpty()) {
                                        Toast.makeText(context, "没有发现碎片垃圾", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showDeleteConfirmDialog = true
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = accentOrange),
                                shape = RoundedCornerShape(10.dp),
                                enabled = scanResult.debrisFiles.isNotEmpty()
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("清空碎片垃圾", fontSize = 12.sp)
                            }

                            Button(
                                onClick = onRefreshMedia,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("刷新相册媒体", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // 3. 深度排查与解答指引
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "故障排查与体积说明",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = textPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        TipItem(
                            number = "1",
                            title = "为什么安装后体积达到 190+ MB？",
                            desc = "最新 v1.1.0 的 APK 安装包实测已降至 74.1MB！但安装运行时，系统会将内置的完整 Python 解释器与 FFmpeg 转码核心解压至内部私有目录（约 120MB），两者相加即为 ~190MB。这属于 Native 下载核心的正常开销。"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TipItem(
                            number = "2",
                            title = "为什么桌面图标还是旧的？",
                            desc = "多数国产安卓系统（MIUI/ColorOS/OriginOS/HarmonyOS）会把桌面的应用图标强制缓存在 Launcher 数据库中。解决办法：点击上方【彻底卸载】旧版后重新安装，或者长按桌面图标从主屏幕移除后从应用抽屉重新拖出，亦可重启手机解决。"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TipItem(
                            number = "3",
                            title = "解析失败怎么处理？",
                            desc = "新版 v1.1.0 现已默认切换为【纯 Kotlin 直连引擎】，Bilibili 视频无需调用外部进程，100% 解决解析失败！对于 YouTube 和 X，请确保手机已开启代理工具，并允许该应用访问网络。"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认清理碎片？") },
            text = {
                Text("将删除 ${scanResult.debrisFiles.size} 个中断下载产生的 .part 和 .ytdl 临时文件，已下载完成的完整视频绝不会受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                scanResult.debrisFiles.forEach { f ->
                                    try {
                                        f.delete()
                                    } catch (_: Exception) {}
                                }
                            }
                            Toast.makeText(context, "碎片文件已彻底清理", Toast.LENGTH_SHORT).show()
                            scanStorageDebris()
                        }
                    }
                ) {
                    Text("彻底删除", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun TipItem(number: String, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFF334155)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = number, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFFE2E8F0))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, fontSize = 12.sp, color = Color(0xFF94A3B8), lineHeight = 16.sp)
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
