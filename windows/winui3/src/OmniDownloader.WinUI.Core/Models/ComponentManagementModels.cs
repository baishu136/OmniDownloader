using System.Globalization;
using System.Text.Json.Serialization;
using OmniDownloader.WinUI.Core.Services;

namespace OmniDownloader.WinUI.Core.Models;

/// <summary>
/// 主题强调色预设条目，支持系统默认与自定义 HEX 强调色规范化计算
/// </summary>
public sealed record ThemeColorPreset(string Key, string NameKey, string PreviewHex);

/// <summary>
/// 24 位无透明色通道（供 Core 层跨平台单元验证）
/// </summary>
public readonly record struct RgbColor(byte R, byte G, byte B)
{
    public string ToHex() => $"#{R:X2}{G:X2}{B:X2}";
}

/// <summary>
/// WinUI 3 Fluent 强调色阶：基础色、亮色阶 1-3、暗色阶 1-3 与对比文字建议
/// </summary>
public sealed record AccentShadePalette(
    RgbColor Base,
    RgbColor Light1,
    RgbColor Light2,
    RgbColor Light3,
    RgbColor Dark1,
    RgbColor Dark2,
    RgbColor Dark3,
    bool DarkTextOnLightFill,
    bool DarkTextOnDarkFill);

/// <summary>
/// 自定义主题颜色工具类：规范化设置值、色阶推导与 WCAG 对比度文本判断
/// </summary>
public static class ThemeAccentHelper
{
    public const string SystemKey = "system";

    public static readonly IReadOnlyList<ThemeColorPreset> Presets = new ThemeColorPreset[]
    {
        new(SystemKey, "跟随系统", "#0078D4"),
        new("#3B5BDB", "钴蓝", "#3B5BDB"),
        new("#0078D4", "经典蓝", "#0078D4"),
        new("#8B5CF6", "暮光紫", "#8B5CF6"),
        new("#E11D48", "绯红", "#E11D48"),
        new("#059669", "翡翠绿", "#059669"),
        new("#D97706", "琥珀橙", "#D97706"),
        new("#EC4899", "樱花粉", "#EC4899"),
        new("#0891B2", "青碧", "#0891B2")
    };

    /// <summary>
    /// 规范化主题色设置值为 "system" 或标准大写 #RRGGBB；无效输入自动回退到 "system"
    /// </summary>
    public static string NormalizeSetting(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return SystemKey;
        var trimmed = value.Trim();
        if (string.Equals(trimmed, SystemKey, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(trimmed, "auto", StringComparison.OrdinalIgnoreCase))
        {
            return SystemKey;
        }
        return TryParseHexRgb(trimmed, out var rgb) ? rgb.ToHex() : SystemKey;
    }

    public static bool TryParseHexRgb(string? input, out RgbColor color)
    {
        color = default;
        if (string.IsNullOrWhiteSpace(input)) return false;
        var s = input.Trim();
        if (s.StartsWith('#')) s = s[1..];
        if (s.Length == 8) s = s[2..]; // 兼容 #AARRGGBB 格式
        if (s.Length != 6) return false;

        if (byte.TryParse(s[0..2], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var r) &&
            byte.TryParse(s[2..4], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var g) &&
            byte.TryParse(s[4..6], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var b))
        {
            color = new RgbColor(r, g, b);
            return true;
        }
        return false;
    }

    public static AccentShadePalette ComputePalette(RgbColor baseColor)
    {
        var l1 = Blend(baseColor, new RgbColor(255, 255, 255), 0.18);
        var l2 = Blend(baseColor, new RgbColor(255, 255, 255), 0.34);
        var l3 = Blend(baseColor, new RgbColor(255, 255, 255), 0.50);
        var d1 = Blend(baseColor, new RgbColor(0, 0, 0), 0.12);
        var d2 = Blend(baseColor, new RgbColor(0, 0, 0), 0.25);
        var d3 = Blend(baseColor, new RgbColor(0, 0, 0), 0.38);

        return new AccentShadePalette(
            baseColor, l1, l2, l3, d1, d2, d3,
            DarkTextOnLightFill: ShouldUseDarkForeground(d1),
            DarkTextOnDarkFill: ShouldUseDarkForeground(l2));
    }

    public static bool ShouldUseDarkForeground(RgbColor bg)
    {
        var luminance = (0.2126 * bg.R + 0.7152 * bg.G + 0.0722 * bg.B) / 255.0;
        return luminance > 0.58;
    }

    private static RgbColor Blend(RgbColor a, RgbColor target, double weight)
    {
        var r = (byte)Math.Clamp((int)Math.Round(a.R + (target.R - a.R) * weight), 0, 255);
        var g = (byte)Math.Clamp((int)Math.Round(a.G + (target.G - a.G) * weight), 0, 255);
        var b = (byte)Math.Clamp((int)Math.Round(a.B + (target.B - a.B) * weight), 0, 255);
        return new RgbColor(r, g, b);
    }
}

/// <summary>
/// 主题明暗解析与防闪烁策略工具：确保窗口首帧与系统实际深浅一致，避免加载后二次反色闪烁
/// </summary>
public static class ThemeAppearanceLogic
{
    public static bool ResolveEffectiveDark(string? themePreference, bool osIsDark, bool rootIsLoaded, bool rootActualIsDark)
    {
        if (string.Equals(themePreference, "dark", StringComparison.OrdinalIgnoreCase)) return true;
        if (string.Equals(themePreference, "light", StringComparison.OrdinalIgnoreCase)) return false;
        return rootIsLoaded ? rootActualIsDark : osIsDark;
    }

