using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using OmniDownloader.WinUI.Controls;
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

    public MainWindow()
    {
        InitializeComponent();

        AppServices.Current.AttachUiDispatcher(DispatcherQueue);
        AppServices.Current.Dialogs.Attach(this);

        NativeShellHelper.ConfigureWindowChrome(this, AppTitleBar);
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
                () => DispatcherQueue.TryEnqueue(() => TrayIconManager.RestoreWindow(this)),
                () => DispatcherQueue.TryEnqueue(() =>
                {
                    if (AppServices.Current.LocalState.Current.EnableWebService)
                    {
                        Vm.SettingsVm.OpenWebCompanionCommand.Execute(null);
                    }
                }),
                () => DispatcherQueue.TryEnqueue(() => _ = PerformFullExitAsync()));
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("TrayIconManager.Init", ex);
        }

        RootGrid.ActualThemeChanged += (_, _) => ApplyCurrentTheme();
        Vm.ThemeAppearanceChanged += (_, _) => ApplyCurrentTheme();
        Vm.NavigationRequested += Vm_NavigationRequested;
        Vm.OnboardingRequested += (_, _) => _ = ShowOnboardingDialogAsync();
        Vm.WebServiceModeChanged += (_, _) => _trayIcon?.RefreshTooltip();
        AppWindow.Closing += AppWindow_Closing;
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

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        AppServices.Current.Loc.LanguageChanged -= OnWindowLanguageChanged;
        _trayIcon?.Dispose();
        _trayIcon = null;
        Vm.PrepareShutdown();
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
        NativeShellHelper.ApplyThemeAndTitleBarColors(this, state.Theme, state.UsePureBlackDarkMode, state.AccentColor);
    }

    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var compact = e.NewSize.Width < 580;
        TitleAppNameText.Visibility = e.NewSize.Width < 360 ? Visibility.Collapsed : Visibility.Visible;
        TitleBadge.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusText.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusDot.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;

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
        "settings" => 3,
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
    private Storyboard? _navPillStoryboard;

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
            _navPillStoryboard?.Stop();
            _navPillStoryboard = null;

            if (!animate || !_hasPositionedNavPill)
            {
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

            // 保持流畅连续的 WinUI 11 Decelerate 曲线，并在移动中段施加克制的竖向微拉伸弹性
            var sb = new Storyboard();
            var durationMs = Math.Clamp(185 + (int)Math.Round(distance * 0.14), 195, 275);
            var midTime = KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(durationMs * 0.34));
            var endTime = KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(durationMs));

            var moveY = new DoubleAnimationUsingKeyFrames();
            moveY.KeyFrames.Add(new DiscreteDoubleKeyFrame { KeyTime = KeyTime.FromTimeSpan(TimeSpan.Zero), Value = currentY });
            moveY.KeyFrames.Add(new SplineDoubleKeyFrame
            {
                KeyTime = endTime,
                Value = targetY,
                KeySpline = new KeySpline
                {
                    ControlPoint1 = new Windows.Foundation.Point(0.1, 0.9),
                    ControlPoint2 = new Windows.Foundation.Point(0.2, 1.0)
                }
            });
            Storyboard.SetTarget(moveY, NavPillTranslate);
            Storyboard.SetTargetProperty(moveY, "Y");
            sb.Children.Add(moveY);

            var stretchScale = Math.Min(1.42, 1.12 + distance / 360.0);
            var scaleY = new DoubleAnimationUsingKeyFrames();
            scaleY.KeyFrames.Add(new DiscreteDoubleKeyFrame { KeyTime = KeyTime.FromTimeSpan(TimeSpan.Zero), Value = 1.0 });
            scaleY.KeyFrames.Add(new SplineDoubleKeyFrame
            {
                KeyTime = midTime,
                Value = stretchScale,
                KeySpline = new KeySpline
                {
                    ControlPoint1 = new Windows.Foundation.Point(0.3, 0.0),
                    ControlPoint2 = new Windows.Foundation.Point(0.5, 1.0)
                }
            });
            scaleY.KeyFrames.Add(new SplineDoubleKeyFrame
            {
                KeyTime = endTime,
                Value = 1.0,
                KeySpline = new KeySpline
                {
                    ControlPoint1 = new Windows.Foundation.Point(0.1, 0.9),
                    ControlPoint2 = new Windows.Foundation.Point(0.2, 1.0)
                }
            });
            Storyboard.SetTarget(scaleY, NavPillScale);
            Storyboard.SetTargetProperty(scaleY, "ScaleY");
            sb.Children.Add(scaleY);

            if (NavSelectionPill.Opacity < 0.99)
            {
                var fade = new DoubleAnimation
                {
                    From = NavSelectionPill.Opacity,
                    To = 1.0,
                    Duration = new Duration(TimeSpan.FromMilliseconds(120))
                };
                Storyboard.SetTarget(fade, NavSelectionPill);
                Storyboard.SetTargetProperty(fade, "Opacity");
                sb.Children.Add(fade);
            }

            sb.Completed += (_, _) =>
            {
                if (ReferenceEquals(_navPillStoryboard, sb))
                {
                    NavPillTranslate.Y = targetY;
                    NavPillScale.ScaleY = 1.0;
                    NavSelectionPill.Opacity = 1.0;
                    _navPillStoryboard = null;
                }
            };

            _navPillStoryboard = sb;
            sb.Begin();
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
}
