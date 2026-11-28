using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Services;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using Windows.UI;
using Windows.UI.ViewManagement;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// Windows 原生壳层能力封装：Mica/Acrylic 背景降级、标题栏配色适配、剪贴板、文件/文件夹选择器与带 LRU 缓存的图片安全加载
/// </summary>
public static class NativeShellHelper
{
    private delegate IntPtr SubclassProcDelegate(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam, UIntPtr uIdSubclass, UIntPtr dwRefData);

    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DwmMargins
    {
        public int Left;
        public int Right;
        public int Top;
        public int Bottom;
    }

    private const uint WmEraseBkgnd = 0x0014;
    private const uint WmNcPaint = 0x0085;
    private const uint WmNcActivate = 0x0086;
    private const uint WmNcDestroy = 0x0082;
    private const uint RdwFrame = 0x0400;
    private const uint RdwInvalidate = 0x0001;
    private const uint RdwUpdatenow = 0x0100;
    private const int GclpHbrBackground = -10;
    private const int NullBrush = 5;
    private const int DwmwaUseImmersiveDarkMode = 20;
    private const int DwmwaBorderColor = 34;
    private static readonly UIntPtr AntiFlickerSubclassId = new(0x4F4D4E49);
    private static readonly object SubclassLock = new();
    private static readonly Dictionary<IntPtr, SubclassProcDelegate> SubclassProcs = new();
    private static readonly Dictionary<IntPtr, uint> TopEdgeColorRefs = new();
    private static readonly Dictionary<IntPtr, bool> ExtendedTitleWindows = new();

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    public static void SendWindowClose(IntPtr hwnd)
    {
        if (hwnd != IntPtr.Zero)
        {
            _ = PostMessage(hwnd, 0x0010, IntPtr.Zero, IntPtr.Zero);
        }
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hWnd, out Win32Rect lpRect);

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindowDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [DllImport("user32.dll")]
    private static extern int FillRect(IntPtr hDC, ref Win32Rect lprc, IntPtr hbr);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RedrawWindow(IntPtr hWnd, IntPtr lprcUpdate, IntPtr hrgnUpdate, uint flags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(IntPtr hWnd);

    [DllImport("user32.dll", EntryPoint = "SetClassLongPtrW")]
    private static extern IntPtr SetClassLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    [DllImport("gdi32.dll")]
    private static extern IntPtr GetStockObject(int fnObject);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateSolidBrush(uint crColor);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr hObject);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref uint attrValue, int attrSize);

    [DllImport("dwmapi.dll")]
    private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref DwmMargins pMarInset);

    [DllImport("comctl32.dll", SetLastError = true)]
    private static extern bool SetWindowSubclass(IntPtr hWnd, SubclassProcDelegate pfnSubclass, UIntPtr uIdSubclass, UIntPtr dwRefData);

    [DllImport("comctl32.dll", SetLastError = true)]
    private static extern bool RemoveWindowSubclass(IntPtr hWnd, SubclassProcDelegate pfnSubclass, UIntPtr uIdSubclass);

    [DllImport("comctl32.dll")]
    private static extern IntPtr DefSubclassProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam);

    private const int MaxCachedImages = 72;
    private const int MaxCachedBytes = 16 * 1024 * 1024;
    private const int MaxFailedImageUrls = 256;

    /// <summary>
    /// 窗口最小化或隐藏至托盘期间开启，跳过非必要 UI 过渡动画以将 GPU/CPU 降至最低。
    /// </summary>
    public static volatile bool SuppressNonEssentialAnimations;

    private static readonly HttpClient ImageHttp = CreateImageHttpClient();
    private static readonly object CacheLock = new();
    private static readonly Dictionary<string, LinkedListNode<(string Url, byte[] Bytes)>> ImageByteCache = new(StringComparer.Ordinal);
    private static readonly LinkedList<(string Url, byte[] Bytes)> ImageLruList = new();
    private static long _cachedBytesTotal;
    private static readonly ConcurrentDictionary<string, DateTimeOffset> FailedImageUrls = new(StringComparer.Ordinal);
    private static readonly ConcurrentDictionary<string, Task<byte[]?>> InflightImageDownloads = new(StringComparer.Ordinal);
    private static readonly ConditionalWeakTable<Microsoft.UI.Xaml.Controls.Image, string> BoundImageUrls = new();
    private static readonly ConditionalWeakTable<Microsoft.UI.Xaml.Controls.Image, CancellationTokenSource> BoundImageCts = new();
    private static readonly ConditionalWeakTable<UIElement, Microsoft.UI.Composition.CompositionScopedBatch> ActiveFadeBatches = new();

    /// <summary>
    /// 使用 Windows Composition Visual API 在 DWM GPU 合成线程运行高刷（60/120/144/165/240Hz）淡入与微位移过渡，
    /// 零 UI 线程逐帧回调与布局重算开销；窗口最小化/隐藏时自动静默直显。
    /// </summary>
    public static void PlayFadeIn(UIElement? element, int durationMs = 150, double fromOffsetY = 0, double fromOffsetX = 0)
    {
        if (element is null || element.Visibility != Visibility.Visible) return;
        try
        {
            Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.SetIsTranslationEnabled(element, true);
            var visual = Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.GetElementVisual(element);
            if (ActiveFadeBatches.TryGetValue(element, out var prevBatch))
            {
                ActiveFadeBatches.Remove(element);
                try { prevBatch.Dispose(); } catch { }
            }

            if (SuppressNonEssentialAnimations)
            {
                visual.StopAnimation("Opacity");
                visual.StopAnimation("Translation");
                visual.Opacity = 1f;
                visual.Properties.InsertVector3("Translation", System.Numerics.Vector3.Zero);
                element.Opacity = 1.0;
                return;
            }

            var compositor = visual.Compositor;
            var duration = TimeSpan.FromMilliseconds(Math.Max(60, durationMs));
            var easing = compositor.CreateCubicBezierEasingFunction(
                new System.Numerics.Vector2(0.1f, 0.9f),
                new System.Numerics.Vector2(0.2f, 1.0f));

            element.Opacity = 1.0;
            visual.Opacity = 0f;
            var fadeAnim = compositor.CreateScalarKeyFrameAnimation();
            fadeAnim.InsertKeyFrame(0f, 0f);
            fadeAnim.InsertKeyFrame(1f, 1f, easing);
            fadeAnim.Duration = duration;
            visual.StartAnimation("Opacity", fadeAnim);

            if (Math.Abs(fromOffsetX) > 0.1 || Math.Abs(fromOffsetY) > 0.1)
            {
                var startOffset = new System.Numerics.Vector3((float)fromOffsetX, (float)fromOffsetY, 0f);
                visual.Properties.InsertVector3("Translation", startOffset);
                var slideAnim = compositor.CreateVector3KeyFrameAnimation();
                slideAnim.InsertKeyFrame(0f, startOffset);
                slideAnim.InsertKeyFrame(1f, System.Numerics.Vector3.Zero, easing);
                slideAnim.Duration = duration;
                visual.StartAnimation("Translation", slideAnim);
            }
            else
            {
                visual.StopAnimation("Translation");
                visual.Properties.InsertVector3("Translation", System.Numerics.Vector3.Zero);
            }
        }
        catch
        {
            element.Opacity = 1.0;
        }
    }

    /// <summary>
    /// 使用 GPU 合成视觉透明度执行浮层显隐过渡（隐藏完成自动复位 Collapsed）。
    /// </summary>
    public static void FadeSetVisibility(UIElement? element, bool visible, int durationMs = 140, double slideY = 0)
    {
        if (element is null) return;
        try
        {
            var visual = Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.GetElementVisual(element);
            if (ActiveFadeBatches.TryGetValue(element, out var prevBatch))
            {
                ActiveFadeBatches.Remove(element);
                try { prevBatch.Dispose(); } catch { }
            }

            if (visible)
            {
                var wasCollapsed = element.Visibility != Visibility.Visible;
                element.Visibility = Visibility.Visible;
                if (!wasCollapsed && visual.Opacity >= 0.99f && element.Opacity >= 0.99) return;
                PlayFadeIn(element, durationMs, fromOffsetY: slideY);
                return;
            }

            if (element.Visibility != Visibility.Visible) return;
            if (SuppressNonEssentialAnimations)
            {
                visual.StopAnimation("Opacity");
                visual.Opacity = 1f;
                element.Visibility = Visibility.Collapsed;
                element.Opacity = 1.0;
                return;
            }

            var compositor = visual.Compositor;
            var easing = compositor.CreateCubicBezierEasingFunction(
                new System.Numerics.Vector2(0.4f, 0.0f),
                new System.Numerics.Vector2(1.0f, 1.0f));
            var fadeOut = compositor.CreateScalarKeyFrameAnimation();
            fadeOut.InsertKeyFrame(1f, 0f, easing);
            fadeOut.Duration = TimeSpan.FromMilliseconds(Math.Max(60, durationMs));

            var batch = compositor.CreateScopedBatch(Microsoft.UI.Composition.CompositionBatchTypes.Animation);
            ActiveFadeBatches.AddOrUpdate(element, batch);
            batch.Completed += (_, _) =>
            {
                if (ActiveFadeBatches.TryGetValue(element, out var cur) && ReferenceEquals(cur, batch))
                {
                    ActiveFadeBatches.Remove(element);
                    element.Visibility = Visibility.Collapsed;
                    visual.Opacity = 1f;
                    element.Opacity = 1.0;
                }
                try { batch.Dispose(); } catch { }
            };
            visual.StartAnimation("Opacity", fadeOut);
            batch.End();
        }
        catch
        {
            element.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
            element.Opacity = 1.0;
        }
    }

    public static readonly DependencyProperty BoundThumbnailUrlProperty =
        DependencyProperty.RegisterAttached(
            "BoundThumbnailUrl",
            typeof(string),
            typeof(NativeShellHelper),
            new PropertyMetadata(string.Empty, OnThumbnailBindingChanged));

    public static string GetBoundThumbnailUrl(DependencyObject obj) => (string)(obj.GetValue(BoundThumbnailUrlProperty) ?? string.Empty);
    public static void SetBoundThumbnailUrl(DependencyObject obj, string? value) => obj.SetValue(BoundThumbnailUrlProperty, value ?? string.Empty);

    public static readonly DependencyProperty BoundTaskIdProperty =
        DependencyProperty.RegisterAttached(
            "BoundTaskId",
            typeof(string),
            typeof(NativeShellHelper),
            new PropertyMetadata(string.Empty, OnThumbnailBindingChanged));

    public static string GetBoundTaskId(DependencyObject obj) => (string)(obj.GetValue(BoundTaskIdProperty) ?? string.Empty);
    public static void SetBoundTaskId(DependencyObject obj, string? value) => obj.SetValue(BoundTaskIdProperty, value ?? string.Empty);

    public static readonly DependencyProperty ThumbnailDecodeWidthProperty =
        DependencyProperty.RegisterAttached(
            "ThumbnailDecodeWidth",
            typeof(int),
            typeof(NativeShellHelper),
            new PropertyMetadata(280));

    public static int GetThumbnailDecodeWidth(DependencyObject obj) => (int)(obj.GetValue(ThumbnailDecodeWidthProperty) ?? 280);
    public static void SetThumbnailDecodeWidth(DependencyObject obj, int value) => obj.SetValue(ThumbnailDecodeWidthProperty, value);

    private static readonly ConditionalWeakTable<Microsoft.UI.Xaml.Controls.Image, object> HookedThumbnailImages = new();

    private static void OnThumbnailBindingChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Microsoft.UI.Xaml.Controls.Image img) return;
        EnsureThumbnailLifecycleHooked(img);
        if (img.IsLoaded)
        {
            _ = ReloadBoundImageAsync(img);
        }
    }

    private static void EnsureThumbnailLifecycleHooked(Microsoft.UI.Xaml.Controls.Image img)
    {
        if (HookedThumbnailImages.TryGetValue(img, out _)) return;
        HookedThumbnailImages.Add(img, new object());
        img.Loaded += (_, _) => _ = ReloadBoundImageAsync(img);
        img.Unloaded += (_, _) =>
        {
            if (BoundImageCts.TryGetValue(img, out var cts))
            {
                try { cts.Cancel(); cts.Dispose(); } catch { }
                BoundImageCts.Remove(img);
            }
        };
    }

    private static string ResolveEffectiveBoundUrl(Microsoft.UI.Xaml.Controls.Image img)
    {
        var bound = GetBoundThumbnailUrl(img);
        if (!string.IsNullOrWhiteSpace(bound)) return bound.Trim();
        return (img.Tag as string)?.Trim() ?? string.Empty;
    }

    private static async Task ReloadBoundImageAsync(Microsoft.UI.Xaml.Controls.Image img)
    {
        var url = ResolveEffectiveBoundUrl(img);
        var taskId = GetBoundTaskId(img).Trim();
        var cacheKey = $"{url}##{taskId}";

        if (string.IsNullOrWhiteSpace(url) && string.IsNullOrWhiteSpace(taskId))
        {
            if (BoundImageCts.TryGetValue(img, out var oldCts))
            {
                try { oldCts.Cancel(); oldCts.Dispose(); } catch { }
                BoundImageCts.Remove(img);
            }
            BoundImageUrls.Remove(img);
            img.Source = null;
            return;
        }

        if (img.Source is not null && BoundImageUrls.TryGetValue(img, out var loadedKey) && string.Equals(loadedKey, cacheKey, StringComparison.Ordinal))
        {
            return;
        }

        if (BoundImageCts.TryGetValue(img, out var prevCts))
        {
            try { prevCts.Cancel(); prevCts.Dispose(); } catch { }
        }
        var cts = new CancellationTokenSource();
        BoundImageCts.AddOrUpdate(img, cts);
        BoundImageUrls.Remove(img);
        img.Source = null;

        var decodeWidth = GetThumbnailDecodeWidth(img);
        if (decodeWidth <= 0) decodeWidth = 280;

        var bmp = await LoadImageAsync(url, decodePixelWidth: decodeWidth, ct: cts.Token, taskId: taskId);
        if (cts.IsCancellationRequested || !img.IsLoaded) return;

        var latestKey = $"{ResolveEffectiveBoundUrl(img)}##{GetBoundTaskId(img).Trim()}";
        if (bmp is not null && string.Equals(latestKey, cacheKey, StringComparison.Ordinal))
        {
            BoundImageUrls.AddOrUpdate(img, cacheKey);
            img.Source = bmp;
        }
    }

    /// <summary>
    /// 按 Image.Tag 绑定地址安全异步加载缩略图：兼容 ItemsRepeater 容器回收复用，URL 变化时先清除旧图并重载。
    /// </summary>
    public static Task LoadBoundThumbnailAsync(Microsoft.UI.Xaml.Controls.Image img, int decodePixelWidth)
    {
        if (img is null) return Task.CompletedTask;
        SetThumbnailDecodeWidth(img, decodePixelWidth);
        EnsureThumbnailLifecycleHooked(img);
        return ReloadBoundImageAsync(img);
    }

    private static SolidColorBrush? _pureBlackBrush;
    private static SolidColorBrush? _darkBgBrush;
    private static SolidColorBrush? _lightBgBrush;
    private static SolidColorBrush? _transparentBrush;
    private static SolidColorBrush? _wallpaperMicaBrush;
    private static SolidColorBrush? _transitionHoldBrush;
    private static long _backdropEpoch;
    private static readonly ConditionalWeakTable<Window, BoxedBackdropState> AppliedWindowBackdrops = new();

    private sealed class BoxedBackdropState
    {
        public EffectiveBackdropKind Kind { get; set; }
        public string StyleKey { get; set; } = string.Empty;
        public bool IsDark { get; set; }
        public long Epoch { get; set; }
    }

    private static HttpClient CreateImageHttpClient()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
            ConnectTimeout = TimeSpan.FromSeconds(4),
            MaxConnectionsPerServer = 8
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
        return client;
    }

    public static BorderlessWindowChromeHelper? ConfigureWindowChrome(
        Window window,
        FrameworkElement titleBarDragRegion,
        int baseWidth = 1100,
        int baseHeight = 760,
        Microsoft.UI.Xaml.Controls.Grid? win10ResizeLayer = null,
        Action<bool>? onMaximizedChanged = null)
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

        var useWin10Borderless = !OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000)
            && win10ResizeLayer is not null
            && AppWindowTitleBar.IsCustomizationSupported();

        // 拦截 Win32 WM_ERASEBKGND 默认白底重绘；若主窗口在 Win10 下启用无原生框线模式，则不再需要 WindowDC 顶部补绘
        AttachAntiFlickerGuard(hwnd, hasExtendedTitleBar: AppWindowTitleBar.IsCustomizationSupported() && !useWin10Borderless);

        ApplyWindowBackdrop(
            window,
            AppServices.Current.LocalState.Current.BackdropStyle,
            ThemeAppearanceLogic.ResolveEffectiveDark(
                AppServices.Current.LocalState.Current.Theme,
                IsOsDarkTheme(),
                false,
                false),
            AppServices.Current.LocalState.Current.UsePureBlackDarkMode);

        if (useWin10Borderless)
        {
            return new BorderlessWindowChromeHelper(window, titleBarDragRegion, win10ResizeLayer!, onMaximizedChanged);
        }

        if (AppWindowTitleBar.IsCustomizationSupported())
        {
            window.ExtendsContentIntoTitleBar = true;
            window.SetTitleBar(titleBarDragRegion);
            titleBarDragRegion.Loaded += (_, _) =>
            {
                try { window.SetTitleBar(titleBarDragRegion); } catch { }
            };
        }

        return null;
    }

    private static (EffectiveBackdropKind Kind, string StyleKey, Color SurfaceBase, bool BackdropInstanceReplaced) ApplyWindowBackdrop(
        Window window,
        string? requestedStyle,
        bool isDark,
        bool pureBlackDark)
    {
        var micaSupported = false;
        var acrylicSupported = false;
        try { micaSupported = MicaController.IsSupported(); } catch { }
        try { acrylicSupported = DesktopAcrylicController.IsSupported(); } catch { }

        var styleKey = BackdropStyleLogic.NormalizeStyle(requestedStyle);
        var kind = BackdropStyleLogic.ResolveEffectiveKind(
            styleKey,
            isDark,
            pureBlackDark,
            micaSupported,
            acrylicSupported);

        var surfaceBase = ResolveSurfaceColor(kind, isDark, pureBlackDark);

        if (AppliedWindowBackdrops.TryGetValue(window, out var current)
            && current.Kind == kind
            && string.Equals(current.StyleKey, styleKey, StringComparison.Ordinal)
            && current.IsDark == isDark)
        {
            return (kind, styleKey, surfaceBase, false);
        }

        var replacedInstance = false;
        try
        {
            switch (kind)
            {
                case EffectiveBackdropKind.NativeOriginal:
                    // 完整还原加入 Ghost 背景之前的系统原生参数（Win11 原生 Mica Base / Win10 原生 DesktopAcrylic），并经安全包装层兜底主题切换异常
                    if (micaSupported)
                    {
                        if (window.SystemBackdrop is not SafeMicaBackdrop origMica || !origMica.MatchesKind(MicaKind.Base))
                        {
                            replacedInstance = true;
                            window.SystemBackdrop = new SafeMicaBackdrop(MicaKind.Base);
                        }
                    }
                    else if (acrylicSupported)
                    {
                        if (window.SystemBackdrop is not ConfigurableAcrylicBackdrop origAcrylic || !origAcrylic.MatchesConfiguration(DesktopAcrylicKind.Default, isNativeDefault: true))
                        {
                            replacedInstance = true;
                            window.SystemBackdrop = ConfigurableAcrylicBackdrop.CreateNativeDefault();
                        }
                    }
                    else
                    {
                        replacedInstance = window.SystemBackdrop is not null;
                        window.SystemBackdrop = null;
                    }
                    break;

                case EffectiveBackdropKind.MicaBase:
                    if (window.SystemBackdrop is not SafeMicaBackdrop mb || !mb.MatchesKind(MicaKind.Base))
                    {
                        replacedInstance = true;
                        window.SystemBackdrop = new SafeMicaBackdrop(MicaKind.Base);
                    }
                    break;

                case EffectiveBackdropKind.MicaAlt:
                    if (window.SystemBackdrop is not SafeMicaBackdrop mba || !mba.MatchesKind(MicaKind.BaseAlt))
                    {
                        replacedInstance = true;
                        window.SystemBackdrop = new SafeMicaBackdrop(MicaKind.BaseAlt);
                    }
                    break;

                case EffectiveBackdropKind.Aero:
                {
                    var (tint, tintOp, lumOp, fallback) = ResolveAcrylicParams(kind, styleKey, isDark);
                    if (window.SystemBackdrop is ConfigurableAcrylicBackdrop aero && aero.MatchesConfiguration(DesktopAcrylicKind.Thin, isNativeDefault: false))
                    {
                        aero.UpdateParameters(tint, tintOp, lumOp, fallback);
                    }
                    else
                    {
                        replacedInstance = true;
                        window.SystemBackdrop = new ConfigurableAcrylicBackdrop(DesktopAcrylicKind.Thin, tint, tintOp, lumOp, fallback);
                    }
                    break;
                }

                case EffectiveBackdropKind.Acrylic:
                {
                    var (tint, tintOp, lumOp, fallback) = ResolveAcrylicParams(kind, styleKey, isDark);
                    if (window.SystemBackdrop is ConfigurableAcrylicBackdrop acr && acr.MatchesConfiguration(DesktopAcrylicKind.Default, isNativeDefault: false))
                    {
                        acr.UpdateParameters(tint, tintOp, lumOp, fallback);
                    }
                    else
                    {
                        replacedInstance = true;
                        window.SystemBackdrop = new ConfigurableAcrylicBackdrop(DesktopAcrylicKind.Default, tint, tintOp, lumOp, fallback);
                    }
                    break;
                }

                default:
                    replacedInstance = window.SystemBackdrop is not null;
                    window.SystemBackdrop = null;
                    break;
            }

            AppliedWindowBackdrops.AddOrUpdate(window, new BoxedBackdropState
            {
                Kind = kind,
                StyleKey = styleKey,
                IsDark = isDark,
                Epoch = Interlocked.Increment(ref _backdropEpoch)
            });
        }
        catch
        {
            try
            {
                window.SystemBackdrop = null;
                AppliedWindowBackdrops.AddOrUpdate(window, new BoxedBackdropState
                {
                    Kind = EffectiveBackdropKind.Solid,
                    StyleKey = BackdropStyleLogic.SolidKey,
                    IsDark = isDark,
                    Epoch = Interlocked.Increment(ref _backdropEpoch)
                });
                return (EffectiveBackdropKind.Solid, BackdropStyleLogic.SolidKey, ResolveSurfaceColor(EffectiveBackdropKind.Solid, isDark, pureBlackDark), true);
            }
            catch
            {
            }
        }

        return (kind, styleKey, surfaceBase, replacedInstance);
    }

    private static (Color TintColor, float TintOpacity, float LuminosityOpacity, Color FallbackColor) ResolveAcrylicParams(
        EffectiveBackdropKind kind,
        string styleKey,
        bool isDark)
    {
        if (kind == EffectiveBackdropKind.Aero)
        {
            return isDark
                ? (Color.FromArgb(255, 18, 22, 30), 0.08f, 0.14f, Color.FromArgb(255, 22, 25, 32))
                : (Color.FromArgb(255, 236, 244, 255), 0.10f, 0.18f, Color.FromArgb(255, 240, 244, 250));
        }

        // Ghost-Downloader-3 标准磨砂参数："00000030" / "FFFFFF30"（高透低浑浊）
        return isDark
            ? (Color.FromArgb(255, 18, 20, 26), 0.19f, 0.36f, Color.FromArgb(255, 28, 30, 38))
            : (Color.FromArgb(255, 250, 252, 255), 0.22f, 0.42f, Color.FromArgb(255, 244, 246, 250));
    }

    private static Color ResolveSurfaceColor(EffectiveBackdropKind kind, bool isDark, bool pureBlackDark)
    {
        if (isDark && pureBlackDark) return Color.FromArgb(255, 0, 0, 0);
        return kind switch
        {
            EffectiveBackdropKind.WallpaperMica => DesktopWallpaperTintHelper.ResolveMicaSurfaceColor(isDark, isAlt: false),
            EffectiveBackdropKind.WallpaperMicaAlt => DesktopWallpaperTintHelper.ResolveMicaSurfaceColor(isDark, isAlt: true),
            _ => isDark ? Color.FromArgb(255, 28, 29, 34) : Color.FromArgb(255, 243, 245, 249)
        };
    }

    /// <summary>
    /// 为 WinUI 3 窗口挂载 Win32 防擦除闪烁与顶部白边抑制子类：
    /// 屏蔽 GDI 默认白底擦除、清除白底类画刷并在 Windows 10 下覆写扩展标题栏顶部 1px 非客户区白边。
    /// </summary>
    public static void AttachAntiFlickerGuard(IntPtr hwnd, bool hasExtendedTitleBar = false)
    {
        if (hwnd == IntPtr.Zero) return;
        try
        {
            var nullBrush = GetStockObject(NullBrush);
            if (nullBrush != IntPtr.Zero)
            {
                _ = SetClassLongPtr(hwnd, GclpHbrBackground, nullBrush);
            }
        }
        catch
        {
        }

        lock (SubclassLock)
        {
            if (hasExtendedTitleBar)
            {
                ExtendedTitleWindows[hwnd] = true;
            }
            if (SubclassProcs.ContainsKey(hwnd)) return;

            SubclassProcDelegate? proc = null;
            proc = (hWnd, uMsg, wParam, lParam, uId, _) =>
            {
                if (uMsg == WmEraseBkgnd)
                {
                    return new IntPtr(1);
                }

                if ((uMsg == WmNcPaint || uMsg == WmNcActivate) &&
                    !OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
                {
                    var res = DefSubclassProc(hWnd, uMsg, wParam, lParam);
                    PaintWindows10TopBorder(hWnd);
                    return res;
                }

                if (uMsg == WmNcDestroy)
                {
                    SubclassProcDelegate? removed = null;
                    lock (SubclassLock)
                    {
                        if (SubclassProcs.TryGetValue(hWnd, out var existing))
                        {
                            removed = existing;
                            SubclassProcs.Remove(hWnd);
                        }
                        TopEdgeColorRefs.Remove(hWnd);
                        ExtendedTitleWindows.Remove(hWnd);
                    }
                    if (removed is not null)
                    {
                        try { RemoveWindowSubclass(hWnd, removed, uId); } catch { }
                    }
                }
                return DefSubclassProc(hWnd, uMsg, wParam, lParam);
            };

            if (SetWindowSubclass(hwnd, proc, AntiFlickerSubclassId, UIntPtr.Zero))
            {
                SubclassProcs[hwnd] = proc;
            }
        }
    }

    private static void PaintWindows10TopBorder(IntPtr hWnd)
    {
        bool isExtended;
        uint colorRef;
        lock (SubclassLock)
        {
            ExtendedTitleWindows.TryGetValue(hWnd, out isExtended);
            if (!TopEdgeColorRefs.TryGetValue(hWnd, out colorRef))
            {
                colorRef = 0x00242020u; // COLORREF BGR: #202024 默认深灰
            }
        }
        if (!isExtended || IsZoomed(hWnd)) return;
        if (!GetWindowRect(hWnd, out var wr)) return;

        var width = wr.Right - wr.Left;
        if (width <= 0) return;

        var hdc = GetWindowDC(hWnd);
        if (hdc == IntPtr.Zero) return;
        var brush = CreateSolidBrush(colorRef);
        try
        {
            if (brush != IntPtr.Zero)
            {
                // Windows 10 自定义标题栏顶行 y=0 易由 DWM 绘成亮白色，按当前主题色覆盖 1px 顶部细线
                var strip = new Win32Rect { Left = 0, Top = 0, Right = width, Bottom = 1 };
                _ = FillRect(hdc, ref strip, brush);
            }
        }
        finally
        {
            if (brush != IntPtr.Zero) DeleteObject(brush);
            _ = ReleaseDC(hWnd, hdc);
        }
    }

    private static Color _sysAccent, _sysLight1, _sysLight2, _sysLight3, _sysDark1, _sysDark2, _sysDark3;
    private static string? _lastAccentKey;

    /// <summary>
    /// 防止主题切换或刷新期间触发重复的 ActualThemeChanged 回调
    /// </summary>
    public static bool IsApplyingThemeRefresh { get; private set; }

    private static Color ToUiColor(RgbColor c) => Color.FromArgb(255, c.R, c.G, c.B);

    private static bool IsOsDarkTheme()
    {
        try
        {
            var bg = new UISettings().GetColorValue(UIColorType.Background);
            return bg.R < 128 && bg.G < 128 && bg.B < 128;
        }
        catch
        {
            return Application.Current?.RequestedTheme == ApplicationTheme.Dark;
        }
    }

    private static void RefreshSystemPalette()
    {
        try
        {
            var ui = new UISettings();
            _sysAccent = ui.GetColorValue(UIColorType.Accent);
            _sysLight1 = ui.GetColorValue(UIColorType.AccentLight1);
            _sysLight2 = ui.GetColorValue(UIColorType.AccentLight2);
            _sysLight3 = ui.GetColorValue(UIColorType.AccentLight3);
            _sysDark1 = ui.GetColorValue(UIColorType.AccentDark1);
            _sysDark2 = ui.GetColorValue(UIColorType.AccentDark2);
            _sysDark3 = ui.GetColorValue(UIColorType.AccentDark3);
        }
        catch
        {
            _sysAccent = Color.FromArgb(255, 0, 120, 212);
            var fallback = ThemeAccentHelper.ComputePalette(new RgbColor(0, 120, 212));
            _sysLight1 = ToUiColor(fallback.Light1);
            _sysLight2 = ToUiColor(fallback.Light2);
            _sysLight3 = ToUiColor(fallback.Light3);
            _sysDark1 = ToUiColor(fallback.Dark1);
            _sysDark2 = ToUiColor(fallback.Dark2);
            _sysDark3 = ToUiColor(fallback.Dark3);
        }
    }

    /// <summary>
    /// 在主窗口创建前预置 Light / Default(Dark) 主题下的可变 SolidColorBrush 字典，保证后续任意主题或配色切换零闪烁
    /// </summary>
    public static void InitializeThemeResources(string? initialAccentColor = null)
    {
        ApplyAccentResources(initialAccentColor ?? ThemeAccentHelper.SystemKey, forceApply: true);
    }

    private static bool ApplyAccentResources(string accentSetting, bool forceApply = false)
    {
        var normalized = ThemeAccentHelper.NormalizeSetting(accentSetting);
        if (!forceApply && string.Equals(_lastAccentKey, normalized, StringComparison.Ordinal) &&
            normalized != ThemeAccentHelper.SystemKey)
        {
            return false;
        }

        if (normalized == ThemeAccentHelper.SystemKey || forceApply)
        {
            RefreshSystemPalette();
        }

        Color baseC, l1, l2, l3, d1, d2, d3;
        bool darkTextLight, darkTextDark;
        if (normalized == ThemeAccentHelper.SystemKey || !ThemeAccentHelper.TryParseHexRgb(normalized, out var customRgb))
        {
            baseC = _sysAccent;
            l1 = _sysLight1;
            l2 = _sysLight2;
            l3 = _sysLight3;
            d1 = _sysDark1;
            d2 = _sysDark2;
            d3 = _sysDark3;
            darkTextLight = ThemeAccentHelper.ShouldUseDarkForeground(new RgbColor(d1.R, d1.G, d1.B));
            darkTextDark = ThemeAccentHelper.ShouldUseDarkForeground(new RgbColor(l2.R, l2.G, l2.B));
        }
        else
        {
            var palette = ThemeAccentHelper.ComputePalette(customRgb);
            baseC = ToUiColor(palette.Base);
            l1 = ToUiColor(palette.Light1);
            l2 = ToUiColor(palette.Light2);
            l3 = ToUiColor(palette.Light3);
            d1 = ToUiColor(palette.Dark1);
            d2 = ToUiColor(palette.Dark2);
            d3 = ToUiColor(palette.Dark3);
            darkTextLight = palette.DarkTextOnLightFill;
            darkTextDark = palette.DarkTextOnDarkFill;
        }

        static Dictionary<string, Color> BuildBrushMap(
            bool darkMode,
            Color baseCol, Color light1, Color light2, Color light3,
            Color dark1, Color dark2, Color dark3,
            bool useDarkFgLight, bool useDarkFgDark)
        {
            var fill = darkMode ? light2 : dark1;
            var fillHover = darkMode ? light1 : dark2;
            var fillPressed = darkMode ? baseCol : dark3;
            var txtPrimary = darkMode ? light3 : dark2;
            var txtSecondary = darkMode ? light3 : dark3;
            var txtTertiary = darkMode ? light2 : dark1;
            var fg = (darkMode ? useDarkFgDark : useDarkFgLight) ? Colors.Black : Colors.White;

            return new Dictionary<string, Color>(StringComparer.Ordinal)
            {
                ["AccentFillColorDefaultBrush"] = fill,
                ["AccentFillColorSecondaryBrush"] = Color.FromArgb(230, fill.R, fill.G, fill.B),
                ["AccentFillColorTertiaryBrush"] = Color.FromArgb(204, fill.R, fill.G, fill.B),
                ["AccentTextFillColorPrimaryBrush"] = txtPrimary,
                ["AccentTextFillColorSecondaryBrush"] = txtSecondary,
                ["AccentTextFillColorTertiaryBrush"] = txtTertiary,
                ["TextOnAccentFillColorPrimaryBrush"] = fg,
                ["TextOnAccentFillColorSecondaryBrush"] = Color.FromArgb(180, fg.R, fg.G, fg.B),
                ["AccentButtonBackground"] = fill,
                ["AccentButtonBackgroundPointerOver"] = fillHover,
                ["AccentButtonBackgroundPressed"] = fillPressed,
                ["AccentButtonForeground"] = fg,
                ["AccentButtonForegroundPointerOver"] = fg,
                ["AccentButtonForegroundPressed"] = fg,
                ["ToggleSwitchFillOn"] = fill,
                ["ToggleSwitchFillOnPointerOver"] = fillHover,
                ["ToggleSwitchFillOnPressed"] = fillPressed,
                ["CheckBoxCheckBackgroundFillChecked"] = fill,
                ["CheckBoxCheckBackgroundFillCheckedPointerOver"] = fillHover,
                ["CheckBoxCheckBackgroundFillCheckedPressed"] = fillPressed,
                ["CheckBoxCheckBackgroundStrokeChecked"] = fill,
                ["CheckBoxCheckBackgroundStrokeCheckedPointerOver"] = fillHover,
                ["CheckBoxCheckBackgroundStrokeCheckedPressed"] = fillPressed,
                ["CheckBoxCheckGlyphForegroundChecked"] = fg,
                ["CheckBoxCheckGlyphForegroundCheckedPointerOver"] = fg,
                ["CheckBoxCheckGlyphForegroundCheckedPressed"] = fg,
                ["ProgressBarForeground"] = fill,
                ["SliderTrackValueFill"] = fill,
                ["SliderTrackValueFillPointerOver"] = fillHover,
                ["SliderTrackValueFillPressed"] = fillPressed,
                ["SliderThumbBackground"] = fill,
                ["SliderThumbBackgroundPointerOver"] = fillHover,
                ["SliderThumbBackgroundPressed"] = fillPressed,
                ["TextControlBorderBrushFocused"] = fill,
                ["HyperlinkButtonForeground"] = txtPrimary,
                ["HyperlinkButtonForegroundPointerOver"] = txtSecondary
            };
        }

        void ApplyColorKeys(ResourceDictionary dict)
        {
            try
            {
                dict["SystemAccentColor"] = baseC;
                dict["SystemAccentColorLight1"] = l1;
                dict["SystemAccentColorLight2"] = l2;
                dict["SystemAccentColorLight3"] = l3;
                dict["SystemAccentColorDark1"] = d1;
                dict["SystemAccentColorDark2"] = d2;
                dict["SystemAccentColorDark3"] = d3;
            }
            catch
            {
            }
        }

        static void ApplyBrushMap(ResourceDictionary dict, Dictionary<string, Color> map)
        {
            foreach (var (key, color) in map)
            {
                try
                {
                    if (dict.TryGetValue(key, out var existing) && existing is SolidColorBrush brush)
                    {
                        try
                        {
                            if (brush.Color != color) brush.Color = color;
                            continue;
                        }
                        catch
                        {
                        }
                    }
                    dict[key] = new SolidColorBrush(color);
                }
                catch
                {
                }
            }
        }

        static ResourceDictionary EnsureThemeDictionary(ResourceDictionary rootDict, string themeKey)
        {
            if (rootDict.ThemeDictionaries.TryGetValue(themeKey, out var obj) && obj is ResourceDictionary rd)
            {
                return rd;
            }
            var created = new ResourceDictionary();
            rootDict.ThemeDictionaries[themeKey] = created;
            return created;
        }

        var lightMap = BuildBrushMap(false, baseC, l1, l2, l3, d1, d2, d3, darkTextLight, darkTextDark);
        var darkMap = BuildBrushMap(true, baseC, l1, l2, l3, d1, d2, d3, darkTextLight, darkTextDark);

        // 仅更新 Application.Current.Resources 及其 ThemeDictionaries["Light"/"Default"]，就地修改画刷 Color，绝不篡改只读 XamlControlsResources 或翻转窗口主题
        var appResources = Application.Current?.Resources;
        if (appResources is not null)
        {
            ApplyColorKeys(appResources);
            var lightDict = EnsureThemeDictionary(appResources, "Light");
            var darkDict = EnsureThemeDictionary(appResources, "Default");
            ApplyColorKeys(lightDict);
            ApplyColorKeys(darkDict);
            ApplyBrushMap(lightDict, lightMap);
            ApplyBrushMap(darkDict, darkMap);
        }

        var changed = !string.Equals(_lastAccentKey, normalized, StringComparison.Ordinal);
        _lastAccentKey = normalized;
        return changed;
    }

    public static void ApplyThemeAndTitleBarColors(
        Window window,
        string themePreference,
        bool pureBlackDark,
        string? accentColorHex = null,
        string? backdropStyle = null,
        bool enableBackdrop = false,
        Microsoft.UI.Xaml.Controls.NavigationView? navView = null)
    {
        if (window.Content is not FrameworkElement root) return;

        var desiredTheme = themePreference switch
        {
            "light" => ElementTheme.Light,
            "dark" => ElementTheme.Dark,
            _ => ElementTheme.Default
        };

        // 首帧加载前或从显式深/浅色切回跟随系统时，采用系统实时偏好，避免先用旧主题渲染再二次切换闪烁
        var isDark = ThemeAppearanceLogic.ResolveEffectiveDark(
            themePreference,
            IsOsDarkTheme(),
            root.IsLoaded && root.RequestedTheme == ElementTheme.Default,
            root.ActualTheme == ElementTheme.Dark);

        ApplyAccentResources(accentColorHex ?? ThemeAccentHelper.SystemKey);

        var hasExtendedChrome = AppWindowTitleBar.IsCustomizationSupported() && window.ExtendsContentIntoTitleBar;
        var shouldManageBackdrop = enableBackdrop || hasExtendedChrome;
        var appliedKind = EffectiveBackdropKind.Solid;
        var appliedStyleKey = BackdropStyleLogic.SolidKey;
        var surfaceBase = ResolveSurfaceColor(EffectiveBackdropKind.Solid, isDark, pureBlackDark);
        var backdropInstanceReplaced = false;

        // 预判是否即将更换 SystemBackdrop 实例；先用同色调保护画刷垫住底层防止 DWM 断开/重建瞬间的闪黑或闪白
        if (shouldManageBackdrop && root is Microsoft.UI.Xaml.Controls.Panel prePanel)
        {
            var effectiveBackdrop = backdropStyle ?? AppServices.Current.LocalState.Current.BackdropStyle;
            var targetKind = BackdropStyleLogic.ResolveEffectiveKind(
                effectiveBackdrop,
                isDark,
                pureBlackDark,
                IsMicaSupportedSafe(),
                IsAcrylicSupportedSafe());
            var targetSurface = ResolveSurfaceColor(targetKind, isDark, pureBlackDark);
            if (AppliedWindowBackdrops.TryGetValue(window, out var prevState) &&
                (prevState.Kind != targetKind || prevState.IsDark != isDark))
            {
                _transitionHoldBrush ??= new SolidColorBrush(targetSurface);
                _transitionHoldBrush.Color = targetSurface;
                prePanel.Background = _transitionHoldBrush;
            }

            // 在挂载新 Backdrop 之前先更新内容区与卡片画刷及主题，彻底消除资源与主题先后变更导致的闪烁
            ApplyBackdropCardResources(targetKind, isDark, pureBlackDark);
            if (navView is not null)
            {
                ApplyNavigationBackdropColors(navView, targetKind, BackdropStyleLogic.NormalizeStyle(effectiveBackdrop), targetSurface, isDark, pureBlackDark);
            }
        }

        if (root.RequestedTheme != desiredTheme)
        {
            IsApplyingThemeRefresh = true;
            try
            {
                root.RequestedTheme = desiredTheme;
            }
            finally
            {
                IsApplyingThemeRefresh = false;
            }
        }

        if (shouldManageBackdrop)
        {
            var effectiveBackdrop = backdropStyle ?? AppServices.Current.LocalState.Current.BackdropStyle;
            (appliedKind, appliedStyleKey, surfaceBase, backdropInstanceReplaced) = ApplyWindowBackdrop(window, effectiveBackdrop, isDark, pureBlackDark);
            ApplyBackdropCardResources(appliedKind, isDark, pureBlackDark);
        }

        try
        {
            var hwnd = WindowNative.GetWindowHandle(window);
            if (hwnd != IntPtr.Zero)
            {
                var darkVal = isDark ? 1 : 0;
                _ = DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref darkVal, sizeof(int));

                // 顶部边框色彩 (BGR COLORREF = 0x00BBGGRR) 仅供保留原生框线的副窗口回退使用
                var isSolidBackdrop = window.SystemBackdrop is null;
                uint topColorRef = (isDark && pureBlackDark)
                    ? 0x00000000u
                    : isDark
                        ? (isSolidBackdrop ? 0x00221D1Cu : 0x002C2C2Cu)
                        : (isSolidBackdrop ? 0x00F9F5F4u : 0x00F9F9F9u);
                lock (SubclassLock)
                {
                    TopEdgeColorRefs[hwnd] = topColorRef;
                }

                if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
                {
                    uint borderColorRef = isDark ? (pureBlackDark ? 0x00181818u : 0x0032343Cu) : 0xFFFFFFFFu;
                    _ = DwmSetWindowAttribute(hwnd, DwmwaBorderColor, ref borderColorRef, sizeof(uint));
                }
                else if (enableBackdrop)
                {
                    // Win10 主窗口采用 SetBorderAndTitleBar(false, false) 无框线方案：
                    // Acrylic/Aero 使用全窗 -1 透明合成，PureSolid/WallpaperMica 保留 Bottom=1 消除顶部亮线
                    var margins = window.SystemBackdrop is not null
                        ? new DwmMargins { Left = -1, Right = -1, Top = -1, Bottom = -1 }
                        : new DwmMargins { Left = 0, Right = 0, Top = 0, Bottom = 1 };
                    _ = DwmExtendFrameIntoClientArea(hwnd, ref margins);
                }
                else if (hasExtendedChrome)
                {
                    var margins = new DwmMargins { Left = 0, Right = 0, Top = 1, Bottom = 0 };
                    _ = DwmExtendFrameIntoClientArea(hwnd, ref margins);
                    _ = RedrawWindow(hwnd, IntPtr.Zero, IntPtr.Zero, RdwFrame | RdwInvalidate);
                    PaintWindows10TopBorder(hwnd);
                }
            }
        }
        catch
        {
        }

        if (root is Microsoft.UI.Xaml.Controls.Panel rootPanel)
        {
            SolidColorBrush targetBrush;
            if (isDark && pureBlackDark)
            {
                targetBrush = _pureBlackBrush ??= new SolidColorBrush(Color.FromArgb(255, 0, 0, 0));
            }
            else if (window.SystemBackdrop is null)
            {
                if (appliedKind is EffectiveBackdropKind.WallpaperMica or EffectiveBackdropKind.WallpaperMicaAlt)
                {
                    _wallpaperMicaBrush ??= new SolidColorBrush(surfaceBase);
                    _wallpaperMicaBrush.Color = surfaceBase;
                    targetBrush = _wallpaperMicaBrush;
                }
                else
                {
                    targetBrush = isDark
                        ? (_darkBgBrush ??= new SolidColorBrush(Color.FromArgb(255, 28, 29, 34)))
                        : (_lightBgBrush ??= new SolidColorBrush(Color.FromArgb(255, 243, 245, 249)));
                }
            }
            else
            {
                targetBrush = _transparentBrush ??= new SolidColorBrush(Colors.Transparent);
            }

            // 当更换了新的 SystemBackdrop 实例且目标透明时，在保护垫层上延迟一帧待新 DWM 合成提交后再显露，杜绝黑/白闪帧
            if (backdropInstanceReplaced && ReferenceEquals(targetBrush, _transparentBrush) && window.DispatcherQueue is not null)
            {
                var capturedEpoch = AppliedWindowBackdrops.TryGetValue(window, out var st) ? st.Epoch : 0;
                window.DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
                {
                    if (window.SystemBackdrop is not null &&
                        AppliedWindowBackdrops.TryGetValue(window, out var latest) &&
                        latest.Epoch == capturedEpoch)
                    {
                        rootPanel.Background = _transparentBrush;
                    }
                });
            }
            else if (!ReferenceEquals(rootPanel.Background, targetBrush))
            {
                rootPanel.Background = targetBrush;
            }
        }

        if (navView is not null)
        {
            ApplyNavigationBackdropColors(navView, appliedKind, appliedStyleKey, surfaceBase, isDark, pureBlackDark);
        }

        if (AppWindowTitleBar.IsCustomizationSupported() && window.ExtendsContentIntoTitleBar)
        {
            var tb = window.AppWindow.TitleBar;
            tb.BackgroundColor = Colors.Transparent;
            tb.InactiveBackgroundColor = Colors.Transparent;
            tb.ButtonBackgroundColor = Colors.Transparent;
            tb.ButtonInactiveBackgroundColor = Colors.Transparent;
            tb.ButtonForegroundColor = isDark ? Color.FromArgb(255, 240, 242, 248) : Color.FromArgb(255, 24, 28, 36);
            tb.ButtonInactiveForegroundColor = isDark ? Color.FromArgb(255, 140, 148, 165) : Color.FromArgb(255, 110, 118, 130);
            tb.ButtonHoverBackgroundColor = isDark ? Color.FromArgb(30, 255, 255, 255) : Color.FromArgb(20, 0, 0, 0);
            tb.ButtonHoverForegroundColor = tb.ButtonForegroundColor;
        }
    }

    private static bool IsMicaSupportedSafe()
    {
        try { return MicaController.IsSupported(); } catch { return false; }
    }

    private static bool IsAcrylicSupportedSafe()
    {
        try { return DesktopAcrylicController.IsSupported(); } catch { return false; }
    }

    private static void ApplyBackdropCardResources(EffectiveBackdropKind kind, bool isDark, bool pureBlackDark)
    {
        Color lightSurface, darkSurface, lightBorder, darkBorder;
        if (pureBlackDark)
        {
            lightSurface = Color.FromArgb(245, 255, 255, 255);
            lightBorder = Color.FromArgb(24, 0, 0, 0);
            darkSurface = Color.FromArgb(255, 16, 17, 21);
            darkBorder = Color.FromArgb(34, 255, 255, 255);
        }
        else
        {
            switch (kind)
            {
                case EffectiveBackdropKind.Aero:
                    // Ghost Aero 经典清透玻璃卡片：适度透光但保有清晰明度差与高光琉璃描边
                    lightSurface = Color.FromArgb(196, 255, 255, 255);
                    lightBorder = Color.FromArgb(62, 255, 255, 255);
                    darkSurface = Color.FromArgb(164, 30, 34, 44);
                    darkBorder = Color.FromArgb(64, 255, 255, 255);
                    break;

                case EffectiveBackdropKind.Acrylic:
                    // Ghost Acrylic 磨砂：内容底板大面积高透，卡片具备醒目的高对比度半透明磨砂承载质感
                    lightSurface = Color.FromArgb(212, 255, 255, 255);
                    lightBorder = Color.FromArgb(34, 0, 0, 0);
                    darkSurface = Color.FromArgb(184, 34, 36, 44);
                    darkBorder = Color.FromArgb(40, 255, 255, 255);
                    break;

                case EffectiveBackdropKind.MicaBase:
                case EffectiveBackdropKind.WallpaperMica:
                    lightSurface = Color.FromArgb(224, 255, 255, 255);
                    lightBorder = Color.FromArgb(24, 0, 0, 0);
                    darkSurface = Color.FromArgb(202, 40, 42, 50);
                    darkBorder = Color.FromArgb(32, 255, 255, 255);
                    break;

                case EffectiveBackdropKind.MicaAlt:
                case EffectiveBackdropKind.WallpaperMicaAlt:
                    lightSurface = Color.FromArgb(236, 255, 255, 255);
                    lightBorder = Color.FromArgb(28, 0, 0, 0);
                    darkSurface = Color.FromArgb(218, 42, 45, 54);
                    darkBorder = Color.FromArgb(36, 255, 255, 255);
                    break;

                case EffectiveBackdropKind.NativeOriginal:
                    // 原版经典参数：细腻均衡的卡片层次
                    lightSurface = Color.FromArgb(235, 255, 255, 255);
                    lightBorder = Color.FromArgb(22, 0, 0, 0);
                    darkSurface = Color.FromArgb(222, 42, 44, 52);
                    darkBorder = Color.FromArgb(30, 255, 255, 255);
                    break;

                default: // Solid
                    lightSurface = Color.FromArgb(255, 255, 255, 255);
                    lightBorder = Color.FromArgb(26, 0, 0, 0);
                    darkSurface = Color.FromArgb(255, 42, 44, 52);
                    darkBorder = Color.FromArgb(34, 255, 255, 255);
                    break;
            }
        }

        var appResources = Application.Current?.Resources;
        if (appResources is null) return;
        UpdateThemeDictBrush(appResources, "Light", "OmniCardSurfaceBrush", lightSurface);
        UpdateThemeDictBrush(appResources, "Light", "OmniCardBorderBrush", lightBorder);
        UpdateThemeDictBrush(appResources, "Default", "OmniCardSurfaceBrush", darkSurface);
        UpdateThemeDictBrush(appResources, "Default", "OmniCardBorderBrush", darkBorder);
    }

    private static void UpdateThemeDictBrush(ResourceDictionary rootDict, string themeKey, string brushKey, Color color)
    {
        try
        {
            if (rootDict.ThemeDictionaries.TryGetValue(themeKey, out var obj) && obj is ResourceDictionary dict)
            {
                if (dict.TryGetValue(brushKey, out var val) && val is SolidColorBrush b)
                {
                    if (b.Color != color) b.Color = color;
                }
                else
                {
                    dict[brushKey] = new SolidColorBrush(color);
                }
            }
        }
        catch
        {
        }
    }

    private static void ApplyNavigationBackdropColors(
        Microsoft.UI.Xaml.Controls.NavigationView navView,
        EffectiveBackdropKind kind,
        string styleKey,
        Color surfaceBase,
        bool isDark,
        bool pureBlackDark)
    {
        Color contentBg;
        Color paneBg;
        Color contentBorder;

        if (isDark && pureBlackDark)
        {
            contentBg = Color.FromArgb(255, 8, 8, 10);
            paneBg = Color.FromArgb(255, 0, 0, 0);
            contentBorder = Color.FromArgb(36, 255, 255, 255);
        }
        else
        {
            switch (kind)
            {
                case EffectiveBackdropKind.NativeOriginal:
                    // 原版原生平衡遮罩：既清晰凸显前景面板又柔和融出系统底层 Mica/Acrylic 光晕
                    contentBg = isDark ? Color.FromArgb(136, 30, 32, 38) : Color.FromArgb(182, 250, 251, 253);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(28, 255, 255, 255) : Color.FromArgb(20, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.Aero:
                    // Ghost Aero：极低画布遮罩（~14%），凸显大比例通透玻璃底层，靠高亮分割线划分区域
                    contentBg = isDark ? Color.FromArgb(28, 18, 24, 34) : Color.FromArgb(44, 244, 248, 255);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(56, 255, 255, 255) : Color.FromArgb(48, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.Acrylic:
                    // Ghost Acrylic：对标 Ghost 内容区轻透分层（~20-26%），让磨砂质感完整覆盖窗口主体而非仅靠侧边栏
                    contentBg = isDark ? Color.FromArgb(48, 20, 22, 28) : Color.FromArgb(68, 255, 255, 255);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(38, 255, 255, 255) : Color.FromArgb(28, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.MicaBase:
                    contentBg = isDark ? Color.FromArgb(92, 28, 30, 38) : Color.FromArgb(134, 248, 250, 255);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(28, 255, 255, 255) : Color.FromArgb(20, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.MicaAlt:
                    contentBg = isDark ? Color.FromArgb(128, 22, 24, 32) : Color.FromArgb(164, 240, 244, 250);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(34, 255, 255, 255) : Color.FromArgb(26, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.WallpaperMica:
                    contentBg = BlendOpaqueColor(surfaceBase, isDark ? Color.FromArgb(255, 44, 47, 57) : Color.FromArgb(255, 255, 255, 255), isDark ? 0.36 : 0.50);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(32, 255, 255, 255) : Color.FromArgb(24, 0, 0, 0);
                    break;

                case EffectiveBackdropKind.WallpaperMicaAlt:
                    contentBg = BlendOpaqueColor(surfaceBase, isDark ? Color.FromArgb(255, 34, 37, 46) : Color.FromArgb(255, 245, 248, 254), isDark ? 0.56 : 0.68);
                    paneBg = Colors.Transparent;
                    contentBorder = isDark ? Color.FromArgb(38, 255, 255, 255) : Color.FromArgb(28, 0, 0, 0);
                    break;

                default:
                    contentBg = isDark ? Color.FromArgb(255, 34, 36, 43) : Color.FromArgb(255, 250, 251, 254);
                    paneBg = isDark ? Color.FromArgb(255, 24, 25, 30) : Color.FromArgb(255, 238, 241, 247);
                    contentBorder = isDark ? Color.FromArgb(32, 255, 255, 255) : Color.FromArgb(24, 0, 0, 0);
                    break;
            }
        }

        UpdateNavResourceBrush(navView.Resources, "NavigationViewContentBackground", contentBg);
        UpdateNavResourceBrush(navView.Resources, "NavigationViewExpandedPaneBackground", paneBg);
        UpdateNavResourceBrush(navView.Resources, "NavigationViewTopPaneBackground", paneBg);
        UpdateNavResourceBrush(navView.Resources, "NavigationViewDefaultPaneBackground", paneBg);
        UpdateNavResourceBrush(navView.Resources, "NavigationViewContentGridBorderBrush", contentBorder);
    }

    private static void UpdateNavResourceBrush(ResourceDictionary resources, string key, Color color)
    {
        if (resources.TryGetValue(key, out var val) && val is SolidColorBrush rootBrush)
        {
            rootBrush.Color = color;
        }
        foreach (var dictKey in new[] { "Light", "Default" })
        {
            if (resources.ThemeDictionaries.TryGetValue(dictKey, out var obj) &&
                obj is ResourceDictionary dict &&
                dict.TryGetValue(key, out var tVal) &&
                tVal is SolidColorBrush themeBrush)
            {
                themeBrush.Color = color;
            }
        }
    }

    private static Color BlendOpaqueColor(Color a, Color b, double weight)
    {
        var r = (byte)Math.Clamp((int)Math.Round(a.R + (b.R - a.R) * weight), 0, 255);
        var g = (byte)Math.Clamp((int)Math.Round(a.G + (b.G - a.G) * weight), 0, 255);
        var bl = (byte)Math.Clamp((int)Math.Round(a.B + (b.B - a.B) * weight), 0, 255);
        return Color.FromArgb(255, r, g, bl);
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

    public static event Action<string>? ClipboardTextCopied;

    public static void CopyText(string text)
    {
        var value = text ?? string.Empty;
        ClipboardTextCopied?.Invoke(value);
        var pkg = new DataPackage();
        pkg.SetText(value);
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
            throw new InvalidOperationException(LocalizationService.Instance.T("所选文件大小超过限制 (%d KB)", maxBytes / 1024));
        }

        return await Windows.Storage.FileIO.ReadTextAsync(file);
    }

    public static async Task<bool> PickSaveTextFileAsync(Window owner, string suggestedFileName, string content)
    {
        var picker = new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
            SuggestedFileName = suggestedFileName
        };
        picker.FileTypeChoices.Add("Omni Download Rules (.omnirule.json)", new List<string> { ".omnirule.json", ".json" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(owner));
        var file = await picker.PickSaveFileAsync();
        if (file is null) return false;
        await Windows.Storage.FileIO.WriteTextAsync(file, content ?? string.Empty);
        return true;
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
    public static string? BackendBaseUrlOverride { get; set; }

    private static string GetBackendBaseUrl()
    {
        if (!string.IsNullOrWhiteSpace(BackendBaseUrlOverride)) return BackendBaseUrlOverride.TrimEnd('/');
        try
        {
            return AppServices.Current.ApiClient.BaseUrl.TrimEnd('/');
        }
        catch
        {
            return "http://127.0.0.1:58000";
        }
    }

    private static bool IsLocalLoopbackUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        return uri.IsLoopback || string.Equals(uri.Host, "127.0.0.1", StringComparison.OrdinalIgnoreCase) || string.Equals(uri.Host, "localhost", StringComparison.OrdinalIgnoreCase);
    }

    private static bool ShouldPreferBackendThumbProxy(string url)
    {
        if (string.IsNullOrWhiteSpace(url)) return true;
        if (IsLocalLoopbackUrl(url)) return false;
        var lower = url.ToLowerInvariant();
        if (lower.Contains(".webp", StringComparison.Ordinal) ||
            lower.Contains(".avif", StringComparison.Ordinal) ||
            lower.Contains("vi_webp", StringComparison.Ordinal) ||
            lower.Contains("/format/webp", StringComparison.Ordinal))
        {
            return true;
        }
        return lower.Contains("pximg.net", StringComparison.Ordinal) ||
               lower.Contains("sinaimg.cn", StringComparison.Ordinal) ||
               lower.Contains("xhscdn.com", StringComparison.Ordinal) ||
               lower.Contains("douyinpic.com", StringComparison.Ordinal) ||
               lower.Contains("douyincdn.com", StringComparison.Ordinal) ||
               lower.Contains("byteimg.com", StringComparison.Ordinal) ||
               lower.Contains("yximgs.com", StringComparison.Ordinal) ||
               lower.Contains("kwimgs.com", StringComparison.Ordinal);
    }

    private static bool IsWicCompatibleHeader(byte[]? bytes)
    {
        if (bytes is null || bytes.Length < 12) return false;
        // JPEG
        if (bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return true;
        // PNG
        if (bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return true;
        // GIF
        if (bytes[0] == (byte)'G' && bytes[1] == (byte)'I' && bytes[2] == (byte)'F') return true;
        // BMP
        if (bytes[0] == (byte)'B' && bytes[1] == (byte)'M') return true;
        return false;
    }

    private static string ResolveNormalizedImageUrl(string? url)
    {
        var raw = (url ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(raw)) return string.Empty;
        if (raw.StartsWith("//", StringComparison.Ordinal)) return "https:" + raw;
        if (raw.StartsWith("/", StringComparison.Ordinal)) return $"{GetBackendBaseUrl()}{raw}";
        return raw;
    }

    /// <summary>
    /// 使用桌面 HTTP 客户端安全拉取远程或本地预览图片并解码为 BitmapImage：
    /// 内置并发去重、防盗链请求头、WebP/AVIF/代理失败自动回退本地服务端 /api/media/thumb 与有界 LRU 字节缓存。
    /// </summary>
    public static async Task<BitmapImage?> LoadImageAsync(string? url, int decodePixelWidth = 0, CancellationToken ct = default, string? taskId = null)
    {
        var normalizedUrl = ResolveNormalizedImageUrl(url);
        var cleanTaskId = (taskId ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(normalizedUrl) && string.IsNullOrWhiteSpace(cleanTaskId)) return null;

        try
        {
            var cacheKey = string.IsNullOrWhiteSpace(normalizedUrl) ? $"task://{cleanTaskId}" : normalizedUrl;
            var bytes = await GetCachedOrFetchBytesAsync(normalizedUrl, cleanTaskId, cacheKey, ct);
            if (bytes is null || bytes.Length == 0 || ct.IsCancellationRequested) return null;

            var bmp = await DecodeImageBytesAsync(bytes, decodePixelWidth, ct);
            if (bmp is not null) return bmp;

            // 若直接解码（例如系统缺少 WebP/AVIF 扩展）失败，清除该 URL 直链缓存并通过后端代理转码再试一次
            if (!ct.IsCancellationRequested && !IsLocalLoopbackUrl(normalizedUrl))
            {
                RemoveCachedImageBytes(cacheKey);
                var proxyUrl = BuildBackendThumbUrl(normalizedUrl, cleanTaskId);
                var compatBytes = await FetchBytesFromUrlCoreAsync(proxyUrl, applyPlatformReferer: false, ct);
                if (compatBytes is { Length: > 0 } && !ct.IsCancellationRequested)
                {
                    var fallbackBmp = await DecodeImageBytesAsync(compatBytes, decodePixelWidth, ct);
                    if (fallbackBmp is not null)
                    {
                        PutCachedImageBytes(cacheKey, compatBytes);
                        return fallbackBmp;
                    }
                }
            }
            return null;
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch
        {
            return null;
        }
    }

    private static string BuildBackendThumbUrl(string? url, string? taskId)
    {
        var parts = new List<string>(2);
        if (!string.IsNullOrWhiteSpace(url) && !IsLocalLoopbackUrl(url))
        {
            parts.Add($"url={Uri.EscapeDataString(url)}");
        }
        if (!string.IsNullOrWhiteSpace(taskId))
        {
            parts.Add($"task_id={Uri.EscapeDataString(taskId)}");
        }
        return $"{GetBackendBaseUrl()}/api/media/thumb?{string.Join("&", parts)}";
    }

    private static async Task<byte[]?> GetCachedOrFetchBytesAsync(string url, string taskId, string cacheKey, CancellationToken ct)
    {
        if (TryGetCachedImageBytes(cacheKey, out var hit))
        {
            return hit;
        }

        if (FailedImageUrls.TryGetValue(cacheKey, out var failedAt) && DateTimeOffset.UtcNow - failedAt < TimeSpan.FromSeconds(5))
        {
            return null;
        }

        var downloadTask = InflightImageDownloads.GetOrAdd(cacheKey, _ => FetchCoverBytesWithFallbackAsync(url, taskId));
        try
        {
            var data = await downloadTask.WaitAsync(ct);
            if (data is { Length: > 0 })
            {
                PutCachedImageBytes(cacheKey, data);
                FailedImageUrls.TryRemove(cacheKey, out _);
            }
            else if (!ct.IsCancellationRequested)
            {
                var now = DateTimeOffset.UtcNow;
                if (FailedImageUrls.Count >= MaxFailedImageUrls)
                {
                    foreach (var kv in FailedImageUrls)
                    {
                        if (now - kv.Value > TimeSpan.FromSeconds(30) || FailedImageUrls.Count >= MaxFailedImageUrls)
                        {
                            FailedImageUrls.TryRemove(kv.Key, out _);
                        }
                    }
                }
                FailedImageUrls[cacheKey] = now;
            }
            return data;
        }
        finally
        {
            if (downloadTask.IsCompleted)
            {
                InflightImageDownloads.TryRemove(new KeyValuePair<string, Task<byte[]?>>(cacheKey, downloadTask));
            }
        }
    }

    private static async Task<byte[]?> FetchCoverBytesWithFallbackAsync(string url, string taskId)
    {
        if (!string.IsNullOrWhiteSpace(url))
        {
            if (IsLocalLoopbackUrl(url))
            {
                var localPreview = await FetchBytesFromUrlCoreAsync(url, applyPlatformReferer: false, CancellationToken.None);
                if (localPreview is { Length: > 0 }) return localPreview;
            }
            else if (!ShouldPreferBackendThumbProxy(url))
            {
                var direct = await FetchBytesFromUrlCoreAsync(url, applyPlatformReferer: true, CancellationToken.None);
                if (IsWicCompatibleHeader(direct))
                {
                    return direct;
                }
            }
        }

        // 回退统一走后端 /api/media/thumb：支持应用内代理、平台 Cookie/Referer、WebP/AVIF 自动转码以及本地文件封面提取
        var proxyUrl = BuildBackendThumbUrl(url, taskId);
        return await FetchBytesFromUrlCoreAsync(proxyUrl, applyPlatformReferer: false, CancellationToken.None);
    }

    private static void ApplyPlatformImageHeaders(HttpRequestMessage req, string url)
    {
        req.Headers.TryAddWithoutValidation("Accept", "image/jpeg,image/png,image/gif,image/bmp,image/webp,*/*;q=0.8");
        var lower = url.ToLowerInvariant();
        if (lower.Contains("hdslb.com") || lower.Contains("bilibili.com"))
            req.Headers.Referrer = new Uri("https://www.bilibili.com/");
        else if (lower.Contains("pximg.net") || lower.Contains("pixiv.net"))
            req.Headers.Referrer = new Uri("https://www.pixiv.net/");
        else if (lower.Contains("sinaimg.cn") || lower.Contains("weibo.com"))
            req.Headers.Referrer = new Uri("https://weibo.com/");
        else if (lower.Contains("xhscdn.com") || lower.Contains("xiaohongshu.com"))
            req.Headers.Referrer = new Uri("https://www.xiaohongshu.com/");
        else if (lower.Contains("douyinpic.com") || lower.Contains("byteimg.com") || lower.Contains("douyincdn.com") || lower.Contains("douyin.com"))
            req.Headers.Referrer = new Uri("https://www.douyin.com/");
        else if (lower.Contains("yximgs.com") || lower.Contains("kuaishou.com") || lower.Contains("kwimgs.com"))
            req.Headers.Referrer = new Uri("https://www.kuaishou.com/");
        else if (lower.Contains("cdninstagram.com") || lower.Contains("fbcdn.net"))
            req.Headers.Referrer = new Uri("https://www.instagram.com/");
    }

    private static async Task<byte[]?> FetchBytesFromUrlCoreAsync(string url, bool applyPlatformReferer, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            if (applyPlatformReferer)
            {
                ApplyPlatformImageHeaders(req, url);
            }
            using var resp = await ImageHttp.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            var bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            return bytes.Length >= 12 ? bytes : null;
        }
        catch
        {
            return null;
        }
    }

    private static void RemoveCachedImageBytes(string url)
    {
        lock (CacheLock)
        {
            if (ImageByteCache.TryGetValue(url, out var existing))
            {
                _cachedBytesTotal -= existing.Value.Bytes.Length;
                ImageLruList.Remove(existing);
                ImageByteCache.Remove(url);
            }
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
        if (bytes is null || bytes.Length < 12 || ct.IsCancellationRequested) return null;
        try
        {
            var bmp = new BitmapImage
            {
                CreateOptions = BitmapCreateOptions.IgnoreImageCache
            };
            if (decodePixelWidth > 0)
            {
                bmp.DecodePixelType = DecodePixelType.Logical;
                bmp.DecodePixelWidth = decodePixelWidth;
            }
            using var mem = new InMemoryRandomAccessStream();
            await mem.WriteAsync(bytes.AsBuffer()).AsTask(ct);
            await mem.FlushAsync().AsTask(ct);
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