    public static bool ShouldReapplyOnActualThemeChanged(string? themePreference) =>
        !string.Equals(themePreference, "light", StringComparison.OrdinalIgnoreCase) &&
        !string.Equals(themePreference, "dark", StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// 窗口背景材质风格枚举（还原原默认原生参数并整合 Ghost-Downloader-3 多材质外观选项）
/// </summary>
public enum EffectiveBackdropKind
{
    Solid,
    NativeOriginal,
    MicaBase,
    MicaAlt,
    WallpaperMica,
    WallpaperMicaAlt,
    Acrylic,
    Aero
}

/// <summary>
/// 背景材质偏好解析与 Windows 10/11 兼容性差异化策略
/// </summary>
public static class BackdropStyleLogic
{
    public const string AutoKey = "auto";
    public const string MicaKey = "mica";
    public const string MicaAltKey = "mica_alt";
    public const string AcrylicKey = "acrylic";
    public const string AeroKey = "aero";
    public const string SolidKey = "solid";

    public static string NormalizeStyle(string? style) =>
        (style ?? string.Empty).Trim().ToLowerInvariant() switch
        {
            MicaKey => MicaKey,
            MicaAltKey or "micaalt" or "mica-alt" => MicaAltKey,
            AcrylicKey => AcrylicKey,
            AeroKey or "glass" or "blur" => AeroKey,
            SolidKey or "none" or "pure" => SolidKey,
            "original" or "native" or "default" => AutoKey,
            _ => AutoKey
        };

    public static EffectiveBackdropKind ResolveEffectiveKind(
        string? requestedStyle,
        bool isDark,
        bool pureBlackDark,
        bool micaSupported,
        bool acrylicSupported)
    {
        if (isDark && pureBlackDark)
        {
            return EffectiveBackdropKind.Solid;
        }

        var norm = NormalizeStyle(requestedStyle);
        return norm switch
        {
            SolidKey => EffectiveBackdropKind.Solid,
            AeroKey => acrylicSupported ? EffectiveBackdropKind.Aero : EffectiveBackdropKind.Solid,
            AcrylicKey => acrylicSupported ? EffectiveBackdropKind.Acrylic : EffectiveBackdropKind.Solid,
            MicaAltKey => micaSupported
                ? EffectiveBackdropKind.MicaAlt
                : EffectiveBackdropKind.WallpaperMicaAlt,
            MicaKey => micaSupported
                ? EffectiveBackdropKind.MicaBase
                : EffectiveBackdropKind.WallpaperMica,
            // 默认材质（auto）：完整回归添加 Ghost 背景前的原版原生参数（Win11 原生 Mica Base / Win10 原生 DesktopAcrylic）
            _ => (micaSupported || acrylicSupported)
                ? EffectiveBackdropKind.NativeOriginal
                : EffectiveBackdropKind.WallpaperMica
        };
    }
}

/// <summary>
/// 持久化存储的用户自定义扩展组件条目，为后续接入外部插件与自定义引擎做准备
/// </summary>
public sealed class CustomComponentEntry
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("description")]
    public string Description { get; set; } = string.Empty;

    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "extractor"; // extractor | postprocessor | service

    [JsonPropertyName("entryPoint")]
    public string EntryPoint { get; set; } = string.Empty;

    [JsonPropertyName("enabled")]
    public bool Enabled { get; set; } = true;
}

/// <summary>
/// 组件管理列表统一描述项（包含核心必要组件、内置可选组件与用户自定义接入组件）
/// </summary>
public sealed class ManagedComponentItem
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string DisplayDescription { get; init; } = string.Empty;
    public string Category { get; init; } = "core"; // core | optional | custom
    public string KindLabel { get; init; } = string.Empty;
    public string StatusBadge { get; init; } = string.Empty;
    public string EntryPoint { get; init; } = string.Empty;
    public bool IsRequired { get; init; }
    public bool IsEnabled { get; set; }
    public bool IsCustom { get; init; }

    /// <summary>
    /// 必要组件锁定启用，控件表现为灰色不可取消勾选
    /// </summary>
    public bool CanToggle => !IsRequired;

    public bool HasEntryPoint => !string.IsNullOrWhiteSpace(EntryPoint);
}

