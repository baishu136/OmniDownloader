using System.Globalization;
using System.Text;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// WinUI 3 原生内置哔哩哔哩网页登录窗口：
/// 使用独立 WebView2 配置目录加载哔哩哔哩登录页，登录成功后读取 SESSDATA 等首方 Cookie 导入加密会话库。
/// </summary>
public sealed partial class BilibiliLoginWindow : Window
{
    private const string LoginUrl = "https://passport.bilibili.com/login";
    private const string MainSiteUrl = "https://www.bilibili.com";

    private readonly AppServices _services;
    private readonly DispatcherQueue _dispatcher;
    private DispatcherQueueTimer? _pollTimer;
    private bool _isClosed;
    private bool _isImporting;
    private bool? _isNarrowToolbar;

    public BilibiliLoginWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _dispatcher = DispatcherQueue;

        _services.RegisterSecondaryWindow(this);
        Closed += BilibiliLoginWindow_Closed;
        Title = $"OmniDownloader · {_services.Loc.T("登录哔哩哔哩")}";
        _services.Loc.LanguageChanged += OnLanguageChanged;

        var icoPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icoPath)) AppWindow.SetIcon(icoPath);
        AppWindow.Resize(new Windows.Graphics.SizeInt32(920, 740));
        NativeShellHelper.AttachAntiFlickerGuard(WindowNative.GetWindowHandle(this));
        ApplyTheme();

        RootGrid.ActualThemeChanged += (_, _) =>
        {
            if (ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged(_services.LocalState.Current.Theme))
            {
                ApplyTheme();
            }
        };
        _services.MainVm.ThemeAppearanceChanged += OnThemeChanged;

        _ = InitWebViewAsync();
    }

    private void OnThemeChanged(object? sender, (string Theme, bool PureBlack) e)
    {
        try
        {
            if (_dispatcher.HasThreadAccess) ApplyTheme();
            else _dispatcher.TryEnqueue(ApplyTheme);
        }
        catch
        {
        }
    }

    private void ApplyTheme()
    {
        if (_isClosed || NativeShellHelper.IsApplyingThemeRefresh) return;
        var state = _services.LocalState.Current;
        NativeShellHelper.ApplyThemeAndTitleBarColors(this, state.Theme, state.UsePureBlackDarkMode, state.AccentColor);
    }

    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var narrow = e.NewSize.Width < 720;
        if (_isNarrowToolbar == narrow) return;
        _isNarrowToolbar = narrow;
        Grid.SetRow(ToolbarActionsPanel, narrow ? 1 : 0);
        Grid.SetColumn(ToolbarActionsPanel, narrow ? 0 : 2);
        Grid.SetColumnSpan(ToolbarActionsPanel, narrow ? 3 : 1);
        ToolbarActionsPanel.HorizontalAlignment = narrow ? HorizontalAlignment.Left : HorizontalAlignment.Right;
    }

    private void OnLanguageChanged(object? sender, string e)
    {
        try
        {
            if (_dispatcher.HasThreadAccess) Title = $"OmniDownloader · {_services.Loc.T("登录哔哩哔哩")}";
            else _dispatcher.TryEnqueue(() => { if (!_isClosed) Title = $"OmniDownloader · {_services.Loc.T("登录哔哩哔哩")}"; });
        }
        catch { }
    }

    private void BilibiliLoginWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        _pollTimer?.Stop();
        _pollTimer = null;
        _services.Loc.LanguageChanged -= OnLanguageChanged;
        _services.MainVm.ThemeAppearanceChanged -= OnThemeChanged;
        try { LoginWebView.Close(); } catch { }
    }

    private async Task InitWebViewAsync()
    {
        try
        {
            await WebViewEnvironmentHelper.EnsureInitializedAsync(LoginWebView, _services);
            if (_isClosed || LoginWebView.CoreWebView2 is null) return;

            var core = LoginWebView.CoreWebView2;
            core.DownloadStarting += (coreSender, dlArgs) =>
            {
                dlArgs.Cancel = true;
                dlArgs.Handled = true;
            };
            core.NewWindowRequested += (_, e) =>
            {
                if (!string.IsNullOrWhiteSpace(e.Uri))
                {
                    e.Handled = true;
                    core.Navigate(e.Uri);
                }
            };

            LoginWebView.NavigationCompleted += async (_, args) =>
            {
                if (_isClosed) return;
                if (args.IsSuccess)
                {
                    SetStatus(InfoBarSeverity.Informational, _services.Loc.T("请完成哔哩哔哩登录，检测到 SESSDATA 后将自动保存凭据"));
                    await TryCaptureAndImportAsync(explicitClick: false);
                }
                else
                {
                    SetStatus(InfoBarSeverity.Warning, _services.Loc.T("页面加载未完成，可点击左侧刷新重试或检查网络代理"));
                }
            };

            await ClearBilibiliWebViewCookiesAsync();
            core.Navigate(LoginUrl);
            StartPolling();
        }
        catch (Exception ex)
        {
            if (!_isClosed)
            {
                SetStatus(
                    InfoBarSeverity.Error,
                    _services.Loc.T("初始化 WebView2 运行时失败，请确认本机已安装 Microsoft Edge WebView2：%s", ex.Message));
            }
        }
    }

    private void StartPolling()
    {
        _pollTimer?.Stop();
        var timer = _dispatcher.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(1500);
        timer.Tick += async (_, _) =>
        {
            if (_isClosed || _isImporting) return;
            await TryCaptureAndImportAsync(explicitClick: false);
        };
        _pollTimer = timer;
        timer.Start();
    }

    private async Task ClearBilibiliWebViewCookiesAsync()
    {
        var manager = LoginWebView.CoreWebView2?.CookieManager;
        if (manager is null) return;

        foreach (var target in new string?[] { null, MainSiteUrl, "https://passport.bilibili.com" })
        {
            var cookies = await manager.GetCookiesAsync(target);
            foreach (var c in cookies)
            {
                if (IsBilibiliDomain(c.Domain))
                {
                    manager.DeleteCookie(c);
                }
            }
        }
    }

    private async Task TryCaptureAndImportAsync(bool explicitClick)
    {
        if (_isClosed || _isImporting || LoginWebView.CoreWebView2 is null) return;
        _isImporting = true;
        try
        {
            var manager = LoginWebView.CoreWebView2.CookieManager;
            var merged = new Dictionary<(string Domain, string Path, string Name), CoreWebView2Cookie>();

            foreach (var target in new string?[] { null, MainSiteUrl, "https://passport.bilibili.com" })
            {
                var list = await manager.GetCookiesAsync(target);
                foreach (var c in list)
                {
                    if (!IsBilibiliDomain(c.Domain)) continue;
                    merged[(c.Domain, string.IsNullOrEmpty(c.Path) ? "/" : c.Path, c.Name)] = c;
                }
            }

            var hasSessData = merged.Values.Any(c =>
                string.Equals(c.Name, "SESSDATA", StringComparison.Ordinal) &&
                !string.IsNullOrWhiteSpace(c.Value));

            if (!hasSessData)
            {
                if (explicitClick)
                {
                    SetStatus(InfoBarSeverity.Warning, _services.Loc.T("尚未检测到有效的 SESSDATA 登录凭据，请先在下方网页完成登录"));
                }
                return;
            }

            var netscape = BuildNetscapeCookies(merged.Values);
            if (string.IsNullOrWhiteSpace(netscape))
            {
                if (explicitClick)
                {
                    SetStatus(InfoBarSeverity.Warning, _services.Loc.T("未能导出有效 B站 Cookie，请刷新后重试"));
                }
                return;
            }

            await _services.ApiClient.ImportSessionAsync("哔哩哔哩", netscape);
            try { await _services.ApiClient.SetSessionEnabledAsync("哔哩哔哩", true); } catch { }
            await _services.MainVm.SettingsVm.LoadAllAsync();

            _pollTimer?.Stop();
            _services.MainVm.Notify("B站凭据已通过内置网页自动保存，可重新解析验证超清画质", InfoBarSeverity.Success);
            if (!_isClosed)
            {
                Close();
            }
        }
        catch (Exception ex)
        {
            if (!_isClosed && explicitClick)
            {
                SetStatus(InfoBarSeverity.Error, ex.Message);
            }
        }
        finally
        {
            _isImporting = false;
        }
    }

    private static bool IsBilibiliDomain(string? domain)
    {
        if (string.IsNullOrWhiteSpace(domain)) return false;
        var host = domain.Trim().TrimStart('.').ToLowerInvariant();
        return host == "bilibili.com" || host.EndsWith(".bilibili.com", StringComparison.Ordinal);
    }

    private static string BuildNetscapeCookies(IEnumerable<CoreWebView2Cookie> cookies)
    {
        var nowEpoch = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var sb = new StringBuilder("# Netscape HTTP Cookie File\n");
        var count = 0;

        foreach (var c in cookies)
        {
            var domain = (c.Domain ?? string.Empty).Trim();
            var name = c.Name ?? string.Empty;
            var value = c.Value ?? string.Empty;
            var path = string.IsNullOrEmpty(c.Path) ? "/" : c.Path;
            if (string.IsNullOrEmpty(domain) || string.IsNullOrEmpty(name)) continue;
            if ((domain + name + value + path).IndexOfAny(['\t', '\r', '\n', '\0']) >= 0) continue;

            long expires = 0;
            if (!c.IsSession && double.IsFinite(c.Expires) && c.Expires > 0)
            {
                expires = (long)Math.Floor(c.Expires);
                if (expires <= nowEpoch) continue;
            }

            var includeSubdomains = domain.StartsWith('.') ? "TRUE" : "FALSE";
            var secure = c.IsSecure ? "TRUE" : "FALSE";
            var prefix = c.IsHttpOnly ? "#HttpOnly_" : string.Empty;
            sb.Append(prefix)
              .Append(domain).Append('\t')
              .Append(includeSubdomains).Append('\t')
              .Append(path).Append('\t')
              .Append(secure).Append('\t')
              .Append(expires.ToString(CultureInfo.InvariantCulture)).Append('\t')
              .Append(name).Append('\t')
              .Append(value).Append('\n');
            count++;
        }

        return count > 0 ? sb.ToString() : string.Empty;
    }

    private void SetStatus(InfoBarSeverity severity, string message)
    {
        if (_isClosed) return;
        LoginStatusBar.Severity = severity;
        LoginStatusBar.Message = message;
    }

    private void ReloadBtn_Click(object sender, RoutedEventArgs e)
    {
        try { LoginWebView.Reload(); } catch { }
    }

    private async void ResetSessionBtn_Click(object sender, RoutedEventArgs e)
    {
        await ClearBilibiliWebViewCookiesAsync();
        SetStatus(InfoBarSeverity.Informational, _services.Loc.T("已清空内置网页缓存，请重新登录哔哩哔哩账号"));
        LoginWebView.CoreWebView2?.Navigate(LoginUrl);
    }

    private async void CaptureNowBtn_Click(object sender, RoutedEventArgs e)
    {
        await TryCaptureAndImportAsync(explicitClick: true);
    }
}
