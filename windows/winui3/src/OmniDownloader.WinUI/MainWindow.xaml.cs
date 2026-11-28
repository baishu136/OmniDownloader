using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using OmniDownloader.WinUI.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;
using OmniDownloader.WinUI.Views;

namespace OmniDownloader.WinUI;

/// <summary>
/// 应用主窗口：整合 Mica/Acrylic 窗口背景、系统主题强调色同步、NavigationView 页面切换与后端初始化
/// </summary>
public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<string, Type> PageRoutes = new()
    {
        ["home"] = typeof(HomePage),
        ["batch"] = typeof(BatchPage),
        ["tasks"] = typeof(TasksPage),
        ["rules"] = typeof(RulesPage),
        ["settings"] = typeof(SettingsPage)
    };

    public MainViewModel Vm => AppServices.Current.MainVm;

    private bool _updatingNav;
    private bool _isClosed;
    private bool _allowRealClose;
    private bool _isExiting;
    private bool _isClosingPromptOpen;
    private bool _onboardingAutoChecked;
    private TrayIconManager? _trayIcon;
    private ClipboardWatcherService? _clipboardWatcher;
    private BorderlessWindowChromeHelper? _win10ChromeHelper;

    public MainWindow()
    {
        InitializeComponent();

        AppServices.Current.AttachUiDispatcher(DispatcherQueue);
        AppServices.Current.Dialogs.Attach(this);

        _win10ChromeHelper = NativeShellHelper.ConfigureWindowChrome(
            this,
            AppTitleBar,
            win10ResizeLayer: Win10ResizeLayer,
            onMaximizedChanged: isMax =>
            {
                if (Win10MaxGlyph is not null)
                {
                    Win10MaxGlyph.Glyph = isMax ? "\uE923" : "\uE922";
                }
            });
        if (_win10ChromeHelper is not null)
        {
            Win10CaptionButtonsPanel.Visibility = Visibility.Visible;
            AppTitleBar.Padding = new Thickness(14, 0, 0, 0);
        }
        var titleLogo = Path.Combine(AppContext.BaseDirectory, "Assets", "logo.png");
        if (File.Exists(titleLogo))
        {
            TitleAppIcon.Source = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new Uri(titleLogo));
        }
        ApplyCurrentTheme();

        try
        {
            _trayIcon = new TrayIconManager(
                () => AppServices.Current.LocalState.Current.EnableWebService,
                () => DispatcherQueue.TryEnqueue(() =>
                {
                    _hiddenToTray = false;
                    TrayIconManager.RestoreWindow(this);
                    Vm.SetWindowActiveOrVisible(true);
                }),
                () => DispatcherQueue.TryEnqueue(() =>
                {
                    if (AppServices.Current.LocalState.Current.EnableWebService)
                    {
                        Vm.SettingsVm.OpenWebCompanionCommand.Execute(null);
                    }
                }),
                () => DispatcherQueue.TryEnqueue(() => _ = PerformFullExitAsync()),
                () => (Vm.RulesVm.RulesSystemEnabled && Vm.RulesVm.ClipboardWatchEnabled, Vm.RulesVm.ClipboardPaused),
                () => DispatcherQueue.TryEnqueue(() => _ = Vm.RulesVm.ToggleClipboardPauseAsync()));
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("TrayIconManager.Init", ex);
        }

        try
        {
            _clipboardWatcher = new ClipboardWatcherService(DispatcherQueue, HandleClipboardCandidateTextAsync);
            NativeShellHelper.ClipboardTextCopied += OnAppCopiedClipboardText;
            Vm.RulesVm.ClipboardPolicyChanged += OnClipboardPolicyChanged;
            Vm.TrayBalloonRequested += OnTrayBalloonRequested;
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("ClipboardWatcher.Init", ex);
        }

        RootGrid.ActualThemeChanged += (_, _) =>
        {
            if (ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged(AppServices.Current.LocalState.Current.Theme))
            {
                ApplyCurrentTheme();
            }
        };
        Vm.ThemeAppearanceChanged += (_, _) => ApplyCurrentTheme();
        Vm.NavigationRequested += Vm_NavigationRequested;
        Vm.OnboardingRequested += (_, _) => _ = ShowOnboardingDialogAsync();
        Vm.WebServiceModeChanged += (_, _) => _trayIcon?.RefreshTooltip();
        AppWindow.Closing += AppWindow_Closing;
        AppWindow.Changed += AppWindow_Changed;
        Closed += MainWindow_Closed;

        ContentFrame.Navigate(typeof(HomePage));
        RootGrid.Loaded += (_, _) =>
        {
            Localized.RefreshTree(RootGrid);
            if (!_onboardingAutoChecked)
            {
                _onboardingAutoChecked = true;
                if (!AppServices.Current.LocalState.Current.HasCompletedOnboarding)
                {
                    _ = ShowOnboardingDialogAsync();
                }
            }
        };
        AppServices.Current.Loc.LanguageChanged += OnWindowLanguageChanged;
        MainNavView.Loaded += (_, _) => ScheduleNavPillUpdate(animate: false);
        MainNavView.LayoutUpdated += MainNavView_InitialLayoutUpdated;
        // 先让首帧窗口完成绘制，再立即异步连接本地后台，防止冷启动探测感官卡顿
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Normal, () =>
        {
            if (!_isClosed) _ = Vm.InitializeAsync();
        });
    }

    private async void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (_allowRealClose) return;
        args.Cancel = true;

        if (_isExiting || _isClosingPromptOpen) return;

        var st = AppServices.Current.LocalState.Current;
        if (st.AlwaysRememberCloseBehavior)
        {
            if (string.Equals(st.SavedCloseBehavior, "tray", StringComparison.OrdinalIgnoreCase))
            {
                _hiddenToTray = true;
                Vm.SetWindowActiveOrVisible(false);
                TrayIconManager.HideToTray(this);
                return;
            }
            if (string.Equals(st.SavedCloseBehavior, "exit", StringComparison.OrdinalIgnoreCase))
            {
                await PerformFullExitAsync();
                return;
            }
        }

        _isClosingPromptOpen = true;
        try
        {
            var result = await AppServices.Current.Dialogs.ShowCloseAppPromptAsync(k => AppServices.Current.Loc.T(k));
            if (result.Decision == CloseAppDecision.Cancel) return;

            if (result.RememberChoice)
            {
                st.AlwaysRememberCloseBehavior = true;
                st.SavedCloseBehavior = result.Decision == CloseAppDecision.Exit ? "exit" : "tray";
                AppServices.Current.LocalState.Save();
                Vm.SettingsVm.RefreshFromLocalState();
            }

            if (result.Decision == CloseAppDecision.MinimizeToTray)
            {
                _hiddenToTray = true;
                Vm.SetWindowActiveOrVisible(false);
                TrayIconManager.HideToTray(this);
            }
            else if (result.Decision == CloseAppDecision.Exit)
            {
                await PerformFullExitAsync();
            }
        }
        finally
        {
            _isClosingPromptOpen = false;
        }
    }

    private async Task ShowOnboardingDialogAsync()
    {
        if (_isClosed || _isExiting) return;
        try
        {
            var dialog = new OnboardingDialog(AppServices.Current);
            await AppServices.Current.Dialogs.ShowCustomDialogAsync(dialog);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("MainWindow.Onboarding", ex);
        }
    }

    private async Task PerformFullExitAsync()
    {
        if (_isExiting) return;
        _isExiting = true;
        _allowRealClose = true;

        try
        {
            TrayIconManager.HideToTray(this);
            _trayIcon?.Dispose();
            _trayIcon = null;

            var stopAssociated = !AppServices.Current.LocalState.Current.EnableWebService;
            await AppServices.Current.ShutdownAsync(stopAssociatedBackend: stopAssociated);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("MainWindow.PerformFullExitAsync", ex);
        }
        finally
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                try { Close(); } catch { }
            });
        }
    }

    private volatile bool _hiddenToTray;

    private void AppWindow_Changed(Microsoft.UI.Windowing.AppWindow sender, Microsoft.UI.Windowing.AppWindowChangedEventArgs args)
    {
        if (_isClosed) return;
        if (args.DidPresenterChange || args.DidVisibilityChange)
        {
            var isMinimized = sender.Presenter is Microsoft.UI.Windowing.OverlappedPresenter op
                && op.State == Microsoft.UI.Windowing.OverlappedPresenterState.Minimized;
            if (sender.IsVisible && !isMinimized)
            {
                _hiddenToTray = false;
            }
            Vm.SetWindowActiveOrVisible(sender.IsVisible && !isMinimized && !_hiddenToTray);
        }
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        AppWindow.Changed -= AppWindow_Changed;
        MainNavView.LayoutUpdated -= MainNavView_InitialLayoutUpdated;
        AppServices.Current.Loc.LanguageChanged -= OnWindowLanguageChanged;
        NativeShellHelper.ClipboardTextCopied -= OnAppCopiedClipboardText;
        Vm.RulesVm.ClipboardPolicyChanged -= OnClipboardPolicyChanged;
        Vm.TrayBalloonRequested -= OnTrayBalloonRequested;
        _clipboardWatcher?.Dispose();
        _clipboardWatcher = null;
        _trayIcon?.Dispose();
        _trayIcon = null;
        Vm.PrepareShutdown();
    }

    private void OnAppCopiedClipboardText(string text) => _clipboardWatcher?.SuppressEcho(text);

    private void OnTrayBalloonRequested(object? sender, (string Title, string Message, bool IsWarning) e)
    {
        DispatcherQueue.TryEnqueue(() => _trayIcon?.ShowBalloonNotice(e.Title, e.Message, e.IsWarning));
    }

    private void OnClipboardPolicyChanged(object? sender, RuleSettingsDto policy)
    {
        var active = policy.RulesSystemEnabled && policy.ClipboardWatchEnabled;
        _clipboardWatcher?.UpdatePolicy(active, policy.ClipboardPaused, policy.ClipboardDebounceMs, policy.ClipboardDedupWindowSec);
        _trayIcon?.RefreshTooltip();
    }

    private async Task HandleClipboardCandidateTextAsync(string candidateText)
    {
        if (_isClosed || !Vm.IsBackendConnected || !Vm.RulesVm.RulesSystemEnabled || !Vm.RulesVm.ClipboardWatchEnabled || Vm.RulesVm.ClipboardPaused)
        {
            return;
        }
        try
        {
            var resp = await AppServices.Current.ApiClient.ExecuteRulesAsync(
                candidateText,
                trigger: "clipboard",
                ruleMode: "auto",
                clientSource: "clipboard").ConfigureAwait(false);
            if (!resp.Matched) return;

            var queuedCount = resp.Results.Where(r => r.Status == "queued").Sum(r => Math.Max(r.PlannedCount, r.TaskIds.Count));
            var confirmCount = resp.Results.Count(r => r.Status == "confirm_required");
            var parseOnlyItems = resp.Results.Where(r => r.Status == "parse_only").ToList();

            if (queuedCount > 0)
            {
                var ruleNames = string.Join(" / ", resp.Results.Where(r => r.Status == "queued").Select(r => r.RuleName).Distinct());
                var msg = AppServices.Current.Loc.T("剪贴板规则「%s」已自动入队 %d 个任务", ruleNames, queuedCount);
                Vm.Notify(msg, InfoBarSeverity.Success);
                if (Vm.RulesVm.ClipboardShowTrayNotice)
                {
                    Vm.RaiseTrayBalloon(AppServices.Current.Loc.T("OmniDownloader · 自动下载已添加"), msg, false);
                }
                _ = Vm.RefreshTasksAsync();
            }
            if (confirmCount > 0)
            {
                _ = Vm.RulesVm.LoadOverviewAsync();
            }
            if (parseOnlyItems.Count > 0)
            {
                var firstUrl = parseOnlyItems[0].Url;
                Vm.RunOnUi(() =>
                {
                    Vm.HomeVm.FillAndAnalyze(firstUrl);
                });
            }
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("ClipboardWatcher.Execute", ex);
        }
    }

    private void OnWindowLanguageChanged(object? sender, string e)
    {
        if (_isClosed) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!_isClosed) Localized.RefreshTree(RootGrid);
        });
    }

    private void ApplyCurrentTheme()
    {
        if (_isClosed || NativeShellHelper.IsApplyingThemeRefresh) return;
        var state = AppServices.Current.LocalState.Current;
        NativeShellHelper.ApplyThemeAndTitleBarColors(
            this,
            state.Theme,
            state.UsePureBlackDarkMode,
            state.AccentColor,
            state.BackdropStyle,
            enableBackdrop: true,
            navView: MainNavView);
    }

    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var compact = e.NewSize.Width < 580;
        TitleAppNameText.Visibility = e.NewSize.Width < 360 ? Visibility.Collapsed : Visibility.Visible;
        TitleBadge.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusText.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusDot.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;

        if (_win10ChromeHelper is not null)
        {
            AppTitleBar.Padding = compact ? new Thickness(12, 0, 0, 0) : new Thickness(14, 0, 0, 0);
            ScheduleNavPillUpdate(animate: false);
            return;
        }

        // 动态按真实标题栏系统按钮右留白计算内边距，避免高 DPI 窄窗口溢出到关闭/最大化按钮下方
        double rightInset = 148;
        try
        {
            if (Microsoft.UI.Windowing.AppWindowTitleBar.IsCustomizationSupported())
            {
                var scale = RootGrid.XamlRoot?.RasterizationScale ?? 1.0;
                if (scale > 0 && AppWindow.TitleBar.RightInset > 0)
                {
                    rightInset = Math.Max(142, Math.Ceiling(AppWindow.TitleBar.RightInset / scale) + 6);
                }
            }
        }
        catch
        {
        }

        AppTitleBar.Padding = compact
            ? new Thickness(12, 0, rightInset, 0)
            : new Thickness(14, 0, Math.Max(148, rightInset), 0);
        ScheduleNavPillUpdate(animate: false);
    }

    private void MainNavView_DisplayModeChanged(NavigationView sender, NavigationViewDisplayModeChangedEventArgs args)
    {
        // Minimal 模式下汉堡按钮悬浮于内容区左上角，需给页面让出顶部 42px 安全区
        ContentFrame.Padding = args.DisplayMode == NavigationViewDisplayMode.Minimal
            ? new Thickness(0, 42, 0, 0)
            : new Thickness(0);
        ScheduleNavPillUpdate(animate: false);
    }

    private void MainNavView_PaneStateChanged(NavigationView sender, object args) => ScheduleNavPillUpdate(animate: false);

    private void Vm_NavigationRequested(object? sender, (string PageKey, string? SettingsCategory) e)
    {
        NavigateToPage(e.PageKey);
    }

    private void MainNavView_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_updatingNav) return;
        if (args.SelectedItem is NavigationViewItem item && item.Tag is string key && PageRoutes.ContainsKey(key))
        {
            Vm.SelectedNavKey = key;
            NavigateToPage(key);
        }
    }

    private string _currentNavKey = "home";

    private static int NavOrder(string key) => key switch
    {
        "home" => 0,
        "batch" => 1,
        "tasks" => 2,
        "rules" => 3,
        "settings" => 4,
        _ => 0
    };

    private void NavigateToPage(string key)
    {
        if (!PageRoutes.TryGetValue(key, out var pageType)) return;

        _updatingNav = true;
        try
        {
            var navItem = key switch
            {
                "batch" => NavBatch,
                "tasks" => NavTasks,
                "rules" => NavRules,
                "settings" => NavSettings,
                _ => NavHome
            };
            if (!ReferenceEquals(MainNavView.SelectedItem, navItem))
            {
                MainNavView.SelectedItem = navItem;
            }
        }
        finally
        {
            _updatingNav = false;
        }

        if (ContentFrame.CurrentSourcePageType != pageType)
        {
            _currentNavKey = key;
            ContentFrame.Navigate(pageType, null, new EntranceNavigationTransitionInfo());
            ScheduleNavPillUpdate(animate: true);
        }
        else
        {
            _currentNavKey = key;
            ScheduleNavPillUpdate(animate: false);
        }
    }

    private bool _hasPositionedNavPill;

    private void MainNavView_InitialLayoutUpdated(object? sender, object e)
    {
        if (_hasPositionedNavPill || _isClosed) return;
        UpdateNavSelectionPill(animate: false);
    }

    private void ScheduleNavPillUpdate(bool animate)
    {
        if (_isClosed) return;
        DispatcherQueue.TryEnqueue(
            animate ? Microsoft.UI.Dispatching.DispatcherQueuePriority.Normal : Microsoft.UI.Dispatching.DispatcherQueuePriority.Low,
            () => UpdateNavSelectionPill(animate));
    }

    private NavigationViewItem ResolveNavItem(string key) => key switch
    {
        "batch" => NavBatch,
        "tasks" => NavTasks,
        "rules" => NavRules,
        "settings" => NavSettings,
        _ => NavHome
    };

    private void UpdateNavSelectionPill(bool animate)
    {
        if (_isClosed || NavSelectionPill is null || NavIndicatorCanvas is null) return;
        if (MainNavView.DisplayMode == NavigationViewDisplayMode.Minimal && !MainNavView.IsPaneOpen)
        {
            NavSelectionPill.Opacity = 0;
            return;
        }

        var item = ResolveNavItem(_currentNavKey);
        if (item.ActualHeight <= 0 || item.ActualWidth <= 0) return;

        try
        {
            var pt = item.TransformToVisual(NavIndicatorCanvas).TransformPoint(new Windows.Foundation.Point(0, 0));
            if (double.IsNaN(pt.X) || double.IsNaN(pt.Y)) return;

            var targetX = Math.Round(pt.X + 4.0);
            var targetY = pt.Y + Math.Max(0, (item.ActualHeight - NavSelectionPill.Height) * 0.5);

            var currentY = NavPillTranslate.Y;
            var pillVisual = Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.GetElementVisual(NavSelectionPill);
            Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.SetIsTranslationEnabled(NavSelectionPill, true);

            if (!animate || !_hasPositionedNavPill || NativeShellHelper.SuppressNonEssentialAnimations)
            {
                pillVisual.StopAnimation("Translation");
                pillVisual.StopAnimation("Scale");
                pillVisual.StopAnimation("Opacity");
                pillVisual.Opacity = 1f;
                pillVisual.Properties.InsertVector3("Translation", System.Numerics.Vector3.Zero);
                pillVisual.Scale = System.Numerics.Vector3.One;
                NavPillTranslate.X = targetX;
                NavPillTranslate.Y = targetY;
                NavPillScale.ScaleY = 1.0;
                NavSelectionPill.Opacity = 1.0;
                if (!_hasPositionedNavPill)
                {
                    _hasPositionedNavPill = true;
                    MainNavView.LayoutUpdated -= MainNavView_InitialLayoutUpdated;
                }
                return;
            }

            NavPillTranslate.X = targetX;
            var distance = Math.Abs(targetY - currentY);
            if (distance < 0.5)
            {
                NavPillTranslate.Y = targetY;
                NavPillScale.ScaleY = 1.0;
                NavSelectionPill.Opacity = 1.0;
                return;
            }

            // 通过 DWM GPU Composition Visual 驱动指示条位移与微拉伸动画，高刷新率屏下零 UI 线程帧开销
            var compositor = pillVisual.Compositor;
            var durationMs = Math.Clamp(165 + (int)Math.Round(distance * 0.12), 175, 250);
            var duration = TimeSpan.FromMilliseconds(durationMs);
            var decelerate = compositor.CreateCubicBezierEasingFunction(
                new System.Numerics.Vector2(0.1f, 0.9f),
                new System.Numerics.Vector2(0.2f, 1.0f));
            var stretchEase = compositor.CreateCubicBezierEasingFunction(
                new System.Numerics.Vector2(0.3f, 0.0f),
                new System.Numerics.Vector2(0.5f, 1.0f));

            var deltaY = (float)(currentY - targetY);
            NavPillTranslate.Y = targetY;
            NavPillScale.ScaleY = 1.0;

            var startOffset = new System.Numerics.Vector3(0f, deltaY, 0f);
            pillVisual.Properties.InsertVector3("Translation", startOffset);
            var moveAnim = compositor.CreateVector3KeyFrameAnimation();
            moveAnim.InsertKeyFrame(0f, startOffset);
            moveAnim.InsertKeyFrame(1f, System.Numerics.Vector3.Zero, decelerate);
            moveAnim.Duration = duration;
            pillVisual.StartAnimation("Translation", moveAnim);

            var stretchScale = (float)Math.Min(1.42, 1.12 + distance / 360.0);
            var cx = (float)(NavSelectionPill.Width * 0.5);
            var cy = (float)(NavSelectionPill.Height * 0.5);
            pillVisual.CenterPoint = new System.Numerics.Vector3(cx, cy, 0f);
            var scaleAnim = compositor.CreateVector3KeyFrameAnimation();
            scaleAnim.InsertKeyFrame(0f, System.Numerics.Vector3.One);
            scaleAnim.InsertKeyFrame(0.34f, new System.Numerics.Vector3(1f, stretchScale, 1f), stretchEase);
            scaleAnim.InsertKeyFrame(1f, System.Numerics.Vector3.One, decelerate);
            scaleAnim.Duration = duration;
            pillVisual.StartAnimation("Scale", scaleAnim);

            if (NavSelectionPill.Opacity < 0.99)
            {
                NavSelectionPill.Opacity = 1.0;
                pillVisual.Opacity = 0f;
                var fadeAnim = compositor.CreateScalarKeyFrameAnimation();
                fadeAnim.InsertKeyFrame(0f, 0f);
                fadeAnim.InsertKeyFrame(1f, 1f, decelerate);
                fadeAnim.Duration = TimeSpan.FromMilliseconds(120);
                pillVisual.StartAnimation("Opacity", fadeAnim);
            }
            else
            {
                pillVisual.Opacity = 1f;
            }
        }
        catch
        {
        }
    }

    private async void NavHelp_Tapped(object sender, TappedRoutedEventArgs e)
    {
        try
        {
            await AppServices.Current.Dialogs.ShowMessageAsync(
                AppServices.Current.Loc.T("帮助与快捷指南"),
                AppServices.Current.Loc.T("1. 在首页粘贴主流视频或图文分享文本，按回车或点击「立即解析」，即可选择 4K/8K 清晰度、纯音频或无水印图文；\n2. 如需登录解锁高画质或受限内容，可前往「设置 → 账号与 Cookie」完成浏览器登录或导入 Cookie；\n3. 批量下载支持粘贴最多 100 条去重链接或原生导入 TXT 文件，自动排队提交；\n4. 本 WinUI 3 客户端自动共享 %LOCALAPPDATA%\\OmniDownloader 配置、Cookie 与下载记录，且可直接连接已在 58000 端口运行的核心服务，或自动静默伴随启动本地引擎。"),
                AppServices.Current.Loc.T("关闭"));
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("NavHelp_Tapped", ex);
        }
    }

    private async void ReconnectBtn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await Vm.ReconnectBackendAsync();
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("ReconnectBtn_Click", ex);
        }
    }

    private void Win10MinBtn_Click(object sender, RoutedEventArgs e) => _win10ChromeHelper?.Minimize();

    private void Win10MaxBtn_Click(object sender, RoutedEventArgs e) => _win10ChromeHelper?.ToggleMaximizeRestore();

    private void Win10CloseBtn_Click(object sender, RoutedEventArgs e)
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        if (hwnd != IntPtr.Zero)
        {
            // 发送标准 WM_CLOSE，完整保留既有的「最小化到托盘 / 退出应用」确认弹窗逻辑
            NativeShellHelper.SendWindowClose(hwnd);
        }
        else
        {
            try { Close(); } catch { }
        }
    }
}
