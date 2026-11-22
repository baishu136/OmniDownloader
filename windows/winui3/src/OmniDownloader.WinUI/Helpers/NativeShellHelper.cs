using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using Windows.UI;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// Windows 原生壳层能力封装：Mica/Acrylic 背景降级、标题栏配色适配、剪贴板、文件/文件夹选择器与带 LRU 缓存的图片安全加载
/// </summary>
public static class NativeShellHelper
{
    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hWnd);

    private const int MaxCachedImages = 72;
    private const int MaxCachedBytes = 16 * 1024 * 1024;

    private static readonly HttpClient ImageHttp = CreateImageHttpClient();
    private static readonly object CacheLock = new();
    private static readonly Dictionary<string, LinkedListNode<(string Url, byte[] Bytes)>> ImageByteCache = new(StringComparer.Ordinal);
    private static readonly LinkedList<(string Url, byte[] Bytes)> ImageLruList = new();
    private static long _cachedBytesTotal;
    private static readonly ConcurrentDictionary<string, DateTimeOffset> FailedImageUrls = new(StringComparer.Ordinal);
    private static readonly ConcurrentDictionary<string, Task<byte[]?>> InflightImageDownloads = new(StringComparer.Ordinal);

    private static SolidColorBrush? _pureBlackBrush;
    private static SolidColorBrush? _darkBgBrush;
    private static SolidColorBrush? _lightBgBrush;
    private static SolidColorBrush? _transparentBrush;

    private static HttpClient CreateImageHttpClient()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = true,
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
            ConnectTimeout = TimeSpan.FromSeconds(3),
            MaxConnectionsPerServer = 8
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        return client;
    }

    public static void ConfigureWindowChrome(Window window, FrameworkElement titleBarDragRegion, int baseWidth = 1100, int baseHeight = 760)
    {
        var hwnd = WindowNative.GetWindowHandle(window);
        var windowId = Win32Interop.GetWindowIdFromWindow(hwnd);
        var appWindow = AppWindow.GetFromWindowId(windowId);

        // 按显示器真实 DPI 计算默认窗口尺寸并限制在工作区内，避免高分屏过小
        var dpi = GetDpiForWindow(hwnd);
        var scale = dpi > 0 ? dpi / 96.0 : 1.0;
        var targetW = (int)Math.Round(baseWidth * scale);
        var targetH = (int)Math.Round(baseHeight * scale);
        try
        {
            var workArea = DisplayArea.GetFromWindowId(windowId, DisplayAreaFallback.Primary).WorkArea;
            targetW = Math.Min(targetW, Math.Max(720, workArea.Width - 64));
            targetH = Math.Min(targetH, Math.Max(540, workArea.Height - 64));
        }
        catch
        {
        }
        appWindow.Resize(new SizeInt32(targetW, targetH));
        var icoPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icoPath))
        {
            appWindow.SetIcon(icoPath);
        }

        // 优先启用 Windows 11 Mica 材质，若在 Windows 10 上则尝试 Acrylic 或自动回落到主题背景
        try
        {
            if (MicaController.IsSupported())
            {
                window.SystemBackdrop = new MicaBackdrop { Kind = MicaKind.Base };
            }
            else if (DesktopAcrylicController.IsSupported())
            {
                window.SystemBackdrop = new DesktopAcrylicBackdrop();
            }
        }
        catch
        {
            // 部分精简或远程桌面会话不支持 SystemBackdrop 时自动使用默认背景色
        }

        if (AppWindowTitleBar.IsCustomizationSupported())
        {
            window.ExtendsContentIntoTitleBar = true;
            window.SetTitleBar(titleBarDragRegion);
        }
    }

    public static void ApplyThemeAndTitleBarColors(Window window, string themePreference, bool pureBlackDark)
    {
        if (window.Content is not FrameworkElement root) return;

        var desiredTheme = themePreference switch
        {
            "light" => ElementTheme.Light,
            "dark" => ElementTheme.Dark,
            _ => ElementTheme.Default
        };
        if (root.RequestedTheme != desiredTheme)
        {
            root.RequestedTheme = desiredTheme;
        }

        var isDark = root.ActualTheme == ElementTheme.Dark;
        if (root is Microsoft.UI.Xaml.Controls.Panel rootPanel)
        {
            SolidColorBrush targetBrush;
            if (isDark && pureBlackDark)
            {
                targetBrush = _pureBlackBrush ??= new SolidColorBrush(Color.FromArgb(255, 0, 0, 0));
            }
            else if (window.SystemBackdrop is null)
            {
                targetBrush = isDark
                    ? (_darkBgBrush ??= new SolidColorBrush(Color.FromArgb(255, 28, 29, 34)))
                    : (_lightBgBrush ??= new SolidColorBrush(Color.FromArgb(255, 244, 245, 249)));
            }
            else
            {
                targetBrush = _transparentBrush ??= new SolidColorBrush(Colors.Transparent);
            }

            if (!ReferenceEquals(rootPanel.Background, targetBrush))
            {
                rootPanel.Background = targetBrush;
            }
        }

        if (AppWindowTitleBar.IsCustomizationSupported())
        {
            var tb = window.AppWindow.TitleBar;
            tb.ButtonBackgroundColor = Colors.Transparent;
            tb.ButtonInactiveBackgroundColor = Colors.Transparent;
            tb.ButtonForegroundColor = isDark ? Color.FromArgb(255, 240, 242, 248) : Color.FromArgb(255, 24, 28, 36);
            tb.ButtonInactiveForegroundColor = isDark ? Color.FromArgb(255, 140, 148, 165) : Color.FromArgb(255, 110, 118, 130);
            tb.ButtonHoverBackgroundColor = isDark ? Color.FromArgb(30, 255, 255, 255) : Color.FromArgb(20, 0, 0, 0);
            tb.ButtonHoverForegroundColor = tb.ButtonForegroundColor;
        }
    }

    public static async Task<string> ReadClipboardTextAsync()
    {
        try
        {
            var view = Clipboard.GetContent();
            if (view.Contains(StandardDataFormats.Text))
            {
                return (await view.GetTextAsync()).Trim();
            }
        }
        catch
        {
            // 剪贴板瞬时被其他进程占用时返回空
        }
        return string.Empty;
    }

    public static void CopyText(string text)
    {
        var pkg = new DataPackage();
        pkg.SetText(text ?? string.Empty);
        Clipboard.SetContent(pkg);
    }

    public static async Task<string?> PickFolderAsync(Window owner)
    {
        var picker = new FolderPicker
        {
            SuggestedStartLocation = PickerLocationId.Downloads
        };
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(owner));
        var folder = await picker.PickSingleFolderAsync();
        return folder?.Path;
    }

    public static async Task<string?> PickTextFileContentAsync(Window owner, long maxBytes, params string[] extensions)
    {
        var picker = new FileOpenPicker
        {
            SuggestedStartLocation = PickerLocationId.DocumentsLibrary
        };
        foreach (var ext in extensions)
        {
            picker.FileTypeFilter.Add(ext.StartsWith('.') ? ext : "." + ext);
        }
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(owner));
        var file = await picker.PickSingleFileAsync();
        if (file is null) return null;

        var props = await file.GetBasicPropertiesAsync();
        if (props.Size > (ulong)maxBytes)
        {
            throw new InvalidOperationException($"所选文件大小超过限制 ({maxBytes / 1024} KB)");
        }

        return await Windows.Storage.FileIO.ReadTextAsync(file);
    }

    public static void OpenWithShell(string target)
    {
        if (string.IsNullOrWhiteSpace(target)) return;
        Process.Start(new ProcessStartInfo
        {
            FileName = target,
            UseShellExecute = true
        });
    }

    /// <summary>
    /// 使用桌面 HTTP 客户端安全拉取远程或本地预览图片并解码为 BitmapImage：
    /// 内置并发去重、失败短时冷却与有界 LRU 字节缓存，且缩略图按 decodePixelWidth 降采样避免爆显存。
    /// </summary>
    public static async Task<BitmapImage?> LoadImageAsync(string? url, int decodePixelWidth = 0, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        try
        {
            var bytes = await GetCachedOrFetchBytesAsync(url, ct);
            if (bytes is null || bytes.Length == 0 || ct.IsCancellationRequested) return null;
            return await DecodeImageBytesAsync(bytes, decodePixelWidth, ct);
        }
        catch
        {
            return null;
        }
    }

    private static async Task<byte[]?> GetCachedOrFetchBytesAsync(string url, CancellationToken ct)
    {
        if (TryGetCachedImageBytes(url, out var hit))
        {
            return hit;
        }

        if (FailedImageUrls.TryGetValue(url, out var failedAt) && DateTimeOffset.UtcNow - failedAt < TimeSpan.FromSeconds(30))
        {
            return null;
        }

        var downloadTask = InflightImageDownloads.GetOrAdd(url, FetchRemoteBytesCoreAsync);
        try
        {
            var data = await downloadTask.WaitAsync(ct);
            if (data is { Length: > 0 })
            {
                PutCachedImageBytes(url, data);
                FailedImageUrls.TryRemove(url, out _);
            }
            else
            {
                FailedImageUrls[url] = DateTimeOffset.UtcNow;
            }
            return data;
        }
        finally
        {
            if (downloadTask.IsCompleted)
            {
                InflightImageDownloads.TryRemove(new KeyValuePair<string, Task<byte[]?>>(url, downloadTask));
            }
        }
    }

    private static async Task<byte[]?> FetchRemoteBytesCoreAsync(string url)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            if (url.Contains("hdslb.com", StringComparison.OrdinalIgnoreCase) || url.Contains("bilibili.com", StringComparison.OrdinalIgnoreCase))
            {
                req.Headers.Referrer = new Uri("https://www.bilibili.com/");
            }
            using var resp = await ImageHttp.SendAsync(req).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            var bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
            return bytes.Length > 0 ? bytes : null;
        }
        catch
        {
            return null;
        }
    }

    private static bool TryGetCachedImageBytes(string url, out byte[] bytes)
    {
        lock (CacheLock)
        {
            if (ImageByteCache.TryGetValue(url, out var node))
            {
                ImageLruList.Remove(node);
                ImageLruList.AddFirst(node);
                bytes = node.Value.Bytes;
                return true;
            }
        }
        bytes = Array.Empty<byte>();
        return false;
    }

    private static void PutCachedImageBytes(string url, byte[] bytes)
    {
        if (bytes.Length > 4 * 1024 * 1024) return;
        lock (CacheLock)
        {
            if (ImageByteCache.TryGetValue(url, out var existing))
            {
                _cachedBytesTotal -= existing.Value.Bytes.Length;
                ImageLruList.Remove(existing);
                ImageByteCache.Remove(url);
            }

            var node = new LinkedListNode<(string Url, byte[] Bytes)>((url, bytes));
            ImageLruList.AddFirst(node);
            ImageByteCache[url] = node;
            _cachedBytesTotal += bytes.Length;

            while (ImageByteCache.Count > MaxCachedImages || _cachedBytesTotal > MaxCachedBytes)
            {
                var tail = ImageLruList.Last;
                if (tail is null) break;
                ImageLruList.RemoveLast();
                ImageByteCache.Remove(tail.Value.Url);
                _cachedBytesTotal -= tail.Value.Bytes.Length;
            }
        }
    }

    /// <summary>
    /// 无锁读取本地图片文件或图集 ZIP 条目并解码为 BitmapImage（支持 GIF 动图），避免锁定磁盘文件
    /// </summary>
    public static async Task<BitmapImage?> LoadLocalOrZipImageAsync(string? localPath, string? zipPath = null, string? zipEntry = null, CancellationToken ct = default)
    {
        try
        {
            byte[]? bytes = null;
            if (!string.IsNullOrWhiteSpace(localPath) && File.Exists(localPath))
            {
                bytes = await File.ReadAllBytesAsync(localPath, ct);
            }
            else if (!string.IsNullOrWhiteSpace(zipPath) && !string.IsNullOrWhiteSpace(zipEntry) && File.Exists(zipPath))
            {
                bytes = await Task.Run(() =>
                {
                    using var archive = ZipFile.OpenRead(zipPath);
                    var entry = archive.GetEntry(zipEntry);
                    if (entry is null) return null;
                    using var stream = entry.Open();
                    using var ms = new MemoryStream();
                    stream.CopyTo(ms);
                    return ms.ToArray();
                }, ct);
            }

            if (bytes is null || bytes.Length == 0 || ct.IsCancellationRequested) return null;
            return await DecodeImageBytesAsync(bytes, 0, ct);
        }
        catch
        {
            return null;
        }
    }

    public static async Task<BitmapImage?> DecodeImageBytesAsync(byte[]? bytes, int decodePixelWidth = 0, CancellationToken ct = default)
    {
        if (bytes is null || bytes.Length == 0 || ct.IsCancellationRequested) return null;
        try
        {
            var bmp = new BitmapImage();
            if (decodePixelWidth > 0)
            {
                bmp.DecodePixelType = DecodePixelType.Logical;
                bmp.DecodePixelWidth = decodePixelWidth;
            }
            using var mem = new InMemoryRandomAccessStream();
            await mem.WriteAsync(bytes.AsBuffer()).AsTask(ct);
            if (ct.IsCancellationRequested) return null;
            mem.Seek(0);
            await bmp.SetSourceAsync(mem).AsTask(ct);
            return bmp;
        }
        catch
        {
            return null;
        }
    }
}
