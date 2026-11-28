using System.Runtime.InteropServices;
using System.Text;
using Microsoft.UI.Composition;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics.Imaging;
using Windows.UI;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// WinUI 3 安全系统背景基类：
/// 1. 在连接期间稳定持有 <see cref="ICompositionSupportsSystemBackdrop"/> 受管引用，避免 GC 回收代理后引发 WinRT 内部弱引用空指针；
/// 2. 统一兜底处理 <see cref="OnDefaultSystemBackdropConfigurationChanged"/> 中 WinUI 底层偶发传入 null target 或抛出 ArgumentException("target") 的框架缺陷，确保深浅色切换不崩溃且配置始终同步。
/// </summary>
public abstract class SafeSystemBackdropBase : SystemBackdrop
{
    private ISystemBackdropControllerWithTargets? _controller;
    private SystemBackdropConfiguration? _configuration;
    private ICompositionSupportsSystemBackdrop? _connectedTarget;
    private bool _isDisposing;

    public bool IsConnected => _controller is not null && _connectedTarget is not null && !_isDisposing;

    protected abstract ISystemBackdropControllerWithTargets CreateController();

    protected virtual void OnControllerAttached(ISystemBackdropControllerWithTargets controller)
    {
    }

    protected ISystemBackdropControllerWithTargets? ActiveController => _controller;

    protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop connectedTarget, XamlRoot xamlRoot)
    {
        base.OnTargetConnected(connectedTarget, xamlRoot);
        _isDisposing = false;
        _connectedTarget = connectedTarget;

        DetachCurrentController(connectedTarget);

        _configuration = ResolveSafeConfiguration(connectedTarget, xamlRoot, existing: null);
        var controller = CreateController();
        _controller = controller;
        OnControllerAttached(controller);
        try
        {
            controller.SetSystemBackdropConfiguration(_configuration);
            controller.AddSystemBackdropTarget(connectedTarget);
        }
        catch
        {
            DetachCurrentController(connectedTarget);
        }
    }

    protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop disconnectedTarget)
    {
        _isDisposing = true;
        _connectedTarget = null;
        DetachCurrentController(disconnectedTarget);
        _configuration = null;
        try
        {
            base.OnTargetDisconnected(disconnectedTarget);
        }
        catch
        {
        }
    }

    protected override void OnDefaultSystemBackdropConfigurationChanged(ICompositionSupportsSystemBackdrop target, XamlRoot xamlRoot)
    {
        if (_isDisposing || _controller is null || _connectedTarget is null)
        {
            return;
        }

        // WinUI 3 在经历 GC 或前序自定义背景销毁后，可能以 null target 触发主题回调；回退至已连接的受管目标确保配置仍能正确切换
        var effectiveTarget = target ?? _connectedTarget;
        if (target is not null)
        {
            try
            {
                base.OnDefaultSystemBackdropConfigurationChanged(target, xamlRoot);
            }
            catch (ArgumentException)
            {
            }
            catch
            {
            }
        }

        _configuration = ResolveSafeConfiguration(effectiveTarget, xamlRoot, _configuration);
        if (_configuration is not null)
        {
            try
            {
                _controller?.SetSystemBackdropConfiguration(_configuration);
            }
            catch
            {
            }
        }
    }

    private SystemBackdropConfiguration ResolveSafeConfiguration(
        ICompositionSupportsSystemBackdrop? target,
        XamlRoot? xamlRoot,
        SystemBackdropConfiguration? existing)
    {
        SystemBackdropConfiguration? cfg = null;
        if (target is not null && xamlRoot is not null)
        {
            try
            {
                cfg = GetDefaultSystemBackdropConfiguration(target, xamlRoot);
            }
            catch
            {
                cfg = null;
            }
        }

        if (cfg is null)
        {
            cfg = existing ?? new SystemBackdropConfiguration { IsInputActive = true };
        }
        if (xamlRoot?.Content is FrameworkElement fe && fe.ActualTheme != ElementTheme.Default)
        {
            var expected = ResolveBackdropTheme(fe.ActualTheme);
            if (cfg.Theme != expected)
            {
                cfg.Theme = expected;
            }
        }
        return cfg;
    }

    private static SystemBackdropTheme ResolveBackdropTheme(ElementTheme actualTheme) => actualTheme switch
    {
        ElementTheme.Dark => SystemBackdropTheme.Dark,
        ElementTheme.Light => SystemBackdropTheme.Light,
        _ => SystemBackdropTheme.Default
    };

    private void DetachCurrentController(ICompositionSupportsSystemBackdrop? target)
    {
        var controller = _controller;
        _controller = null;
        if (controller is null) return;
        if (target is not null)
        {
            try { controller.RemoveSystemBackdropTarget(target); } catch { }
        }
        try { controller.Dispose(); } catch { }
    }
}

