using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.System;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// WinUI 3 原生内置 WebView2 辅助浏览提取窗口：
/// 当连接到无 pywebview 壳子的纯服务端模式时，提供本地网页验证播放与媒体 DOM 读取能力。
/// </summary>
public sealed partial class BrowserAssistWindow : Window
{
    private readonly AppServices _services;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcher;
    private string _initialUrl;
    private bool _isClosed;
    private bool? _isNarrowToolbar;

    public BrowserAssistWindow(AppServices services, string initialUrl)
    {
        _services = services;
        _initialUrl = NormalizeUrl(initialUrl);
        InitializeComponent();
        _dispatcher = DispatcherQueue;

        _services.RegisterSecondaryWindow(this);
        Closed += BrowserAssistWindow_Closed;
        Title = $"OmniDownloader · {_services.Loc.T("内置浏览器辅助提取")}";
        _services.Loc.LanguageChanged += OnLanguageChanged;

        var icoPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icoPath)) AppWindow.SetIcon(icoPath);
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1060, 780));
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

        AddressBox.Text = _initialUrl;
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
        var narrow = e.NewSize.Width < 760;
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
            if (_dispatcher.HasThreadAccess) Title = $"OmniDownloader · {_services.Loc.T("内置浏览器辅助提取")}";
            else _dispatcher.TryEnqueue(() => { if (!_isClosed) Title = $"OmniDownloader · {_services.Loc.T("内置浏览器辅助提取")}"; });
        }
        catch { }
    }

    private void BrowserAssistWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        _services.Loc.LanguageChanged -= OnLanguageChanged;
        _services.MainVm.ThemeAppearanceChanged -= OnThemeChanged;
        try { AssistWebView.Close(); } catch { }
    }

    private static string NormalizeUrl(string raw)
    {
        var trimmed = (raw ?? string.Empty).Trim();
        if (string.IsNullOrEmpty(trimmed)) return "https://www.douyin.com";
        if (!trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private async Task InitWebViewAsync()
    {
        try
        {
            await WebViewEnvironmentHelper.EnsureInitializedAsync(AssistWebView, _services);
            if (_isClosed || AssistWebView.CoreWebView2 is null) return;
            AssistWebView.CoreWebView2.DownloadStarting += (coreSender, dlArgs) =>
            {
                // 客户端内置浏览器严禁落入 Edge 浏览器默认下载记录，全部拦截转由本地任务引擎直存
                dlArgs.Cancel = true;
                dlArgs.Handled = true;
                var dlUri = dlArgs.DownloadOperation?.Uri;
                if (!string.IsNullOrWhiteSpace(dlUri) &&
                    Uri.TryCreate(dlUri, UriKind.Absolute, out var parsed) &&
                    (parsed.Scheme == Uri.UriSchemeHttp || parsed.Scheme == Uri.UriSchemeHttps))
                {
                    var candidate = new BrowserAssistCandidate
                    {
                        Url = dlUri,
                        Title = Path.GetFileNameWithoutExtension(dlArgs.ResultFilePath ?? string.Empty),
                        SourcePage = coreSender.Source ?? AssistWebView.Source?.ToString() ?? string.Empty
                    };
                    _dispatcher.TryEnqueue(() =>
                    {
                        if (!_isClosed)
                        {
                            _ = _services.MainVm.HomeVm.ConfirmAndDownloadAssistVideoAsync(candidate);
                        }
                    });
                }
            };
            AssistWebView.NavigationCompleted += (_, _) =>
            {
                if (!_isClosed)
                {
                    AddressBox.Text = AssistWebView.Source?.ToString() ?? _initialUrl;
                }
            };
            AssistWebView.Source = new Uri(_initialUrl);
        }
        catch (Exception ex)
        {
            if (!_isClosed)
            {
                AssistStatusBar.Severity = InfoBarSeverity.Error;
                AssistStatusBar.Message = _services.Loc.T("初始化 WebView2 运行时失败，请确认本机已安装 Microsoft Edge WebView2：%s", ex.Message);
            }
        }
    }

    private void NavigateAddress()
    {
        var url = NormalizeUrl(AddressBox.Text);
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            AssistWebView.Source = uri;
        }
    }

    private void AddressBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter)
        {
            NavigateAddress();
            e.Handled = true;
        }
    }

    private void GoBtn_Click(object sender, RoutedEventArgs e) => NavigateAddress();

    private void ReloadBtn_Click(object sender, RoutedEventArgs e) => AssistWebView.Reload();

    private async void CaptureVideoBtn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            const string js = """
                JSON.stringify(
                  Array.from(document.querySelectorAll('video'))
                    .filter(v => v.played.length > 0 && v.currentSrc && /^https?:/i.test(v.currentSrc))
                    .map(v => ({
                      url: v.currentSrc,
                      width: v.videoWidth || 0,
                      height: v.videoHeight || 0,
                      duration: Number.isFinite(v.duration) ? v.duration : 0,
                      title: document.title || ''
                    }))
                )
                """;

            var rawJson = await AssistWebView.ExecuteScriptAsync(js);
            var innerJson = JsonSerializer.Deserialize<string>(rawJson) ?? "[]";
            var items = JsonSerializer.Deserialize<List<BrowserAssistCandidate>>(innerJson) ?? new();

            if (items.Count == 0)
            {
                AssistStatusBar.Severity = InfoBarSeverity.Warning;
                AssistStatusBar.Message = _services.Loc.T("尚未发现可保存的已播放 HTTP/HTTPS 视频源；blob/MSE 流或未开始播放的视频不适用。");
                return;
            }

            var candidate = items[0];
            candidate.SourcePage = AssistWebView.Source?.ToString() ?? string.Empty;
            AssistStatusBar.Severity = InfoBarSeverity.Success;
            AssistStatusBar.Message = _services.Loc.T("已读取播放源，请在主窗口确认加入下载队列。");

            await _services.MainVm.HomeVm.ConfirmAndDownloadAssistVideoAsync(candidate);
        }
        catch (Exception ex)
        {
            AssistStatusBar.Severity = InfoBarSeverity.Error;
            AssistStatusBar.Message = _services.Loc.T("读取已播放媒体失败: %s", ex.Message);
        }
    }

    private async void CaptureImagesBtn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            const string js = """
                JSON.stringify({
                  title: document.title || '网页图文捕获',
                  url: location.href,
                  images: Array.from(document.querySelectorAll('img'))
                    .map((img, i) => ({
                      id: 'web_' + i,
                      url: img.currentSrc || img.src || '',
                      width: img.naturalWidth || 0,
                      height: img.naturalHeight || 0
                    }))
                    .filter(x => /^https?:/i.test(x.url) && x.width >= 320 && x.height >= 320)
                    .filter((x, idx, arr) => arr.findIndex(y => y.url === x.url) === idx)
                    .slice(0, 60)
                })
                """;

            var rawJson = await AssistWebView.ExecuteScriptAsync(js);
            var innerJson = JsonSerializer.Deserialize<string>(rawJson) ?? "{}";
            using var doc = JsonDocument.Parse(innerJson);
            var root = doc.RootElement;
            var imgs = root.GetProperty("images");

            if (imgs.GetArrayLength() == 0)
            {
                AssistStatusBar.Severity = InfoBarSeverity.Warning;
                AssistStatusBar.Message = _services.Loc.T("当前页面未检测到尺寸不小于 320×320 的公开 HTTPS 图片。");
                return;
            }

            var post = new ImagePost
            {
                Platform = _services.Loc.T("网页捕获"),
                Title = root.GetProperty("title").GetString() ?? _services.Loc.T("网页图文"),
                Url = root.GetProperty("url").GetString() ?? string.Empty,
                Route = "winui_webview2"
            };
            foreach (var el in imgs.EnumerateArray())
            {
                var u = el.GetProperty("url").GetString() ?? string.Empty;
                post.Resources.Add(new ImageResource
                {
                    Id = el.GetProperty("id").GetString() ?? Guid.NewGuid().ToString("N"),
                    Url = u,
                    PreviewUrl = u,
                    Width = el.GetProperty("width").GetInt32(),
                    Height = el.GetProperty("height").GetInt32(),
                    Kind = "IMAGE",
                    Original = true
                });
            }

            _services.MainVm.RunOnUi(() =>
            {
                _services.MainVm.HomeVm.LoadImagePost(post);
                _services.MainVm.Notify("已从网页提取 %d 张图片", InfoBarSeverity.Success, post.Resources.Count);
            });
            Close();
        }
        catch (Exception ex)
        {
            AssistStatusBar.Severity = InfoBarSeverity.Error;
            AssistStatusBar.Message = _services.Loc.T("提取页面图文失败: %s", ex.Message);
        }
    }
}
