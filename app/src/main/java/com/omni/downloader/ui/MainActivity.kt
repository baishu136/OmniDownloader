package com.omni.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.omni.downloader.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import com.omni.downloader.ui.design.NavigationFillIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.omni.downloader.ui.design.LocalTabActive
import com.omni.downloader.ui.design.RetainedTabHost
import com.omni.downloader.engine.UrlSniffer
import com.omni.downloader.ui.localization.AppStrings
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.localization.resolveAppStrings
import com.omni.downloader.ui.screens.HomeScreen
import com.omni.downloader.ui.screens.SettingsScreen
import com.omni.downloader.ui.screens.TasksScreen
import com.omni.downloader.ui.theme.OmniDownloaderTheme
import com.omni.downloader.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val sharedLinkGeneration = mutableStateOf(0)
    private val sharedTargetTab = mutableStateOf(0)
    private var automaticDouyin: com.omni.downloader.ui.components.AndroidDouyinPageCapture? = null

    private fun attachAutomaticCapture() {
        if (automaticDouyin != null) return
        automaticDouyin = com.omni.downloader.ui.components.AndroidDouyinPageCapture(this).also {
            com.omni.downloader.engine.DouyinPageCapture.attach(it)
        }
    }

    override fun onResume() { super.onResume(); attachAutomaticCapture() }
    override fun onPause() {
        automaticDouyin?.let { com.omni.downloader.engine.DouyinPageCapture.detach(it); it.close() }
        automaticDouyin = null
        super.onPause()
    }

    override fun onDestroy() {
        automaticDouyin?.let { com.omni.downloader.engine.DouyinPageCapture.detach(it); it.close() }
        automaticDouyin = null
        super.onDestroy()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchTime = SystemClock.uptimeMillis()
        val launchSplash = if (savedInstanceState == null) installSplashScreen() else {
            setTheme(R.style.Theme_OmniDownloader)
            null
        }
        super.onCreate(savedInstanceState)
        attachAutomaticCapture()
        launchSplash?.setKeepOnScreenCondition { SystemClock.uptimeMillis() - launchTime < 500L }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            launchSplash?.setOnExitAnimationListener { provider ->
                val content = findViewById<View>(android.R.id.content)
                provider.iconView.translationY = 0f
                content.translationY = 16f * resources.displayMetrics.density
                content.animate().translationY(0f).setDuration(400L)
                    .setInterpolator(PathInterpolator(0f, 0f, 0.2f, 1f)).start()
                provider.view.animate().alpha(0f).setDuration(400L)
                    .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .withEndAction { provider.remove(); content.translationY = 0f }.start()
            }
        }

        // 内容延伸到系统栏之下，配合 Scaffold 的 Insets 消费实现沉浸式布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            val currentLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val appStrings = remember(currentLanguage) { resolveAppStrings(currentLanguage) }

            CompositionLocalProvider(LocalAppStrings provides appStrings) {
                OmniDownloaderTheme(darkTheme = darkTheme) {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val message by viewModel.message.collectAsStateWithLifecycle()
                    var currentTab by rememberSaveable { mutableStateOf(0) }
                    LaunchedEffect(Unit) { if (currentTab !in 0..3) currentTab = 0 }
                    val mediaNavigation by viewModel.mediaNavigation.collectAsStateWithLifecycle()
                    LaunchedEffect(sharedLinkGeneration.value) {
                        if (sharedLinkGeneration.value > 0) currentTab = sharedTargetTab.value
                    }
                    LaunchedEffect(mediaNavigation) {
                        mediaNavigation?.let { request ->
                            // An explicit visit to Tasks/Settings is not stolen by
                            // a slower parse finishing in a retained content tab.
                            if (viewModel.consumeMediaNavigation(request) && currentTab == 0) {
                                currentTab = 0
                            }
                        }
                    }
                    BackHandler(enabled = currentTab != 0) { currentTab = 0 }

                    LaunchedEffect(message) {
                        message?.let {
                            snackbarHostState.showSnackbar(it)
                            viewModel.clearMessage()
                        }
                    }

                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        },
                        bottomBar = {
                            OmniNavigationBar(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it },
                                appStrings = appStrings
                            )
                        }
                    ) { innerPadding ->
                        MainTabContent(
                            currentTab = currentTab,
                            onSelectTab = { currentTab = it },
                            viewModel = viewModel,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    /**
     * 底栏直接使用 M3 NavigationBar / NavigationBarItem（与 Mihon 手机端一致）：
     * 指示器、涟漪和标签保留 M3 动效；图标额外使用与切页同步的填充过渡。
     */
    @Composable
    private fun OmniNavigationBar(
        currentTab: Int,
        onTabSelected: (Int) -> Unit,
        appStrings: AppStrings
    ) {
        val navItems = remember(appStrings) {
            listOf(
                NavItem(0, Icons.Outlined.Home, Icons.Filled.Home, appStrings.navHome),
                NavItem(3, Icons.AutoMirrored.Outlined.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay, "批量"),
                NavItem(1, Icons.Outlined.Download, Icons.Filled.Download, appStrings.navTasks),
                NavItem(2, Icons.Outlined.Settings, Icons.Filled.Settings, appStrings.navSettings)
            )
        }

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            navItems.forEach { item ->
                val selected = currentTab == item.index
                NavigationBarItem(
                    modifier = Modifier.testTag("nav_tab_${item.index}"),
                    selected = selected,
                    onClick = {
                        if (currentTab != item.index) {
                            onTabSelected(item.index)
                        }
                    },
                    icon = {
                        NavigationFillIcon(item.icon, item.selectedIcon, item.label, selected,
                            Modifier.testTag("nav_icon_${item.index}"))
                    },
                    label = {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    alwaysShowLabel = true
                )
            }
        }
    }

    @Composable
    private fun MainTabContent(
        currentTab: Int,
        onSelectTab: (Int) -> Unit,
        viewModel: MainViewModel,
        modifier: Modifier = Modifier
    ) {
        val onNavTasks = remember(onSelectTab) { { onSelectTab(1) } }
        val onNavSettings = remember(onSelectTab) { { onSelectTab(2) } }
        RetainedTabHost(if (currentTab in 0..3) currentTab else 0, 4, modifier.fillMaxSize()) { tab ->
            when (tab) {
                0 -> HomeScreen(viewModel, onNavTasks, onNavSettings, isActive = LocalTabActive.current)
                1 -> TasksScreen(viewModel)
                2 -> SettingsScreen(viewModel)
                3 -> com.omni.downloader.ui.screens.BatchDownloadScreen(viewModel, onNavTasks, onSingle = { onSelectTab(0) })
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
            val sharedLinks = runCatching { com.omni.downloader.engine.BatchInput.links(sharedText) }.getOrDefault(emptyList())
            if (intent.getStringExtra("com.omni.downloader.TARGET") != "images" && sharedLinks.size > 1) {
                viewModel.inputDrafts.update { it.copy(batchText = sharedText.take(100_000), batchManual = false) }
                sharedTargetTab.value = 3; sharedLinkGeneration.value += 1
                return
            }
            val url = UrlSniffer.extractUrl(sharedText) ?: sharedText
            if (url.isNotBlank()) {
                // Explicit share integrations may choose the photo-post workflow without typing
                // the URL through an IME; ordinary shared links retain the video workflow.
                if (intent.getStringExtra("com.omni.downloader.TARGET") == "images") {
                    sharedTargetTab.value = 0
                    viewModel.updateImageInput(url)
                    viewModel.analyzeImages()
                    sharedLinkGeneration.value += 1
                    return
                }
                sharedTargetTab.value = 0
                viewModel.updateInputUrl(url)
                sharedLinkGeneration.value += 1
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

    private data class NavItem(
        val index: Int,
        val icon: ImageVector,
        val selectedIcon: ImageVector,
        val label: String
    )
}