/// <summary>
/// 安全云母系统背景控制器（替代内置密封 MicaBackdrop，避免主题切换触发 WinRT target 参数异常）。
/// </summary>
public sealed class SafeMicaBackdrop : SafeSystemBackdropBase
{
    private readonly MicaKind _kind;

    public SafeMicaBackdrop(MicaKind kind = MicaKind.Base)
    {
        _kind = kind;
    }

    public bool MatchesKind(MicaKind kind) => _kind == kind;

    protected override ISystemBackdropControllerWithTargets CreateController() => new MicaController { Kind = _kind };
}

/// <summary>
/// 可调参数亚克力/Aero 系统背景控制器（直接驱动 DesktopAcrylicController）：
/// 支持系统原生默认参数（Win10 经典外观）及按风格/深浅色动态调整 TintOpacity、LuminosityOpacity 与 Thin/Default 材质，原地刷新不闪烁。
/// </summary>
public sealed class ConfigurableAcrylicBackdrop : SafeSystemBackdropBase
{
    private readonly DesktopAcrylicKind _kind;
    private readonly bool _isNativeDefault;
    private Color _tintColor;
    private Color _fallbackColor;
    private float _tintOpacity;
    private float _luminosityOpacity;

    private ConfigurableAcrylicBackdrop(
        DesktopAcrylicKind kind,
        bool isNativeDefault,
        Color tintColor,
        float tintOpacity,
        float luminosityOpacity,
        Color fallbackColor)
    {
        _kind = kind;
        _isNativeDefault = isNativeDefault;
        _tintColor = tintColor;
        _tintOpacity = Math.Clamp(tintOpacity, 0f, 1f);
        _luminosityOpacity = Math.Clamp(luminosityOpacity, 0f, 1f);
        _fallbackColor = fallbackColor;
    }

    public ConfigurableAcrylicBackdrop(
        DesktopAcrylicKind kind,
        Color tintColor,
        float tintOpacity,
        float luminosityOpacity,
        Color fallbackColor)
        : this(kind, isNativeDefault: false, tintColor, tintOpacity, luminosityOpacity, fallbackColor)
    {
    }

    public static ConfigurableAcrylicBackdrop CreateNativeDefault() =>
        new(DesktopAcrylicKind.Default, isNativeDefault: true, default, 0f, 0f, default);

    public bool MatchesKind(DesktopAcrylicKind kind) => _kind == kind && !_isNativeDefault;

    public bool MatchesConfiguration(DesktopAcrylicKind kind, bool isNativeDefault) =>
        _kind == kind && _isNativeDefault == isNativeDefault;

    public void UpdateParameters(
        Color tintColor,
        float tintOpacity,
        float luminosityOpacity,
        Color fallbackColor)
    {
        if (_isNativeDefault) return;
        _tintColor = tintColor;
        _tintOpacity = Math.Clamp(tintOpacity, 0f, 1f);
        _luminosityOpacity = Math.Clamp(luminosityOpacity, 0f, 1f);
        _fallbackColor = fallbackColor;
        if (ActiveController is DesktopAcrylicController acrylic)
        {
            ApplyToController(acrylic);
        }
    }

    protected override ISystemBackdropControllerWithTargets CreateController() => new DesktopAcrylicController { Kind = _kind };

    protected override void OnControllerAttached(ISystemBackdropControllerWithTargets controller)
    {
        if (!_isNativeDefault && controller is DesktopAcrylicController acrylic)
        {
            ApplyToController(acrylic);
        }
    }

    private void ApplyToController(DesktopAcrylicController controller)
    {
        try
        {
            controller.TintColor = _tintColor;
            controller.TintOpacity = _tintOpacity;
            controller.LuminosityOpacity = _luminosityOpacity;
            controller.FallbackColor = _fallbackColor;
        }
        catch
        {
        }
    }
}

