package com.omni.downloader.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.animation.Animator
import android.animation.ValueAnimator
import android.util.Log
import android.view.View
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.omni.downloader.R
import com.omni.downloader.OmniApp
import com.omni.downloader.ui.theme.LaunchTheme
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import com.omni.downloader.engine.AndroidAppUpdates
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
    private var splashFade: Animator? = null
    private var cancelFirstDrawWork: (() -> Unit)? = null
    private var cancelSplashStart: (() -> Unit)? = null
    private var finishSplash: (() -> Unit)? = null
    private var foreground = false
    private var pendingLaunchTheme: String? = null
    private var appliedLaunchTheme: String? = null
    internal var launchExitFinished = false
        private set

    private fun schedulePostExitWork() {
        if (!foreground || !launchExitFinished) return
        val app = application as OmniApp
        if (app.hasStartedWarmup && pendingLaunchTheme == appliedLaunchTheme) return
        cancelFirstDrawWork?.invoke()
        cancelFirstDrawWork = afterFirstDraw(window.decorView) {
            cancelFirstDrawWork = null
            if (foreground && launchExitFinished) {
                Log.d("OmniStartup", "Post-exit frame t=${SystemClock.uptimeMillis()}")
                pendingLaunchTheme?.let { mode ->
                    if (mode != appliedLaunchTheme) {
                        LaunchTheme.persistForNextLaunch(this, mode)
                        appliedLaunchTheme = mode
                    }
                }
                app.warmDownloadEngine()
            }
        }
    }

    private fun attachAutomaticCapture() {
        if (automaticDouyin != null) return
        automaticDouyin = com.omni.downloader.ui.components.AndroidDouyinPageCapture(this).also {
            com.omni.downloader.engine.DouyinPageCapture.attach(it)
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        attachAutomaticCapture()
        schedulePostExitWork()
    }
    override fun onPause() {
        foreground = false
        cancelFirstDrawWork?.invoke()
        cancelFirstDrawWork = null
        automaticDouyin?.let { com.omni.downloader.engine.DouyinPageCapture.detach(it); it.close() }
        automaticDouyin = null
        super.onPause()
    }

    override fun onDestroy() {
        foreground = false
        cancelSplashStart?.invoke()
        cancelSplashStart = null
        cancelFirstDrawWork?.invoke()
        cancelFirstDrawWork = null
        splashFade?.cancel()
        splashFade = null
        finishSplash?.invoke()
        automaticDouyin?.let { com.omni.downloader.engine.DouyinPageCapture.detach(it); it.close() }
        automaticDouyin = null
        super.onDestroy()
    }

    override fun onStop() {
        foreground = false
        cancelSplashStart?.invoke()
        cancelSplashStart = null
        cancelFirstDrawWork?.invoke()
        cancelFirstDrawWork = null
        splashFade?.end()
        splashFade = null
        finishSplash?.invoke()
        super.onStop()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchTime = SystemClock.uptimeMillis()
        val mode = LaunchTheme.readMode(this)
        setTheme(if (savedInstanceState == null) LaunchTheme.startingStyle(mode) else LaunchTheme.contentStyle(mode))
        val launchSplash = if (savedInstanceState == null) installSplashScreen() else {
            null
        }
        super.onCreate(savedInstanceState)
        // The OS may present an activity frame before transferring its splash view.
        // Hide only that handoff frame; reveal the page beneath the received opaque
        // overlay before preparing the cross-scale transition.
        val launchContent = findViewById<View>(android.R.id.content)
        if (launchSplash != null) launchContent.alpha = 0f
        attachAutomaticCapture()
        val coldLaunch = (application as OmniApp).launchSession.claimColdLaunch(savedInstanceState != null)
        launchExitFinished = launchSplash == null
        launchSplash?.setKeepOnScreenCondition { coldLaunch && SystemClock.uptimeMillis() - launchTime < SPLASH_HOLD_MS }
        // Own the exit on every OS, including Android 12's window-shift animation.
        launchSplash?.setOnExitAnimationListener { provider ->
            var removed = false
            // A native warm-launch splash may have no icon; page scaling still works.
            val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (provider.view as? android.window.SplashScreenView)?.iconView
            } else provider.iconView
            val contentLayer = launchContent.layerType
            val iconLayer = icon?.layerType
            val finish = {
                if (!removed) {
                    removed = true
                    launchContent.alpha = 1f
                    launchContent.scaleX = 1f
                    launchContent.scaleY = 1f
                    icon?.scaleX = 1f
                    icon?.scaleY = 1f
                    launchContent.setLayerType(contentLayer, null)
                    if (icon != null && iconLayer != null) icon.setLayerType(iconLayer, null)
                    provider.remove()
                    splashFade = null
                    finishSplash = null
                    launchExitFinished = true
                    Log.d("OmniStartup", "Fade end t=${SystemClock.uptimeMillis()}")
                    schedulePostExitWork()
                }
            }
            finishSplash = finish
            launchContent.alpha = 1f
            if (!foreground || isFinishing || isDestroyed) {
                finish()
                return@setOnExitAnimationListener
            }
            val animate = ValueAnimator.areAnimatorsEnabled()
            if (animate) prepareSplashTransition(launchContent, icon)
            if (launchContent.isHardwareAccelerated) {
                // Cache the two scaled views so text and the icon are not
                // rasterized at a different size on every frame.
                launchContent.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                launchContent.buildLayer()
                icon?.let {
                    if (it.isHardwareAccelerated && it.width > 0 && it.height > 0) {
                        it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        it.buildLayer()
                    }
                }
            }
            // Render the initial scale beneath the opaque overlay before starting.
            cancelSplashStart = afterCommittedFrame(window.decorView) {
                cancelSplashStart = null
                Log.d("OmniStartup", "Fade start t=${SystemClock.uptimeMillis()}")
                splashFade = startSplashTransition(
                    content = launchContent,
                    splash = provider.view,
                    icon = icon,
                    animationsEnabled = ValueAnimator.areAnimatorsEnabled() && foreground && !isFinishing && !isDestroyed,
                    removeSplash = finish
                )
            }
        }

        // 内容延伸到系统栏之下，配合 Scaffold 的 Insets 消费实现沉浸式布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            val currentLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LaunchedEffect(themeMode) {
                pendingLaunchTheme = themeMode
                schedulePostExitWork()
            }
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val appStrings = remember(currentLanguage) { resolveAppStrings(currentLanguage) }
            // ViewModel 产生的提示文案在发出时就按当前语言本地化。
            LaunchedEffect(appStrings) { viewModel.updateStrings(appStrings) }

            CompositionLocalProvider(LocalAppStrings provides appStrings) {
                OmniDownloaderTheme(darkTheme = darkTheme) {
                    val snackbarHostState = remember { SnackbarHostState() }
                    AndroidUpdateNotice(appStrings)
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
                    val protection by com.omni.downloader.engine.RequestProtectionNotices.warnings.collectAsStateWithLifecycle()
                    val noticeScope=rememberCoroutineScope()
                    protection.firstOrNull()?.let{warning->
                        com.omni.downloader.ui.components.RequestProtectionDialog(warning,
                            onAcknowledge={noticeScope.launch{com.omni.downloader.engine.RequestProtectionNotices.acknowledge(warning)}},
                            onAccount={noticeScope.launch{
                                com.omni.downloader.engine.RequestProtectionNotices.acknowledge(warning)
                                viewModel.requestedSessionPlatform.value=if(warning.platform=="pixiv")"Pixiv" else "X (Twitter)"
                                currentTab=2
                            }})
                    }

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

    @Composable
    private fun AndroidUpdateNotice(strings: AppStrings) {
        var updateVersion by rememberSaveable { mutableStateOf<String?>(null) }
        var updateUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var browserUnavailable by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (updateVersion != null) return@LaunchedEffect
            try {
                AndroidAppUpdates.checkAutomatically(applicationContext)?.let {
                    updateVersion = it.version
                    updateUrl = it.releaseUrl
                }
            } catch (_: java.io.IOException) {
                // Offline launches stay quiet and can check again on the next launch.
            } catch (_: org.json.JSONException) {
                // A malformed release response is not a verified update.
            }
        }

        val version = updateVersion ?: return
        val url = updateUrl ?: return
        val dismiss = {
            updateVersion = null
            updateUrl = null
            browserUnavailable = false
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(strings.androidUpdateTitle.format(version)) },
            text = { Text(if (browserUnavailable) strings.browserUnavailable else strings.androidUpdateBody) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        dismiss()
                    } catch (_: ActivityNotFoundException) {
                        browserUnavailable = true
                    }
                }) { Text(strings.androidUpdateRelease) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(strings.close) } },
        )
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
                NavItem(3, Icons.AutoMirrored.Outlined.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay, appStrings.navBatch),
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
