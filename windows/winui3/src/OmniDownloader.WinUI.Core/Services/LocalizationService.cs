using System.Reflection;
using System.Text.Json;
using System.Text.RegularExpressions;
using CommunityToolkit.Mvvm.ComponentModel;

namespace OmniDownloader.WinUI.Core.Services;

/// <summary>
/// 多语言本地化服务：支持简体中文 / 繁體中文 / English / 日本語 无重启实时切换
/// </summary>
public sealed partial class LocalizationService : ObservableObject
{
    public static LocalizationService Instance { get; } = new();

    private static readonly IReadOnlyList<(string Code, string DisplayName)> SupportedLanguageList = new[]
    {
        ("zh-CN", "简体中文"),
        ("zh-TW", "繁體中文"),
        ("en", "English"),
        ("ja", "日本語"),
    };

    private static readonly Regex FormatPlaceholderRegex = new(@"%%|%[sd]", RegexOptions.Compiled);

    private readonly Dictionary<string, Dictionary<string, string>> _dictionaries;
    private string _currentLanguage = "zh-CN";

    public event EventHandler<string>? LanguageChanged;

    public IReadOnlyList<(string Code, string DisplayName)> SupportedLanguages => SupportedLanguageList;

    public string CurrentLanguage => _currentLanguage;

    public string CurrentLanguageDisplayName =>
        SupportedLanguageList.FirstOrDefault(x => x.Code == _currentLanguage).DisplayName ?? "简体中文";

    public LocalizationService()
    {
        _dictionaries = LoadEmbeddedDictionaries();
    }

    private static Dictionary<string, Dictionary<string, string>> LoadEmbeddedDictionaries()
    {
        var asm = Assembly.GetExecutingAssembly();
        const string resourceName = "OmniDownloader.WinUI.Core.Assets.strings.json";
        using var stream = asm.GetManifestResourceStream(resourceName);
        if (stream is null)
        {
            return new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        }

        return JsonSerializer.Deserialize<Dictionary<string, Dictionary<string, string>>>(stream)
               ?? new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
    }

    public void SetLanguage(string languageCode)
    {
        var normalized = SupportedLanguageList.Any(x => x.Code == languageCode) ? languageCode : "zh-CN";
        if (_currentLanguage == normalized) return;

        _currentLanguage = normalized;
        // 通知所有绑定此对象属性与索引器的视图即时刷新文案
        OnPropertyChanged(string.Empty);
        LanguageChanged?.Invoke(this, _currentLanguage);
    }

    /// <summary>
    /// 获取本地化文本，支持 %s / %d / %% 格式化占位符（兼容 Android 与 Web strings.json 约定）
    /// </summary>
    public string T(string key, params object[] args)
    {
        if (string.IsNullOrEmpty(key)) return string.Empty;

        var template = key;
        if (_dictionaries.TryGetValue(_currentLanguage, out var dict) && dict.TryGetValue(key, out var translated))
        {
            template = translated;
        }
        else if (_dictionaries.TryGetValue("zh-CN", out var fallback) && fallback.TryGetValue(key, out var zh))
        {
            template = zh;
        }

        if (args.Length == 0) return template;

        var index = 0;
        return FormatPlaceholderRegex.Replace(template, match =>
        {
            if (match.Value == "%%") return "%";
            return index < args.Length ? Convert.ToString(args[index++]) ?? string.Empty : match.Value;
        });
    }

    /// <summary>
    /// 索引器访问，便于后台代码或 Binding 简洁读取
    /// </summary>
    public string this[string key] => T(key);

    // 常用静态导航与基础文案属性（供 XAML x:Bind OneWay 即时更新）
    public string NavHome => T("首页");
    public string NavBatch => T("批量下载");
    public string NavTasks => T("任务");
    public string NavSettings => T("设置");
    public string NavHelp => T("使用帮助");
    public string HomePlaceholder => T("粘贴视频链接或分享文本...");
    public string BtnPasteAnalyze => T("粘贴剪贴板");
    public string BtnAnalyze => T("立即解析");
    public string BtnAnalyzing => T("解析中...");
    public string BrowserAssistTitle => T("浏览器辅助提取");
    public string RecentTasksTitle => T("最近任务");
    public string ViewAllLabel => T("查看全部");
    public string BatchTitle => T("批量下载");
    public string BatchSubtitle => T("支持分享文本、TXT 文件、100 条链接去重");
    public string TasksTitle => T("下载管理");
    public string SettingsTitle => T("偏好设置");
    public string LanguageLabel => T("语言 (Language)");
    public string ThemeLabel => T("外观");
    public string SupportedPlatformsHint => T("支持 哔哩哔哩、抖音、快手、小红书、YouTube、TikTok、X 等 10 大主流平台");
    public string AboutNativeNote => T("原生桌面体验：支持跟随 Windows 系统主题与主题强调色，自动复用本机 OmniDownloader 配置、Cookie 与下载任务队列。");
}