/// <summary>
/// 桌面壁纸主色调轻量采样器：在 Windows 10 不支持原生 MicaController 时，
/// 通过低分辨率 WIC 采样（16×16，零常驻大图）提取壁纸环境色并合成 Mica / Mica Alt 哑光底色。
/// </summary>
public static class DesktopWallpaperTintHelper
{
    private const uint SpiGetDesktopWallpaper = 0x0073;
    private static readonly object CacheLock = new();
    private static string _cachedKey = string.Empty;
    private static Color? _cachedDominant;

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SystemParametersInfo(uint uiAction, uint uiParam, StringBuilder pvParam, uint fWinIni);

    /// <summary>
    /// 计算适配 Windows 10 的 Mica / Mica Alt 柔和背景基色（混合桌面壁纸平均色，无壁纸时回退中性冷调）。
    /// </summary>
    public static Color ResolveMicaSurfaceColor(bool isDark, bool isAlt)
    {
        var baseColor = isDark
            ? (isAlt ? Color.FromArgb(255, 19, 21, 28) : Color.FromArgb(255, 30, 32, 40))
            : (isAlt ? Color.FromArgb(255, 228, 233, 243) : Color.FromArgb(255, 243, 245, 250));

        var dominant = TryGetCachedWallpaperTint() ?? Color.FromArgb(255, 76, 98, 138);
        if (isDark)
        {
            // 深色云母避免亮壁纸均值拉高整体明度，先将壁纸高光抑制到暗调色域再做柔和混色
            dominant = Color.FromArgb(255, (byte)(dominant.R / 2), (byte)(dominant.G / 2), (byte)(dominant.B / 2));
        }
        var weight = isDark
            ? (isAlt ? 0.18 : 0.30)
            : (isAlt ? 0.16 : 0.11);

        return Blend(baseColor, dominant, weight);
    }

    private static Color? TryGetCachedWallpaperTint()
    {
        try
        {
            var sb = new StringBuilder(512);
            if (!SystemParametersInfo(SpiGetDesktopWallpaper, (uint)sb.Capacity, sb, 0))
            {
                return null;
            }
            var path = sb.ToString().Trim();
            if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
            {
                return null;
            }

            var stamp = File.GetLastWriteTimeUtc(path).Ticks;
            var cacheKey = $"{path}|{stamp}";
            lock (CacheLock)
            {
                if (string.Equals(_cachedKey, cacheKey, StringComparison.Ordinal))
                {
                    return _cachedDominant;
                }
            }

            var sampled = Task.Run(async () =>
            {
                using var stream = File.OpenRead(path);
                using var ras = stream.AsRandomAccessStream();
                var decoder = await BitmapDecoder.CreateAsync(ras);
                var transform = new BitmapTransform
                {
                    ScaledWidth = 16,
                    ScaledHeight = 16,
                    InterpolationMode = BitmapInterpolationMode.Linear
                };
                var pixelProvider = await decoder.GetPixelDataAsync(
                    BitmapPixelFormat.Bgra8,
                    BitmapAlphaMode.Ignore,
                    transform,
                    ExifOrientationMode.IgnoreExifOrientation,
                    ColorManagementMode.DoNotColorManage);
                var bytes = pixelProvider.DetachPixelData();
                if (bytes.Length < 4) return (Color?)null;

                long sumR = 0, sumG = 0, sumB = 0;
                var count = bytes.Length / 4;
                for (var i = 0; i <= bytes.Length - 4; i += 4)
                {
                    sumB += bytes[i];
                    sumG += bytes[i + 1];
                    sumR += bytes[i + 2];
                }
                if (count <= 0) return null;
                return Color.FromArgb(255, (byte)(sumR / count), (byte)(sumG / count), (byte)(sumB / count));
            }).GetAwaiter().GetResult();

            lock (CacheLock)
            {
                _cachedKey = cacheKey;
                _cachedDominant = sampled;
            }
            return sampled;
        }
        catch
        {
            return null;
        }
    }

    private static Color Blend(Color a, Color b, double weight)
    {
        var r = (byte)Math.Clamp((int)Math.Round(a.R + (b.R - a.R) * weight), 0, 255);
        var g = (byte)Math.Clamp((int)Math.Round(a.G + (b.G - a.G) * weight), 0, 255);
        var bl = (byte)Math.Clamp((int)Math.Round(a.B + (b.B - a.B) * weight), 0, 255);
        return Color.FromArgb(255, r, g, bl);
    }
}
