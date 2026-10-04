package com.omni.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omni.downloader.engine.UrlSniffer
import com.omni.downloader.ui.screens.HomeScreen
import com.omni.downloader.ui.screens.SettingsScreen
import com.omni.downloader.ui.screens.TasksScreen
import com.omni.downloader.ui.theme.OmniDownloaderTheme
import com.omni.downloader.ui.viewmodel.MainViewModel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.localization.resolveAppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 主页面视差转场柔和缓冲阻尼曲线
private val TransitionCushionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // 申请必要通知权限与存储权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            val currentLanguage by viewModel.appLanguage.collectAsState()
            val appStrings = remember(currentLanguage) { resolveAppStrings(currentLanguage) }

            CompositionLocalProvider(LocalAppStrings provides appStrings) {
                OmniDownloaderTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val snackbarHostState = remember { SnackbarHostState() }
                        val message by viewModel.message.collectAsState()
                        var currentTab by remember { mutableIntStateOf(0) }

                        LaunchedEffect(message) {
                            message?.let {
                                snackbarHostState.showSnackbar(it)
                                viewModel.clearMessage()
                            }
                        }

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                OmniBottomBar(
                                    currentTab = currentTab,
                                    onTabSelected = { currentTab = it },
                                    appStrings = appStrings
                                )
                            }
                        ) { innerPadding ->
                            MainTabContent(
                                currentTabProvider = { currentTab },
                                onSelectTab = { currentTab = it },
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun OmniBottomBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    appStrings: com.omni.downloader.ui.localization.AppStrings
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val navItems = remember(appStrings) {
                listOf(
                    Triple(0, Icons.Default.Home, appStrings.navHome),
                    Triple(1, Icons.Default.Download, appStrings.navTasks),
                    Triple(2, Icons.Default.Settings, appStrings.navSettings)
                )
            }
            navItems.forEach { (tabIndex, icon, desc) ->
                val isSelected = currentTab == tabIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (currentTab != tabIndex) {
                                onTabSelected(tabIndex)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 58.dp, height = 34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = desc,
                            modifier = Modifier.size(26.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTabContent(
    currentTabProvider: () -> Int,
    onSelectTab: (Int) -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab 0: 首页 (持久保活，Placement 延迟阶段瞬间偏移，0 重组、0 重新测量)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val tab = currentTabProvider()
                    IntOffset(if (tab == 0) 0 else -50000, 0)
                }
                .clipToBounds()
                .graphicsLayer {
                    val tab = currentTabProvider()
                    alpha = if (tab == 0) 1f else 0f
                }
        ) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToTasks = { onSelectTab(1) },
                onNavigateToSettings = { onSelectTab(2) }
            )
        }

        // Tab 1: 任务列表 (持久保活，Placement 延迟阶段瞬间偏移，0 重组、0 重新测量)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val tab = currentTabProvider()
                    IntOffset(if (tab == 1) 0 else -50000, 0)
                }
                .clipToBounds()
                .graphicsLayer {
                    val tab = currentTabProvider()
                    alpha = if (tab == 1) 1f else 0f
                }
        ) {
            TasksScreen(viewModel = viewModel)
        }

        // Tab 2: 设置 (持久保活，Placement 延迟阶段瞬间偏移，0 重组、0 重新测量)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val tab = currentTabProvider()
                    IntOffset(if (tab == 2) 0 else -50000, 0)
                }
                .clipToBounds()
                .graphicsLayer {
                    val tab = currentTabProvider()
                    alpha = if (tab == 2) 1f else 0f
                }
        ) {
            SettingsScreen(viewModel = viewModel)
        }
    }
}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = UrlSniffer.extractUrl(sharedText) ?: sharedText
            if (url.isNotBlank()) {
                viewModel.updateInputUrl(url)
                viewModel.startAnalyze(url)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