/// <summary>
/// 组件目录构建器：将引擎探测状态与本地持久化偏好合成为可扩展的组件清单
/// </summary>
public static class ComponentCatalog
{
    public static List<ManagedComponentItem> BuildItems(
        LocalClientState state,
        EngineDiagnostics? diagnostics,
        bool isBackendConnected,
        string serviceBaseUrl,
        Func<string, string> translate,
        Func<string, object[], string>? translateFormat = null)
    {
        var disabledSet = new HashSet<string>(state.DisabledOptionalComponentIds ?? new List<string>(), StringComparer.OrdinalIgnoreCase);
        string Format(string key, params object[] args) =>
            translateFormat is not null ? translateFormat(key, args) : translate(key);

        var items = new List<ManagedComponentItem>
        {
            new()
            {
                Id = "core-engine",
                DisplayName = translate("OmniDownloader 核心引擎"),
                DisplayDescription = Format("必要核心调度服务，负责任务队列、文件落盘与媒体流解析（%s）", serviceBaseUrl),
                Category = "core",
                StatusBadge = translate(isBackendConnected ? "运行中" : "待连接"),
                IsRequired = true,
                IsEnabled = true,
                IsCustom = false
            },
            new()
            {
                Id = "python-runtime",
                DisplayName = translate("Python 运行环境"),
                DisplayDescription = translate("驱动内置核心服务及解析器生态的必要基础运行时"),
                Category = "core",
                StatusBadge = diagnostics is { Python.Length: > 0 } ? $"v{diagnostics.Python}" : translate("必要"),
                IsRequired = true,
                IsEnabled = true,
                IsCustom = false
            },
            new()
            {
                Id = "yt-dlp",
                DisplayName = translate("yt-dlp 核心解析器"),
                DisplayDescription = translate("提供主流站点视频与音频流嗅探、清晰度识别及分段下载支持"),
                Category = "core",
                StatusBadge = diagnostics is { YtDlp.Length: > 0 } ? diagnostics.YtDlp : translate("必要"),
                IsRequired = true,
                IsEnabled = true,
                IsCustom = false
            },
            new()
            {
                Id = "ffmpeg",
                DisplayName = translate("FFmpeg 音视频处理"),
                DisplayDescription = translate("负责高清音视频无损封装、格式转码与高保真 GIF 导出"),
                Category = "core",
                StatusBadge = diagnostics is null ? translate("必要") : translate(diagnostics.FfmpegAvailable ? "就绪" : "未就绪"),
                IsRequired = true,
                IsEnabled = true,
                IsCustom = false
            },
            new()
            {
                Id = "yt-dlp-ejs",
                DisplayName = translate("yt-dlp EJS 解算组件"),
                DisplayDescription = translate("可选 JavaScript 签名解算模块，提升受保护视频链接解析成功率"),
                Category = "optional",
                StatusBadge = diagnostics is { Ejs.Length: > 0 } ? $"v{diagnostics.Ejs}" : translate("可选"),
                IsRequired = false,
                IsEnabled = !disabledSet.Contains("yt-dlp-ejs"),
                IsCustom = false
            },
            new()
            {
                Id = "deno-runtime",
                DisplayName = translate("Deno JS 运行时"),
                DisplayDescription = translate("可选独立脚本执行环境，为复杂网页算法和挑战校验提供沙箱支持"),
                Category = "optional",
                StatusBadge = diagnostics is null ? translate("可选") : translate(diagnostics.DenoAvailable ? "可用" : "缺失"),
                IsRequired = false,
                IsEnabled = !disabledSet.Contains("deno-runtime"),
                IsCustom = false
            },
            new()
            {
                Id = "webview2-bridge",
                DisplayName = translate("WebView2 登录与网页组件"),
                DisplayDescription = translate("可选内置网页认证与独立浏览器会话捕获扩展，用于账号免填 Cookie 登录"),
                Category = "optional",
                StatusBadge = translate("可选"),
                IsRequired = false,
                IsEnabled = !disabledSet.Contains("webview2-bridge"),
                IsCustom = false
            }
        };

        foreach (var custom in state.CustomComponents ?? Enumerable.Empty<CustomComponentEntry>())
        {
            if (string.IsNullOrWhiteSpace(custom.Id) || string.IsNullOrWhiteSpace(custom.Name)) continue;
            var kindText = custom.Kind switch
            {
                "postprocessor" => translate("后处理插件"),
                "service" => translate("外部扩展服务"),
                _ => translate("外部解析器")
            };
            var desc = string.IsNullOrWhiteSpace(custom.Description)
                ? kindText
                : custom.Description;
            if (!string.IsNullOrWhiteSpace(custom.EntryPoint))
            {
                desc = $"{desc} · {custom.EntryPoint}";
            }
            items.Add(new ManagedComponentItem
            {
                Id = custom.Id,
                DisplayName = custom.Name,
                DisplayDescription = desc,
                Category = "custom",
                KindLabel = kindText,
                StatusBadge = kindText,
                EntryPoint = custom.EntryPoint,
                IsRequired = false,
                IsEnabled = custom.Enabled,
                IsCustom = true
            });
        }

        return items;
    }
}
