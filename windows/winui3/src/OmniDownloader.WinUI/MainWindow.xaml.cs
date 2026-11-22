using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
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

    public MainWindow()
    {
        InitializeComponent();

        AppServices.Current.AttachUiDispatcher(DispatcherQueue);
        AppServices.Current.Dialogs.Attach(this);

        NativeShellHelper.ConfigureWindowChrome(this, AppTitleBar);
        ApplyCurrentTheme();

        RootGrid.ActualThemeChanged += (_, _) => ApplyCurrentTheme();
        Vm.ThemeAppearanceChanged += (_, _) => ApplyCurrentTheme();
        Vm.NavigationRequested += Vm_NavigationRequested;
        Closed += MainWindow_Closed;

        ContentFrame.Navigate(typeof(HomePage));
        // 先让首帧窗口完成绘制，再立即异步连接本地后台，防止冷启动探测感官卡顿
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Normal, () =>
        {
            if (!_isClosed) _ = Vm.InitializeAsync();
        });
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        Vm.PrepareShutdown();
    }

    private void ApplyCurrentTheme()
    {
        if (_isClosed) return;
        var state = AppServices.Current.LocalState.Current;
        NativeShellHelper.ApplyThemeAndTitleBarColors(this, state.Theme, state.UsePureBlackDarkMode);
    }

    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var compact = e.NewSize.Width < 580;
        TitleBadge.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusText.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        TitleStatusDot.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
        AppTitleBar.Padding = compact ? new Thickness(12, 0, 140, 0) : new Thickness(14, 0, 148, 0);
    }

    private void MainNavView_DisplayModeChanged(NavigationView sender, NavigationViewDisplayModeChangedEventArgs args)
    {
        // Minimal 模式下汉堡按钮悬浮于内容区左上角，需给页面让出顶部 42px 安全区
        ContentFrame.Padding = args.DisplayMode == NavigationViewDisplayMode.Minimal
            ? new Thickness(0, 42, 0, 0)
            : new Thickness(0);
    }

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
            ContentFrame.Navigate(pageType, null, new EntranceNavigationTransitionInfo());
        }
    }

    private async void NavHelp_Tapped(object sender, TappedRoutedEventArgs e)
    {
        try
        {
            await AppServices.Current.Dialogs.ShowMessageAsync(
                AppServices.Current.Loc.T("帮助与快捷指南"),
                "1. 在首页粘贴主流视频或图文分享文本，按回车或点击「立即解析」，即可选择 4K/8K 清晰度、纯音频或无水印图文；\n" +
                "2. 对需要网页登录后验证的内容（小黑盒/抖音/快手），可展开「浏览器辅助提取」启用内置 WebView2 辅助窗读取；\n" +
                "3. 批量下载支持粘贴最多 100 条去重链接或原生导入 TXT 文件，自动排队提交；\n" +
                "4. 本 WinUI 3 客户端自动共享 %LOCALAPPDATA%\\OmniDownloader 配置、Cookie 与下载记录，且可直接连接已在 58000 端口运行的核心服务，或自动静默伴随启动本地引擎。",
                "关闭");
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
