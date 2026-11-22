using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.System;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// WinUI 3 原生内置 WebView2 辅助浏览提取窗口：
/// 当连接到无 pywebview 壳子的纯服务端模式时，提供本地网页验证播放与媒体 DOM 读取能力。
/// </summary>
public sealed partial class BrowserAssistWindow : Window
{
    private readonly AppServices _services;
    private string _initialUrl;
    private bool _isClosed;

    public BrowserAssistWindow(AppServices services, string initialUrl)
    {
        _services = services;
        _initialUrl = NormalizeUrl(initialUrl);
        InitializeComponent();

        _services.RegisterSecondaryWindow(this);
        Closed += BrowserAssistWindow_Closed;

        var icoPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icoPath)) AppWindow.SetIcon(icoPath);
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1060, 780));

        AddressBox.Text = _initialUrl;
        _ = InitWebViewAsync();
    }

    private void BrowserAssistWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
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
            await AssistWebView.EnsureCoreWebView2Async();
            if (_isClosed) return;
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
                AssistStatusBar.Message = $"初始化 WebView2 运行时失败，请确认本机已安装 Microsoft Edge WebView2：{ex.Message}";
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
                AssistStatusBar.Message = "尚未发现可保存的已播放 HTTP/HTTPS 视频源；blob/MSE 流或未开始播放的视频不适用。";
                return;
            }

            var candidate = items[0];
            candidate.SourcePage = AssistWebView.Source?.ToString() ?? string.Empty;
            AssistStatusBar.Severity = InfoBarSeverity.Success;
            AssistStatusBar.Message = "已读取播放源，请在主窗口确认加入下载队列。";

            await _services.MainVm.HomeVm.ConfirmAndDownloadAssistVideoAsync(candidate);
        }
        catch (Exception ex)
        {
            AssistStatusBar.Severity = InfoBarSeverity.Error;
            AssistStatusBar.Message = $"读取已播放媒体失败: {ex.Message}";
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
                AssistStatusBar.Message = "当前页面未检测到尺寸不小于 320×320 的公开 HTTPS 图片。";
                return;
            }

            var post = new ImagePost
            {
                Platform = "网页捕获",
                Title = root.GetProperty("title").GetString() ?? "网页图文",
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
                _services.MainVm.Notify($"已从网页提取 {post.Resources.Count} 张图片", InfoBarSeverity.Success);
            });
            Close();
        }
        catch (Exception ex)
        {
            AssistStatusBar.Severity = InfoBarSeverity.Error;
            AssistStatusBar.Message = $"提取页面图文失败: {ex.Message}";
        }
    }
}
