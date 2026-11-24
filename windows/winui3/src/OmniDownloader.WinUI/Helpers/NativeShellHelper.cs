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
    private static readonly ConditionalWeakTable<Microsoft.UI.Xaml.Controls.Image, string> BoundImageUrls = new();
    private static readonly ConditionalWeakTable<Microsoft.UI.Xaml.Controls.Image, CancellationTokenSource> BoundImageCts = new();
    private static readonly ConditionalWeakTable<UIElement, Storyboard> ActiveElementStoryboards = new();

    /// <summary>
    /// 使用 WinUI 3 原生 XAML Storyboard 与 CubicEase(EaseOut) 执行克制自然的淡入/微位移呈现。
    /// 不修改布局缩放或注入全局隐式位移动画，保持原生桌面交互稳定。
    /// </summary>
    public static void PlayFadeIn(UIElement? element, int durationMs = 150, double fromOffsetY = 0, double fromOffsetX = 0)
    {
        if (element is null || element.Visibility != Visibility.Visible) return;
        try
        {
            if (ActiveElementStoryboards.TryGetValue(element, out var prev))
            {
                prev.Stop();
            }

            var sb = new Storyboard();
            var keyTime = KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(Math.Max(60, durationMs)));
            static KeySpline MakeDecelerateSpline() => new()
            {
                ControlPoint1 = new Windows.Foundation.Point(0.1, 0.9),
                ControlPoint2 = new Windows.Foundation.Point(0.2, 1.0)
            };

            var fade = new DoubleAnimationUsingKeyFrames();
            fade.KeyFrames.Add(new DiscreteDoubleKeyFrame { KeyTime = KeyTime.FromTimeSpan(TimeSpan.Zero), Value = 0.0 });
            fade.KeyFrames.Add(new SplineDoubleKeyFrame { KeyTime = keyTime, Value = 1.0, KeySpline = MakeDecelerateSpline() });
            Storyboard.SetTarget(fade, element);
            Storyboard.SetTargetProperty(fade, "Opacity");
            sb.Children.Add(fade);

            if (Math.Abs(fromOffsetX) > 0.1 || Math.Abs(fromOffsetY) > 0.1)
            {
                if (element.RenderTransform is not TranslateTransform tt)
                {
                    tt = new TranslateTransform();
                    element.RenderTransform = tt;
                }
                if (Math.Abs(fromOffsetY) > 0.1)
                {
                    tt.Y = fromOffsetY;
                    var slideY = new DoubleAnimationUsingKeyFrames();
                    slideY.KeyFrames.Add(new DiscreteDoubleKeyFrame { KeyTime = KeyTime.FromTimeSpan(TimeSpan.Zero), Value = fromOffsetY });
                    slideY.KeyFrames.Add(new SplineDoubleKeyFrame { KeyTime = keyTime, Value = 0.0, KeySpline = MakeDecelerateSpline() });
                    Storyboard.SetTarget(slideY, tt);
                    Storyboard.SetTargetProperty(slideY, "Y");
                    sb.Children.Add(slideY);
                }
                if (Math.Abs(fromOffsetX) > 0.1)
                {
                    tt.X = fromOffsetX;
                    var slideX = new DoubleAnimationUsingKeyFrames();
                    slideX.KeyFrames.Add(new DiscreteDoubleKeyFrame { KeyTime = KeyTime.FromTimeSpan(TimeSpan.Zero), Value = fromOffsetX });
                    slideX.KeyFrames.Add(new SplineDoubleKeyFrame { KeyTime = keyTime, Value = 0.0, KeySpline = MakeDecelerateSpline() });
                    Storyboard.SetTarget(slideX, tt);
                    Storyboard.SetTargetProperty(slideX, "X");
                    sb.Children.Add(slideX);
                }
            }

            sb.Completed += (_, _) =>
            {
                if (ActiveElementStoryboards.TryGetValue(element, out var cur) && ReferenceEquals(cur, sb))
                {
                    element.Opacity = 1.0;
                    if (element.RenderTransform is TranslateTransform t)
                    {
                        t.X = 0;
                        t.Y = 0;
                    }
                }
            };
            ActiveElementStoryboards.AddOrUpdate(element, sb);
            sb.Begin();
        }
        catch
        {
            element.Opacity = 1.0;
        }
    }

    /// <summary>
    /// 为浮层/提示面板提供轻量原生的显隐透明度过渡（隐藏后自动复位 Collapsed）。
    /// </summary>
    public static void FadeSetVisibility(UIElement? element, bool visible, int durationMs = 140, double slideY = 0)
    {
        if (element is null) return;
        try
        {
            if (ActiveElementStoryboards.TryGetValue(element, out var prev))
            {
                prev.Stop();
            }

            if (visible)
            {
                var wasCollapsed = element.Visibility != Visibility.Visible;
                element.Visibility = Visibility.Visible;
                if (!wasCollapsed && element.Opacity >= 0.99) return;
                PlayFadeIn(element, durationMs, fromOffsetY: slideY);
                return;
            }

            if (element.Visibility != Visibility.Visible) return;
            var sb = new Storyboard();
            var fade = new DoubleAnimation
            {
                From = element.Opacity,
                To = 0.0,
                Duration = new Duration(TimeSpan.FromMilliseconds(Math.Max(60, durationMs))),
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn }
            };
            Storyboard.SetTarget(fade, element);
            Storyboard.SetTargetProperty(fade, "Opacity");
            sb.Children.Add(fade);
            sb.Completed += (_, _) =>
            {
                if (ActiveElementStoryboards.TryGetValue(element, out var cur) && ReferenceEquals(cur, sb))
                {
                    element.Visibility = Visibility.Collapsed;
                    element.Opacity = 1.0;
                }
            };
            ActiveElementStoryboards.AddOrUpdate(element, sb);
            sb.Begin();
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

    private static bool _systemPaletteCaptured;
    private static Color _sysAccent, _sysLight1, _sysLight2, _sysLight3, _sysDark1, _sysDark2, _sysDark3;
    private static string _lastAccentKey = ThemeAccentHelper.SystemKey;
    private static bool? _lastAccentDark;

    /// <summary>
    /// 防止主题强调色刷新期间触发重复的 ActualThemeChanged 回调
    /// </summary>
    public static bool IsApplyingThemeRefresh { get; private set; }

    private static Color ToUiColor(RgbColor c) => Color.FromArgb(255, c.R, c.G, c.B);

    private static void EnsureSystemPaletteCaptured()
    {
        if (_systemPaletteCaptured) return;
        _systemPaletteCaptured = true;
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

    private static bool ApplyAccentResources(bool isDark, string accentSetting)
    {
        var normalized = ThemeAccentHelper.NormalizeSetting(accentSetting);
        EnsureSystemPaletteCaptured();

        if (_lastAccentKey == normalized && _lastAccentDark == isDark)
        {
            return false;
        }
        if (normalized == ThemeAccentHelper.SystemKey && _lastAccentKey == ThemeAccentHelper.SystemKey)
        {
            _lastAccentDark = isDark;
            return false;
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
                            brush.Color = color;
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

        var lightMap = BuildBrushMap(false, baseC, l1, l2, l3, d1, d2, d3, darkTextLight, darkTextDark);
        var darkMap = BuildBrushMap(true, baseC, l1, l2, l3, d1, d2, d3, darkTextLight, darkTextDark);
        var activeMap = isDark ? darkMap : lightMap;

        // 仅在应用自身顶级 ResourceDictionary 上覆写键，绝不改动只读 XamlControlsResources(MergedDictionaries) 防止 XAML 底层 E_NOINTERFACE 崩溃
        var appResources = Application.Current?.Resources;
        if (appResources is not null)
        {
            ApplyColorKeys(appResources);
            ApplyBrushMap(appResources, activeMap);
        }

        _lastAccentKey = normalized;
        _lastAccentDark = isDark;
        return true;
    }

    public static void ApplyThemeAndTitleBarColors(Window window, string themePreference, bool pureBlackDark, string? accentColorHex = null)
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

        // 先按目标主题确定深浅（防止 RequestedTheme 切换首帧 ActualTheme 尚未同步导致的短暂反色）
        var isDark = desiredTheme switch
        {
            ElementTheme.Dark => true,
            ElementTheme.Light => false,
            _ => root.ActualTheme == ElementTheme.Dark
        };

        var accentChanged = ApplyAccentResources(isDark, accentColorHex ?? ThemeAccentHelper.SystemKey);
        try
        {
            // 在根节点注入独立浅/深字典副本，确保运行时立即响应与跨页面继承
            var norm = ThemeAccentHelper.NormalizeSetting(accentColorHex);
            if (norm != ThemeAccentHelper.SystemKey && ThemeAccentHelper.TryParseHexRgb(norm, out var rgb))
            {
                var p = ThemeAccentHelper.ComputePalette(rgb);
                var fill = ToUiColor(isDark ? p.Light2 : p.Dark1);
                var txt = ToUiColor(isDark ? p.Light3 : p.Dark2);
                var fg = (isDark ? p.DarkTextOnDarkFill : p.DarkTextOnLightFill) ? Colors.Black : Colors.White;
                foreach (var (k, c) in new (string, Color)[]
                {
                    ("AccentFillColorDefaultBrush", fill),
                    ("AccentTextFillColorPrimaryBrush", txt),
                    ("TextOnAccentFillColorPrimaryBrush", fg),
                    ("AccentButtonBackground", fill),
                    ("AccentButtonForeground", fg),
                    ("ToggleSwitchFillOn", fill),
                    ("CheckBoxCheckBackgroundFillChecked", fill),
                    ("CheckBoxCheckBackgroundStrokeChecked", fill),
                    ("CheckBoxCheckGlyphForegroundChecked", fg)
                })
                {
                    if (root.Resources.TryGetValue(k, out var ex) && ex is SolidColorBrush b) b.Color = c;
                    else root.Resources[k] = new SolidColorBrush(c);
                }
            }
            else if (accentChanged)
            {
                foreach (var k in new[]
                {
                    "AccentFillColorDefaultBrush", "AccentTextFillColorPrimaryBrush",
                    "TextOnAccentFillColorPrimaryBrush", "AccentButtonBackground",
                    "AccentButtonForeground", "ToggleSwitchFillOn",
                    "CheckBoxCheckBackgroundFillChecked", "CheckBoxCheckBackgroundStrokeChecked",
                    "CheckBoxCheckGlyphForegroundChecked"
                })
                {
                    root.Resources.Remove(k);
                }
            }
        }
        catch
        {
        }

        if (accentChanged && root.IsLoaded && !IsApplyingThemeRefresh)
        {
            IsApplyingThemeRefresh = true;
            try
            {
                var flip = isDark ? ElementTheme.Light : ElementTheme.Dark;
                root.RequestedTheme = flip;
                root.RequestedTheme = desiredTheme;
            }
            finally
            {
                IsApplyingThemeRefresh = false;
            }
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
            throw new InvalidOperationException(LocalizationService.Instance.T("所选文件大小超过限制 (%d KB)", maxBytes / 1024));
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
                FailedImageUrls[cacheKey] = DateTimeOffset.UtcNow;
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
