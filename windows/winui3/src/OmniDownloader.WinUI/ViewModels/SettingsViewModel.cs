using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.UI;

namespace OmniDownloader.WinUI.ViewModels;

/// <summary>
/// 设置页预设主题色圆点展示模型（支持原地刷新选中态，避免切换配色时整行重构闪烁）
/// </summary>
public sealed partial class ThemeColorSwatchItem : ObservableObject
{
    private bool _isSelected;

    public string Key { get; init; } = string.Empty;
    public string ToolTipText { get; init; } = string.Empty;
    public SolidColorBrush SwatchBrush { get; init; } = new(Colors.Transparent);
    public SolidColorBrush CheckBrush { get; init; } = new(Colors.White);

    public bool IsSelected
    {
        get => _isSelected;
        set
        {
            if (SetProperty(ref _isSelected, value))
            {
                OnPropertyChanged(nameof(SelectedVisibility));
            }
        }
    }

    public Visibility SelectedVisibility => IsSelected ? Visibility.Visible : Visibility.Collapsed;
}

/// <summary>
/// 设置页账号与 Cookie 平台下拉选项（保留内部标准平台 Key，展示文本随界面语言动态翻译）
/// </summary>
public sealed class SessionPlatformOption
{
    public string Key { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
}

/// <summary>
/// 设置页面 ViewModel：管理外观、语言、自定义主题色、连接配置、下载存储、网络代理、中转站、12 平台 Cookie 会话、组件管理与平台目录
/// </summary>
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private bool _suppressThemeSync;
    private EngineDiagnostics? _lastDiagnostics;

    public static readonly IReadOnlyList<string> SupportedSessionPlatforms = new[]
    {
        "YouTube", "X (Twitter)", "小红书", "小黑盒", "Pixiv",
        "抖音", "哔哩哔哩", "Instagram", "Facebook",
        "TikTok", "快手", "微博"
    };

    public IReadOnlyList<string> SessionPlatforms => SupportedSessionPlatforms;
    public ObservableCollection<SessionPlatformOption> SessionPlatformOptions { get; } = new();

    private string _currentCategory = "root";
    private string _selectedLanguage;
    private string _selectedTheme;
    private string _selectedBackdropStyle;
    private string _selectedAccentColor;
    private bool _usePureBlackDark;
    private string _serviceBaseUrl;
    private bool _autoStartBackend;
    private string _downloadDir = string.Empty;
    private bool _followDefaultDownloadDir = true;
    private string _videoDownloadDir = string.Empty;
    private string _imageDownloadDir = string.Empty;
    private string _audioDownloadDir = string.Empty;
    private bool _showRecentTasksOnHome = true;
    private bool _enableWebService = true;
    private int _maxConcurrentTasks = 3;
    private bool _autoStartWithWindows;
    private string _proxyUrl = string.Empty;
    private string _selectedSessionPlatform = "YouTube";
    private bool _isSelectedPlatformCookieDisabled;
    private string _sessionStatusBadge = "未导入";
    private string _sessionPolicySummary = string.Empty;
    private string _browserLoginStatusText = "使用独立 Edge 窗口登录。Google 登录后请返回目标平台，再点击「保存会话并关闭」；已保存不等于账号已验证。";
    private bool _isBrowserLoginBusy;
    private CancellationTokenSource? _loginPollCts;
    private string _bilibiliCookieInput = string.Empty;
    private bool _hasBilibiliSession;
    private string _engineStatusSummary = "点击下方按钮探测运行环境";
    private string _engineReadyBadge = "未探测";
    private bool _isEngineReady;
    private string _updateStatusText = string.Empty;
    private string _updateDownloadUrl = string.Empty;
    private bool _gpuAccelerationEnabled = true;
    private string _gpuPreferredVendor = "auto";
    private string _gpuStatusSummary = string.Empty;
    private string _gpuStatusBadge = string.Empty;
    private GpuStatusInfo? _lastGpuStatus;

    public string CurrentCategory
    {
        get => _currentCategory;
        set
        {
            if (SetProperty(ref _currentCategory, value))
            {
                OnPropertyChanged(nameof(IsRootPage));
                if (value == "engine") _ = CheckEngineDiagnosticsAsync();
                if (value == "accounts") _ = LoadSelectedSessionPolicyAsync();
            }
        }
    }

    public string SelectedLanguage
    {
        get => _selectedLanguage;
        set
        {
            if (SetProperty(ref _selectedLanguage, value) && !string.IsNullOrWhiteSpace(value))
            {
                _services.LocalState.Current.Language = value;
                _services.LocalState.Save();
                _services.Loc.SetLanguage(value);
                _main.Notify("界面语言已切换", InfoBarSeverity.Informational);
            }
        }
    }

    public string SelectedTheme
    {
        get => _selectedTheme;
        set
        {
            if (SetProperty(ref _selectedTheme, value) && !string.IsNullOrWhiteSpace(value))
            {
                _services.LocalState.Current.Theme = value;
                _services.LocalState.Save();
                _main.RaiseThemeChanged();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { Theme = value });
                }
            }
        }
    }

    public bool UsePureBlackDark
    {
        get => _usePureBlackDark;
        set
        {
            if (SetProperty(ref _usePureBlackDark, value))
            {
                _services.LocalState.Current.UsePureBlackDarkMode = value;
                _services.LocalState.Save();
                _main.RaiseThemeChanged();
            }
        }
    }

    public string SelectedBackdropStyle
    {
        get => _selectedBackdropStyle;
        set
        {
            var norm = BackdropStyleLogic.NormalizeStyle(value);
            if (SetProperty(ref _selectedBackdropStyle, norm))
            {
                _services.LocalState.Current.BackdropStyle = norm;
                _services.LocalState.Save();
                OnPropertyChanged(nameof(BackdropStyleDescription));
                _main.RaiseThemeChanged();
            }
        }
    }

    public string BackdropStyleDescription
    {
        get
        {
            var micaSupported = false;
            try { micaSupported = Microsoft.UI.Composition.SystemBackdrops.MicaController.IsSupported(); } catch { }
            return micaSupported
                ? _services.Loc.T("默认保留原版经典原生质感，并可选 Ghost 风格的 Mica、Mica Alt、Acrylic 磨砂、Aero 玻璃与纯色模式")
                : _services.Loc.T("默认保留原版经典亚克力参数，并可选壁纸染色云母、Ghost Acrylic 磨砂、Aero 玻璃与纯色模式");
        }
    }

    public string SelectedAccentColor
    {
        get => _selectedAccentColor;
        set
        {
            var norm = ThemeAccentHelper.NormalizeSetting(value);
            if (SetProperty(ref _selectedAccentColor, norm))
            {
                _services.LocalState.Current.AccentColor = norm;
                _services.LocalState.Save();
                RefreshAccentProperties();
                _main.RaiseThemeChanged();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { AccentColor = norm });
                }
            }
        }
    }

    public ObservableCollection<ThemeColorSwatchItem> AccentPresets { get; } = new();

    public bool IsCustomAccentActive => !string.Equals(SelectedAccentColor, ThemeAccentHelper.SystemKey, StringComparison.OrdinalIgnoreCase);

    public string ActiveAccentDisplayText
    {
        get
        {
            if (!IsCustomAccentActive) return _services.Loc.T("跟随系统");
            var preset = ThemeAccentHelper.Presets.FirstOrDefault(p =>
                string.Equals(p.Key, SelectedAccentColor, StringComparison.OrdinalIgnoreCase));
            return preset is not null
                ? $"{_services.Loc.T(preset.NameKey)} ({SelectedAccentColor})"
                : SelectedAccentColor;
        }
    }

    public SolidColorBrush ActiveAccentPreviewBrush
    {
        get
        {
            if (IsCustomAccentActive && ThemeAccentHelper.TryParseHexRgb(SelectedAccentColor, out var rgb))
            {
                return new SolidColorBrush(Color.FromArgb(255, rgb.R, rgb.G, rgb.B));
            }
            if (Application.Current?.Resources is not null &&
                Application.Current.Resources.TryGetValue("SystemAccentColor", out var sysObj) &&
                sysObj is Color sysCol)
            {
                return new SolidColorBrush(sysCol);
            }
            return new SolidColorBrush(Color.FromArgb(255, 0, 120, 212));
        }
    }

    public Color GetPickerInitialColor()
    {
        if (ThemeAccentHelper.TryParseHexRgb(SelectedAccentColor, out var rgb))
        {
            return Color.FromArgb(255, rgb.R, rgb.G, rgb.B);
        }
        return Color.FromArgb(255, 59, 91, 219);
    }

    [RelayCommand]
    private void SelectAccentPreset(string? key) => SelectedAccentColor = key ?? ThemeAccentHelper.SystemKey;

    [RelayCommand]
    private void ResetAccentColor()
    {
        SelectedAccentColor = ThemeAccentHelper.SystemKey;
        _main.Notify("主题颜色已恢复跟随系统默认", InfoBarSeverity.Informational);
    }

    public void ApplyCustomAccentColor(Color color)
    {
        SelectedAccentColor = $"#{color.R:X2}{color.G:X2}{color.B:X2}";
        _main.Notify("自定义主题颜色已应用", InfoBarSeverity.Success);
    }

    private void RefreshAccentProperties(bool forceRebuildPresets = false)
    {
        OnPropertyChanged(nameof(IsCustomAccentActive));
        OnPropertyChanged(nameof(ActiveAccentDisplayText));
        OnPropertyChanged(nameof(ActiveAccentPreviewBrush));
        RebuildAccentPresets(forceRebuildPresets);
    }

    private void RebuildAccentPresets(bool forceRebuild = false)
    {
        if (!forceRebuild && AccentPresets.Count > 0)
        {
            foreach (var item in AccentPresets)
            {
                item.IsSelected = string.Equals(SelectedAccentColor, item.Key, StringComparison.OrdinalIgnoreCase);
            }
            return;
        }

        AccentPresets.Clear();
        foreach (var preset in ThemeAccentHelper.Presets)
        {
            if (preset.Key == ThemeAccentHelper.SystemKey) continue;
            if (!ThemeAccentHelper.TryParseHexRgb(preset.PreviewHex, out var rgb)) continue;
            var useDarkCheck = ThemeAccentHelper.ShouldUseDarkForeground(rgb);
            AccentPresets.Add(new ThemeColorSwatchItem
            {
                Key = preset.Key,
                ToolTipText = $"{_services.Loc.T(preset.NameKey)} ({preset.Key})",
                SwatchBrush = new SolidColorBrush(Color.FromArgb(255, rgb.R, rgb.G, rgb.B)),
                CheckBrush = new SolidColorBrush(useDarkCheck ? Colors.Black : Colors.White),
                IsSelected = string.Equals(SelectedAccentColor, preset.Key, StringComparison.OrdinalIgnoreCase)
            });
        }
    }

    public string ServiceBaseUrl { get => _serviceBaseUrl; set => SetProperty(ref _serviceBaseUrl, value); }
    public bool AutoStartBackend { get => _autoStartBackend; set => SetProperty(ref _autoStartBackend, value); }
    public string DownloadDir { get => _downloadDir; set => SetProperty(ref _downloadDir, value); }
    public bool FollowDefaultDownloadDir
    {
        get => _followDefaultDownloadDir;
        set
        {
            if (SetProperty(ref _followDefaultDownloadDir, value))
            {
                OnPropertyChanged(nameof(UseCustomMediaDirs));
                _services.LocalState.Current.FollowDefaultDownloadDir = value;
                _services.LocalState.Save();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { FollowDefaultDir = value });
                }
            }
        }
    }
    public bool UseCustomMediaDirs => !FollowDefaultDownloadDir;
    public string VideoDownloadDir { get => _videoDownloadDir; set => SetProperty(ref _videoDownloadDir, value); }
    public string ImageDownloadDir { get => _imageDownloadDir; set => SetProperty(ref _imageDownloadDir, value); }
    public string AudioDownloadDir { get => _audioDownloadDir; set => SetProperty(ref _audioDownloadDir, value); }

    public bool ShowRecentTasksOnHome
    {
        get => _showRecentTasksOnHome;
        set
        {
            if (SetProperty(ref _showRecentTasksOnHome, value))
            {
                _services.LocalState.Current.ShowRecentTasksOnHome = value;
                _services.LocalState.Save();
                _main.HomeVm.RefreshRecentTasksVisibility();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { ShowRecentTasksOnHome = value });
                }
            }
        }
    }

    public bool EnableWebService
    {
        get => _enableWebService;
        set
        {
            if (SetProperty(ref _enableWebService, value))
            {
                _services.LocalState.Current.EnableWebService = value;
                _services.LocalState.Save();
                _main.NotifyWebServiceModeChanged();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { EnableWebService = value });
                }
            }
        }
    }

    public string CloseBehaviorMode
    {
        get
        {
            var st = _services.LocalState.Current;
            if (!st.AlwaysRememberCloseBehavior) return "ask";
            return st.SavedCloseBehavior is "tray" or "exit" ? st.SavedCloseBehavior : "ask";
        }
        set
        {
            var st = _services.LocalState.Current;
            if (value == "tray" || value == "exit")
            {
                st.AlwaysRememberCloseBehavior = true;
                st.SavedCloseBehavior = value;
            }
            else
            {
                st.AlwaysRememberCloseBehavior = false;
                st.SavedCloseBehavior = string.Empty;
            }
            _services.LocalState.Save();
            OnPropertyChanged(nameof(CloseBehaviorMode));
        }
    }

    public int MaxConcurrentTasks { get => _maxConcurrentTasks; set => SetProperty(ref _maxConcurrentTasks, value); }
    public bool AutoStartWithWindows { get => _autoStartWithWindows; set => SetProperty(ref _autoStartWithWindows, value); }
    public string ProxyUrl
    {
        get => _proxyUrl;
        set { if (SetProperty(ref _proxyUrl, value)) OnPropertyChanged(nameof(HasProxy)); }
    }
    public string SelectedSessionPlatform
    {
        get => _selectedSessionPlatform;
        set
        {
            if (SetProperty(ref _selectedSessionPlatform, value))
            {
                StopLoginStatusPolling();
                _ = LoadSelectedSessionPolicyAsync();
            }
        }
    }
    public bool IsSelectedPlatformCookieDisabled { get => _isSelectedPlatformCookieDisabled; set => SetProperty(ref _isSelectedPlatformCookieDisabled, value); }
    public string SessionStatusBadge { get => _sessionStatusBadge; set => SetProperty(ref _sessionStatusBadge, value); }
    public string SessionPolicySummary { get => _sessionPolicySummary; set => SetProperty(ref _sessionPolicySummary, value); }
    public string BrowserLoginStatusText { get => _browserLoginStatusText; set => SetProperty(ref _browserLoginStatusText, value); }
    public bool IsBrowserLoginBusy { get => _isBrowserLoginBusy; set => SetProperty(ref _isBrowserLoginBusy, value); }
    public bool ShowOutdatedBackendWarning => _main.IsBackendConnected && _services.BackendManager.IsOutdatedBackend;
    public string OutdatedBackendWarningText => _services.BackendManager.IsOutdatedBackend
        ? _services.Loc.T(
            "当前连接的后端服务版本 (v%s) 低于 1.7.63，尚未包含普通浏览器登录修复。请退出旧版 Web 托盘服务，并点击下方按钮重连最新版核心引擎。",
            string.IsNullOrWhiteSpace(_services.BackendManager.ConnectedVersion) ? _services.Loc.T("未知") : _services.BackendManager.ConnectedVersion)
        : string.Empty;
    public string BilibiliCookieInput { get => _bilibiliCookieInput; set => SetProperty(ref _bilibiliCookieInput, value); }
    public bool HasBilibiliSession { get => _hasBilibiliSession; set => SetProperty(ref _hasBilibiliSession, value); }

    // 中转站
    public ObservableCollection<RelaySite> RelaySites { get; } = new();

    // 组件管理（必要核心组件 + 可选扩展组件 + 预留自定义接入组件）
    public ObservableCollection<ManagedComponentItem> ManagedComponents { get; } = new();
    public string ComponentSummaryText => _services.Loc.T(
        "共 %d 项组件 · %d 项必要 · 已启用 %d 项",
        ManagedComponents.Count,
        ManagedComponents.Count(c => c.IsRequired),
        ManagedComponents.Count(c => c.IsEnabled));

    public bool GpuAccelerationEnabled
    {
        get => _gpuAccelerationEnabled;
        set
        {
            if (SetProperty(ref _gpuAccelerationEnabled, value))
            {
                _services.LocalState.Current.GpuAccelerationEnabled = value;
                _services.LocalState.Save();
                if (!_suppressThemeSync)
                {
                    ApplyGpuStatusTexts(_lastGpuStatus);
                    if (_main.IsBackendConnected)
                    {
                        _ = SaveGpuSettingsAsync();
                    }
                }
            }
        }
    }

    public string GpuPreferredVendor
    {
        get => _gpuPreferredVendor;
        set
        {
            var normalized = string.IsNullOrWhiteSpace(value) ? "auto" : value.Trim().ToLowerInvariant();
            if (SetProperty(ref _gpuPreferredVendor, normalized))
            {
                _services.LocalState.Current.GpuPreferredVendor = normalized;
                _services.LocalState.Save();
                if (!_suppressThemeSync && _main.IsBackendConnected)
                {
                    _ = SaveGpuSettingsAsync();
                }
            }
        }
    }

    public string GpuStatusSummary { get => _gpuStatusSummary; set => SetProperty(ref _gpuStatusSummary, value); }
    public string GpuStatusBadge { get => _gpuStatusBadge; set => SetProperty(ref _gpuStatusBadge, value); }

    public LocalizationService Loc => _services.Loc;
    public string EngineStatusSummary { get => _engineStatusSummary; set => SetProperty(ref _engineStatusSummary, value); }
    public string EngineReadyBadge { get => _engineReadyBadge; set => SetProperty(ref _engineReadyBadge, value); }
    public bool IsEngineReady { get => _isEngineReady; set => SetProperty(ref _isEngineReady, value); }
    public string UpdateStatusText { get => _updateStatusText; set => SetProperty(ref _updateStatusText, value); }
    public string UpdateDownloadUrl
    {
        get => _updateDownloadUrl;
        set { if (SetProperty(ref _updateDownloadUrl, value)) OnPropertyChanged(nameof(HasUpdateUrl)); }
    }

    public bool IsRootPage => CurrentCategory == "root";
    public bool HasProxy => !string.IsNullOrWhiteSpace(ProxyUrl);
    public bool HasRelaySites => RelaySites.Count > 0;
    public bool HasUpdateUrl => !string.IsNullOrWhiteSpace(UpdateDownloadUrl);
    public string RelaySectionTitle => _services.Loc.T("第三方中转网站 (%d)", RelaySites.Count);

    public event EventHandler? AddRelaySiteDialogRequested;
    public event EventHandler? BilibiliGuideDialogRequested;
    public event EventHandler? BilibiliLoginWindowRequested;
    public event EventHandler? PlatformCatalogDialogRequested;
    public event EventHandler? AddCustomComponentDialogRequested;

    public SettingsViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        _selectedLanguage = services.LocalState.Current.Language;
        _selectedTheme = services.LocalState.Current.Theme;
        _selectedBackdropStyle = BackdropStyleLogic.NormalizeStyle(services.LocalState.Current.BackdropStyle);
        _selectedAccentColor = ThemeAccentHelper.NormalizeSetting(services.LocalState.Current.AccentColor);
        _usePureBlackDark = services.LocalState.Current.UsePureBlackDarkMode;
        _showRecentTasksOnHome = services.LocalState.Current.ShowRecentTasksOnHome;
        _enableWebService = services.LocalState.Current.EnableWebService;
        _followDefaultDownloadDir = services.LocalState.Current.FollowDefaultDownloadDir;
        _videoDownloadDir = services.LocalState.Current.VideoDownloadDir;
        _imageDownloadDir = services.LocalState.Current.ImageDownloadDir;
        _audioDownloadDir = services.LocalState.Current.AudioDownloadDir;
        _gpuAccelerationEnabled = services.LocalState.Current.GpuAccelerationEnabled;
        _gpuPreferredVendor = string.IsNullOrWhiteSpace(services.LocalState.Current.GpuPreferredVendor) ? "auto" : services.LocalState.Current.GpuPreferredVendor;
        _serviceBaseUrl = services.LocalState.Current.ServiceBaseUrl;
        _autoStartBackend = services.LocalState.Current.AutoStartBackend;
        _sessionStatusBadge = _services.Loc.T("未导入");
        _browserLoginStatusText = _services.Loc.T("使用独立 Edge 窗口登录。Google 登录后请返回目标平台，再点击「保存会话并关闭」；已保存不等于账号已验证。");
        _engineStatusSummary = _services.Loc.T("点击下方按钮探测运行环境");
        _engineReadyBadge = _services.Loc.T("未探测");
        ApplyGpuStatusTexts(null);
        RebuildAccentPresets();
        RebuildSessionPlatformOptions();
        RebuildManagedComponents(direct: true);

        _services.Loc.LanguageChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(RelaySectionTitle));
            OnPropertyChanged(nameof(OutdatedBackendWarningText));
            OnPropertyChanged(nameof(BackdropStyleDescription));
            RebuildSessionPlatformOptions();
            RefreshAccentProperties(forceRebuildPresets: true);
            RebuildManagedComponents();
            if (!_isBrowserLoginBusy)
            {
                BrowserLoginStatusText = _services.Loc.T("使用独立 Edge 窗口登录。Google 登录后请返回目标平台，再点击「保存会话并关闭」；已保存不等于账号已验证。");
            }
            _ = LoadSelectedSessionPolicyAsync();
            if (_lastDiagnostics is not null)
            {
                ApplyDiagnosticsTexts(_lastDiagnostics);
            }
            else if (_main.IsBackendConnected && CurrentCategory == "engine")
            {
                _ = CheckEngineDiagnosticsAsync();
            }
            else
            {
                EngineStatusSummary = _services.Loc.T("点击下方按钮探测运行环境");
                EngineReadyBadge = _services.Loc.T("未探测");
            }
            ApplyGpuStatusTexts(_lastGpuStatus);
        };
    }

    private void ApplyGpuStatusTexts(GpuStatusInfo? gpu)
    {
        if (!_gpuAccelerationEnabled)
        {
            GpuStatusBadge = _services.Loc.T("已关闭");
            GpuStatusSummary = _services.Loc.T("当前已关闭 GPU 硬件加速，转码与图形渲染均采用软件模式");
            return;
        }
        if (gpu is null)
        {
            GpuStatusBadge = _services.Loc.T("待探测");
            GpuStatusSummary = _services.Loc.T("已开启 GPU 硬件加速，等待核心引擎反馈真实硬件状态");
            return;
        }
        if (string.Equals(gpu.StatusCode, "DISABLED", StringComparison.OrdinalIgnoreCase))
        {
            GpuStatusBadge = _services.Loc.T("已关闭");
            GpuStatusSummary = _services.Loc.T("当前已关闭 GPU 硬件加速，转码与图形渲染均采用软件模式");
            return;
        }
        if (gpu.FallbackActive || !gpu.HardwareAvailable || string.Equals(gpu.StatusCode, "FALLBACK_SOFTWARE", StringComparison.OrdinalIgnoreCase))
        {
            GpuStatusBadge = _services.Loc.T("已回退软件处理");
            GpuStatusSummary = _services.Loc.T("未检测到可用硬件编码器或驱动初始化失败，已自动回退软件处理 (%s)", string.IsNullOrWhiteSpace(gpu.ActiveEncoder) ? "libx264" : gpu.ActiveEncoder);
            return;
        }

        var backendLabel = string.IsNullOrWhiteSpace(gpu.ActiveBackend) ? "GPU" : gpu.ActiveBackend.ToUpperInvariant();
        var adapter = string.IsNullOrWhiteSpace(gpu.ActiveAdapterName) ? gpu.ActiveVendor.ToUpperInvariant() : gpu.ActiveAdapterName;
        var decodeNote = string.IsNullOrWhiteSpace(gpu.ActiveDecodeHwaccel) ? _services.Loc.T("软件解码") : gpu.ActiveDecodeHwaccel.ToUpperInvariant();
        GpuStatusBadge = _services.Loc.T("已启用 · %s", backendLabel);
        GpuStatusSummary = _services.Loc.T("显卡：%s · 编码器 %s · 解码 %s · 无损直传保持开启", adapter, gpu.ActiveEncoder, decodeNote);
    }

    public async Task SaveGpuSettingsAsync()
    {
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest
            {
                GpuAccelerationEnabled = GpuAccelerationEnabled,
                GpuPreferredVendor = GpuPreferredVendor
            });
            var gpu = await _services.ApiClient.GetGpuStatusAsync(refresh: false);
            _lastGpuStatus = gpu;
            _main.RunOnUi(() => ApplyGpuStatusTexts(gpu));
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    private void RebuildSessionPlatformOptions()
    {
        SessionPlatformOptions.Clear();
        foreach (var key in SupportedSessionPlatforms)
        {
            SessionPlatformOptions.Add(new SessionPlatformOption
            {
                Key = key,
                DisplayName = _services.Loc.T(key)
            });
        }
        OnPropertyChanged(nameof(SessionPlatformOptions));
    }

    private void ApplyDiagnosticsTexts(EngineDiagnostics d)
    {
        EngineStatusSummary = _services.Loc.T(
            "Python %s · yt-dlp %s · EJS %s · FFmpeg %s · JS 运行时 %s",
            d.Python,
            d.YtDlp,
            d.Ejs,
            _services.Loc.T(d.FfmpegAvailable ? "就绪" : "未就绪"),
            _services.Loc.T(d.DenoAvailable ? "可用" : "缺失"));
        IsEngineReady = !string.IsNullOrWhiteSpace(d.YtDlp);
        EngineReadyBadge = IsEngineReady ? _services.Loc.T("正常运行") : _services.Loc.T("未就绪");
    }

    public void Shutdown()
    {
        StopLoginStatusPolling();
    }

    public void OpenCategory(string category) => CurrentCategory = category;

    public void GoBackToRoot() => CurrentCategory = "root";

    /// <summary>
    /// 启动时轻量读取设置与中转站列表，避免在冷启动同步拉起诊断导入
    /// </summary>
    public async Task LoadInitialAsync()
    {
        try
        {
            var s = await _services.ApiClient.GetSettingsAsync().ConfigureAwait(false);
            _main.RunOnUi(() =>
            {
                _suppressThemeSync = true;
                try
                {
                    if (!string.IsNullOrWhiteSpace(s.Theme) &&
                        !string.Equals(_selectedTheme, s.Theme, StringComparison.OrdinalIgnoreCase))
                    {
                        SelectedTheme = s.Theme;
                    }
                    var remoteAccent = ThemeAccentHelper.NormalizeSetting(s.AccentColor);
                    if (_selectedAccentColor == ThemeAccentHelper.SystemKey && remoteAccent != ThemeAccentHelper.SystemKey)
                    {
                        SelectedAccentColor = remoteAccent;
                    }
                    else if (_selectedAccentColor != ThemeAccentHelper.SystemKey && remoteAccent != _selectedAccentColor)
                    {
                        _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { AccentColor = _selectedAccentColor });
                    }
                    var local = _services.LocalState.Current;
                    var customDefaultPending = local.HasCompletedOnboarding && !string.IsNullOrWhiteSpace(DownloadDir) && !string.Equals(DownloadDir, s.DownloadDir, StringComparison.OrdinalIgnoreCase);
                    if (!customDefaultPending)
                    {
                        DownloadDir = s.DownloadDir;
                    }
                    if (!local.HasCompletedOnboarding)
                    {
                        FollowDefaultDownloadDir = s.FollowDefaultDir;
                        if (!string.IsNullOrWhiteSpace(s.VideoDownloadDir)) VideoDownloadDir = s.VideoDownloadDir;
                        if (!string.IsNullOrWhiteSpace(s.ImageDownloadDir)) ImageDownloadDir = s.ImageDownloadDir;
                        if (!string.IsNullOrWhiteSpace(s.AudioDownloadDir)) AudioDownloadDir = s.AudioDownloadDir;
                    }
                    else
                    {
                        // 已完成引导后以客户端显式偏好同步后端，保证配置一致
                        if (customDefaultPending ||
                            s.ShowRecentTasksOnHome != local.ShowRecentTasksOnHome ||
                            s.EnableWebService != local.EnableWebService ||
                            s.FollowDefaultDir != local.FollowDefaultDownloadDir ||
                            !string.Equals(s.VideoDownloadDir, local.VideoDownloadDir, StringComparison.OrdinalIgnoreCase) ||
                            !string.Equals(s.ImageDownloadDir, local.ImageDownloadDir, StringComparison.OrdinalIgnoreCase) ||
                            !string.Equals(s.AudioDownloadDir, local.AudioDownloadDir, StringComparison.OrdinalIgnoreCase))
                        {
                            _ = SyncExtendedDirectoriesToBackendAsync();
                        }
                    }
                    MaxConcurrentTasks = Math.Clamp(s.MaxConcurrentTasks, 1, 10);
                    AutoStartWithWindows = s.AutoStart;
                    ProxyUrl = s.ProxyUrl;
                    HasBilibiliSession = s.HasBilibiliSession;
                    if (!local.HasCompletedOnboarding)
                    {
                        GpuAccelerationEnabled = s.GpuAccelerationEnabled;
                        GpuPreferredVendor = string.IsNullOrWhiteSpace(s.GpuPreferredVendor) ? "auto" : s.GpuPreferredVendor;
                    }
                    else if (s.GpuAccelerationEnabled != local.GpuAccelerationEnabled ||
                             !string.Equals(s.GpuPreferredVendor, local.GpuPreferredVendor, StringComparison.OrdinalIgnoreCase))
                    {
                        _ = _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest
                        {
                            GpuAccelerationEnabled = local.GpuAccelerationEnabled,
                            GpuPreferredVendor = string.IsNullOrWhiteSpace(local.GpuPreferredVendor) ? "auto" : local.GpuPreferredVendor
                        });
                    }
                    if (s.GpuStatus is not null)
                    {
                        _lastGpuStatus = s.GpuStatus;
                        ApplyGpuStatusTexts(s.GpuStatus);
                    }
                }
                finally
                {
                    _suppressThemeSync = false;
                }
            });
            await RefreshRelaySitesAsync().ConfigureAwait(false);
        }
        catch
        {
        }
    }

    public async Task LoadAllAsync()
    {
        await LoadInitialAsync().ConfigureAwait(false);
        await LoadSelectedSessionPolicyAsync().ConfigureAwait(false);
        await CheckEngineDiagnosticsAsync().ConfigureAwait(false);
    }

    public async Task RefreshRelaySitesAsync()
    {
        try
        {
            var resp = await _services.ApiClient.GetRelaySitesAsync();
            _main.RunOnUi(() =>
            {
                RelaySites.Clear();
                foreach (var site in resp.RelaySites) RelaySites.Add(site);
                OnPropertyChanged(nameof(HasRelaySites));
                OnPropertyChanged(nameof(RelaySectionTitle));
                _main.HomeVm.UpdateRelaySites(resp.RelaySites);
            });
        }
        catch
        {
        }
    }

    [RelayCommand]
    private async Task SaveBackendConnectionAsync()
    {
        _services.LocalState.Current.ServiceBaseUrl = ServiceBaseUrl.Trim();
        _services.LocalState.Current.AutoStartBackend = AutoStartBackend;
        _services.LocalState.Save();
        RebuildManagedComponents();
        await _main.ReconnectBackendAsync();
    }

    [RelayCommand]
    private async Task BrowseDownloadDirAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        var folder = await NativeShellHelper.PickFolderAsync(owner);
        if (!string.IsNullOrWhiteSpace(folder))
        {
            DownloadDir = folder;
            await SaveDownloadDirAsync();
        }
    }

    [RelayCommand]
    private async Task SaveDownloadDirAsync()
    {
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { DownloadDir = DownloadDir.Trim() });
            _main.Notify("保存路径已更新", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task BrowseVideoDirAsync() => await PickAndSaveCategoryDirAsync(v => VideoDownloadDir = v);

    [RelayCommand]
    private async Task BrowseImageDirAsync() => await PickAndSaveCategoryDirAsync(v => ImageDownloadDir = v);

    [RelayCommand]
    private async Task BrowseAudioDirAsync() => await PickAndSaveCategoryDirAsync(v => AudioDownloadDir = v);

    private async Task PickAndSaveCategoryDirAsync(Action<string> setter)
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        var folder = await NativeShellHelper.PickFolderAsync(owner);
        if (!string.IsNullOrWhiteSpace(folder))
        {
            setter(folder);
            await SaveCategoryDirsAsync();
        }
    }

    [RelayCommand]
    private async Task SaveCategoryDirsAsync()
    {
        var st = _services.LocalState.Current;
        st.FollowDefaultDownloadDir = FollowDefaultDownloadDir;
        st.VideoDownloadDir = VideoDownloadDir.Trim();
        st.ImageDownloadDir = ImageDownloadDir.Trim();
        st.AudioDownloadDir = AudioDownloadDir.Trim();
        _services.LocalState.Save();

        try
        {
            if (_main.IsBackendConnected)
            {
                await SyncExtendedDirectoriesToBackendAsync();
            }
            _main.Notify("分类保存目录已更新", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public Task SyncExtendedDirectoriesToBackendAsync() =>
        _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest
        {
            DownloadDir = string.IsNullOrWhiteSpace(DownloadDir) ? null : DownloadDir.Trim(),
            FollowDefaultDir = _services.LocalState.Current.FollowDefaultDownloadDir,
            VideoDownloadDir = _services.LocalState.Current.VideoDownloadDir.Trim(),
            ImageDownloadDir = _services.LocalState.Current.ImageDownloadDir.Trim(),
            AudioDownloadDir = _services.LocalState.Current.AudioDownloadDir.Trim(),
            ShowRecentTasksOnHome = _services.LocalState.Current.ShowRecentTasksOnHome,
            EnableWebService = _services.LocalState.Current.EnableWebService
        });

    [RelayCommand]
    private void OpenOnboarding() => _main.RequestOnboarding();

    public void RefreshFromLocalState()
    {
        _suppressThemeSync = true;
        try
        {
            SelectedLanguage = _services.LocalState.Current.Language;
            SelectedTheme = _services.LocalState.Current.Theme;
            SelectedAccentColor = _services.LocalState.Current.AccentColor;
            UsePureBlackDark = _services.LocalState.Current.UsePureBlackDarkMode;
            ShowRecentTasksOnHome = _services.LocalState.Current.ShowRecentTasksOnHome;
            EnableWebService = _services.LocalState.Current.EnableWebService;
            FollowDefaultDownloadDir = _services.LocalState.Current.FollowDefaultDownloadDir;
            VideoDownloadDir = _services.LocalState.Current.VideoDownloadDir;
            ImageDownloadDir = _services.LocalState.Current.ImageDownloadDir;
            AudioDownloadDir = _services.LocalState.Current.AudioDownloadDir;
            GpuAccelerationEnabled = _services.LocalState.Current.GpuAccelerationEnabled;
            GpuPreferredVendor = string.IsNullOrWhiteSpace(_services.LocalState.Current.GpuPreferredVendor) ? "auto" : _services.LocalState.Current.GpuPreferredVendor;
            ApplyGpuStatusTexts(_lastGpuStatus);
            OnPropertyChanged(nameof(CloseBehaviorMode));
        }
        finally
        {
            _suppressThemeSync = false;
        }
    }

    [RelayCommand]
    private async Task OpenDownloadFolderAsync()
    {
        try
        {
            await _services.ApiClient.OpenDownloadFolderAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task SaveMaxConcurrentTasksAsync(int value)
    {
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { MaxConcurrentTasks = value });
            _main.Notify("并发数已更新", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task SaveAutoStartAsync(bool enabled)
    {
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { AutoStart = enabled });
            var actual = await _services.ApiClient.GetSettingsAsync();
            AutoStartWithWindows = actual.AutoStart;
            if (actual.AutoStart != enabled) throw new InvalidOperationException("网页服务开机启动状态未能保存，请检查权限后重试。");
            _main.Notify(enabled ? "已开启网页服务开机启动" : "已关闭网页服务开机启动", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            AutoStartWithWindows = !enabled;
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private void OpenWebCompanion()
    {
        var url = _services.ApiClient.BaseUrl;
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https")
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
    }

    [RelayCommand]
    private async Task SaveProxyAsync()
    {
        try
        {
            var trimmed = ProxyUrl.Trim();
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { ProxyUrl = trimmed });
            _main.Notify(string.IsNullOrEmpty(trimmed) ? "已切换为直连网络" : "代理已更新", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task ClearProxyAsync()
    {
        ProxyUrl = string.Empty;
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { ProxyUrl = string.Empty });
            _main.Notify("代理已清空", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    // === 中转站 ===

    [RelayCommand]
    private void RequestAddRelaySite() => AddRelaySiteDialogRequested?.Invoke(this, EventArgs.Empty);

    [RelayCommand]
    private async Task DeleteRelaySiteAsync(RelaySite? site)
    {
        if (site is null) return;
        try
        {
            await _services.ApiClient.DeleteRelaySiteAsync(site.Id);
            await RefreshRelaySitesAsync();
            _main.Notify("已移除中转站", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private Task OpenRelaySiteAsync(RelaySite? site) =>
        site is null ? Task.CompletedTask : _main.HomeVm.OpenRelaySiteWithConfirmAsync(site);

    // === 会话与凭证 ===

    [RelayCommand]
    private async Task ReconnectForLoginAsync()
    {
        await _main.ReconnectBackendAsync();
        OnPropertyChanged(nameof(ShowOutdatedBackendWarning));
        OnPropertyChanged(nameof(OutdatedBackendWarningText));
        await LoadSelectedSessionPolicyAsync();
    }

    [RelayCommand]
    private async Task OpenBrowserLoginAsync()
    {
        if (string.IsNullOrWhiteSpace(SelectedSessionPlatform)) return;
        if (IsBrowserLoginBusy) return;
        IsBrowserLoginBusy = true;
        try
        {
            var status = await _services.ApiClient.OpenBrowserLoginAsync(SelectedSessionPlatform);
            UpdateBrowserLoginStatusText(status);
            if (status.Phase == "ERROR")
            {
                StopLoginStatusPolling();
                var errMsg = !string.IsNullOrWhiteSpace(status.ErrorMessage)
                    ? status.ErrorMessage
                    : BrowserLoginStatusText;
                _main.Notify(errMsg, InfoBarSeverity.Error);
                return;
            }
            if (status.Running)
            {
                StartLoginStatusPolling(SelectedSessionPlatform);
            }
            _main.Notify("正在打开普通 Edge 登录窗口。Google 登录后请返回目标平台，再点击保存会话并关闭。", InfoBarSeverity.Informational);
        }
        catch (OmniApiException ex) when (ex.StatusCode == 404)
        {
            var hint = _services.Loc.T("当前连接的本地后台服务未提供浏览器登录接口（可能仍在运行旧版 1.7.51 服务）。请退出旧版托盘服务或升级后点击顶部刷新重连。");
            BrowserLoginStatusText = hint;
            OnPropertyChanged(nameof(ShowOutdatedBackendWarning));
            OnPropertyChanged(nameof(OutdatedBackendWarningText));
            _main.Notify(hint, InfoBarSeverity.Error);
        }
        catch (Exception ex)
        {
            BrowserLoginStatusText = ex.Message;
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            IsBrowserLoginBusy = false;
        }
    }

    [RelayCommand]
    private async Task SaveBrowserLoginAsync()
    {
        if (string.IsNullOrWhiteSpace(SelectedSessionPlatform)) return;
        if (IsBrowserLoginBusy) return;
        IsBrowserLoginBusy = true;
        try
        {
            var result = await _services.ApiClient.SaveBrowserLoginAsync(SelectedSessionPlatform);
            UpdateBrowserLoginStatusText(result);
            _main.Notify("已保存 %d 项 Cookie 并关闭登录窗口；请重新解析验证账号。", InfoBarSeverity.Success, result.CookieCount);
            StopLoginStatusPolling();
            await LoadSelectedSessionPolicyAsync();
            if (SelectedSessionPlatform == "哔哩哔哩")
            {
                await LoadInitialAsync();
            }
        }
        catch (OmniApiException ex) when (ex.StatusCode == 404)
        {
            var hint = _services.Loc.T("当前连接的本地后台服务未提供会话捕获接口（版本低于 1.7.63）。请退出旧版服务并重连最新版核心。");
            BrowserLoginStatusText = hint;
            _main.Notify(hint, InfoBarSeverity.Error);
        }
        catch (Exception ex)
        {
            BrowserLoginStatusText = ex.Message;
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            IsBrowserLoginBusy = false;
        }
    }

    private void StartLoginStatusPolling(string platform)
    {
        StopLoginStatusPolling();
        var cts = new CancellationTokenSource();
        _loginPollCts = cts;
        _ = PollBrowserLoginLoopAsync(platform, cts.Token);
    }

    private void StopLoginStatusPolling()
    {
        _loginPollCts?.Cancel();
        _loginPollCts?.Dispose();
        _loginPollCts = null;
    }

    private async Task PollBrowserLoginLoopAsync(string platform, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(1800, ct);
                if (ct.IsCancellationRequested || SelectedSessionPlatform != platform) break;
                var st = await _services.ApiClient.GetBrowserLoginStatusAsync(platform, ct);
                _main.RunOnUi(() => UpdateBrowserLoginStatusText(st));
                if (st.Phase is "CAPTURED" or "CLOSED" or "ERROR")
                {
                    await LoadSelectedSessionPolicyAsync();
                    if (!st.Running)
                    {
                        if (st.Phase == "ERROR")
                        {
                            var errMsg = !string.IsNullOrWhiteSpace(st.ErrorMessage)
                                ? st.ErrorMessage
                                : _services.Loc.T("登录捕获未完成（%s），原有凭据已保留。", string.IsNullOrWhiteSpace(st.ErrorCode) ? "CAPTURE_FAILED" : st.ErrorCode);
                            _main.RunOnUi(() => _main.Notify(errMsg, InfoBarSeverity.Error));
                        }
                        break;
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch
        {
        }
    }

    private void UpdateBrowserLoginStatusText(BrowserLoginStatus st)
    {
        var loc = _services.Loc;
        var baseText = st.Phase switch
        {
            "OPENING" => loc.T("正在打开普通 Edge 登录窗口…"),
            "WAITING_LOGIN" => loc.T("请在普通 Edge 窗口中手动登录；此阶段不启用自动化。Google 登录后返回目标平台，再点击「保存会话并关闭」。"),
            "AWAITING_CAPTURE" => loc.T("窗口已关闭，仍可点击保存读取已落盘的会话。"),
            "CAPTURING" => loc.T("正在正常关闭专用窗口并保存会话，请勿同时重开。"),
            "CAPTURED" => loc.T("已保存目标平台 Cookie（%d 项）；未验证账号权益，请返回重新解析原链接。", st.CookieCount),
            "ERROR" => !string.IsNullOrWhiteSpace(st.ErrorMessage)
                ? st.ErrorMessage
                : loc.T("登录捕获未完成（%s），原有凭据已保留。", string.IsNullOrWhiteSpace(st.ErrorCode) ? "CAPTURE_FAILED" : st.ErrorCode),
            _ => loc.T("使用独立 Edge 窗口登录。Google 登录后请返回目标平台，再点击「保存会话并关闭」；已保存不等于账号已验证。")
        };
        if (st.SkippedPartitionedCookies > 0)
        {
            baseText += " " + loc.T("已跳过 %d 项分区 Cookie。", st.SkippedPartitionedCookies);
        }
        BrowserLoginStatusText = baseText;
    }

    public async Task LoadSelectedSessionPolicyAsync()
    {
        if (!_main.IsBackendConnected) return;
        OnPropertyChanged(nameof(ShowOutdatedBackendWarning));
        OnPropertyChanged(nameof(OutdatedBackendWarningText));
        try
        {
            var all = await _services.ApiClient.GetSessionsAsync();
            var info = all.FirstOrDefault(s => s.Platform == SelectedSessionPlatform);
            var enabled = info is null || info.Enabled;
            var st = info?.State ?? "MISSING";

            _main.RunOnUi(() =>
            {
                IsSelectedPlatformCookieDisabled = !enabled;
                SessionStatusBadge = !enabled
                    ? _services.Loc.T("已停用（保留原凭证）")
                    : st switch
                    {
                        "IMPORTED" => info is { CookieCount: > 0 }
                            ? _services.Loc.T("已保存（未验证账号 · %d 项）", info.CookieCount)
                            : _services.Loc.T("已保存（未验证账号）"),
                        "EXPIRED" => _services.Loc.T("凭据已过期，请重新登录"),
                        "CORRUPT" => _services.Loc.T("凭据无法读取，请重新导入"),
                        "NEEDS_AUTH" => _services.Loc.T("需重新验证"),
                        _ => _services.Loc.T("未导入")
                    };
                SessionPolicySummary = enabled
                    ? _services.Loc.T("关闭停用开关时，新解析与新下载可使用该平台已保存的 Cookie")
                    : _services.Loc.T("已停用：新解析与新下载使用匿名请求，已保存会话仍保留");
            });
        }
        catch
        {
        }
    }

    public async Task ToggleSessionCookieDisabledAsync(bool disabled)
    {
        var enabled = !disabled;
        try
        {
            await _services.ApiClient.SetSessionEnabledAsync(SelectedSessionPlatform, enabled);
            _main.Notify(enabled ? "Cookie 已启用，请重新解析" : "Cookie 已停用，会话保留", InfoBarSeverity.Informational);
            await LoadSelectedSessionPolicyAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task ImportSessionCookiesFileAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        try
        {
            var text = await NativeShellHelper.PickTextFileContentAsync(owner, 2 * 1024 * 1024, ".txt");
            if (text is null) return;
            await _services.ApiClient.ImportSessionAsync(SelectedSessionPlatform, text);
            _main.Notify("会话已导入，请重新解析原链接", InfoBarSeverity.Success);
            await LoadSelectedSessionPolicyAsync();
            if (SelectedSessionPlatform == "哔哩哔哩")
            {
                await LoadAllAsync();
            }
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task ClearSelectedSessionAsync()
    {
        var ok = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("清除会话"),
            _services.Loc.T("确定清除「%s」的 Cookie 和独立登录浏览器状态吗？操作后需要重新登录。", SelectedSessionPlatform),
            _services.Loc.T("清除"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!ok) return;

        try
        {
            StopLoginStatusPolling();
            await _services.ApiClient.ClearSessionAsync(SelectedSessionPlatform);
            BrowserLoginStatusText = _services.Loc.T("会话及受控登录浏览器状态已清除，可重新打开登录窗口或导入 Cookie 文件。");
            _main.Notify("会话已清除", InfoBarSeverity.Informational);
            await LoadSelectedSessionPolicyAsync();
            if (SelectedSessionPlatform == "哔哩哔哩")
            {
                HasBilibiliSession = false;
            }
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task SaveBilibiliCookieAsync()
    {
        var cookie = BilibiliCookieInput.Trim();
        try
        {
            await _services.ApiClient.UpdateSettingsAsync(new UpdateSettingsRequest { BilibiliCookie = cookie });
            HasBilibiliSession = !string.IsNullOrEmpty(cookie);
            BilibiliCookieInput = string.Empty;
            _main.Notify(HasBilibiliSession ? "B站凭据已保存，请重新解析验证账号" : "B站凭证已清空", InfoBarSeverity.Success);
            await LoadSelectedSessionPolicyAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task ClearBilibiliCookieAsync()
    {
        BilibiliCookieInput = string.Empty;
        try
        {
            await _services.ApiClient.ClearSessionAsync("哔哩哔哩");
            HasBilibiliSession = false;
            _main.Notify("B站凭证已清空", InfoBarSeverity.Informational);
            await LoadSelectedSessionPolicyAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private void ShowBilibiliGuide() => BilibiliGuideDialogRequested?.Invoke(this, EventArgs.Empty);

    [RelayCommand]
    private void OpenBilibiliLoginWindow() => BilibiliLoginWindowRequested?.Invoke(this, EventArgs.Empty);

    // === 组件管理与更新 ===

    public void RebuildManagedComponents(bool direct = false)
    {
        var items = ComponentCatalog.BuildItems(
            _services.LocalState.Current,
            _lastDiagnostics,
            _main.IsBackendConnected,
            ServiceBaseUrl,
            key => _services.Loc.T(key),
            (key, args) => _services.Loc.T(key, args));

        void UpdateCollection()
        {
            ManagedComponents.Clear();
            foreach (var item in items) ManagedComponents.Add(item);
            OnPropertyChanged(nameof(ComponentSummaryText));
        }

        if (direct || Application.Current is null)
        {
            UpdateCollection();
        }
        else
        {
            _main.RunOnUi(UpdateCollection);
        }
    }

    [RelayCommand]
    private void RequestAddCustomComponent() => AddCustomComponentDialogRequested?.Invoke(this, EventArgs.Empty);

    public void AddCustomComponent(string name, string description, string kind, string entryPoint)
    {
        var list = _services.LocalState.Current.CustomComponents ??= new List<CustomComponentEntry>();
        list.Add(new CustomComponentEntry
        {
            Id = "custom-" + Guid.NewGuid().ToString("N")[..8],
            Name = name.Trim(),
            Description = description.Trim(),
            Kind = string.IsNullOrWhiteSpace(kind) ? "extractor" : kind,
            EntryPoint = entryPoint.Trim(),
            Enabled = true
        });
        _services.LocalState.Save();
        RebuildManagedComponents();
        _main.Notify("已添加自定义组件（扩展接入已就绪）", InfoBarSeverity.Success);
    }

    public void ToggleComponentEnabled(string? componentId, bool enabled)
    {
        if (string.IsNullOrWhiteSpace(componentId)) return;
        var target = ManagedComponents.FirstOrDefault(c => string.Equals(c.Id, componentId, StringComparison.OrdinalIgnoreCase));
        if (target is { IsRequired: true })
        {
            RebuildManagedComponents();
            return;
        }

        var state = _services.LocalState.Current;
        var custom = (state.CustomComponents ?? new List<CustomComponentEntry>())
            .FirstOrDefault(c => string.Equals(c.Id, componentId, StringComparison.OrdinalIgnoreCase));
        if (custom is not null)
        {
            custom.Enabled = enabled;
        }
        else
        {
            var disabled = state.DisabledOptionalComponentIds ??= new List<string>();
            disabled.RemoveAll(x => string.Equals(x, componentId, StringComparison.OrdinalIgnoreCase));
            if (!enabled) disabled.Add(componentId);
        }
        _services.LocalState.Save();
        RebuildManagedComponents();
    }

    public void RemoveCustomComponent(string? componentId)
    {
        if (string.IsNullOrWhiteSpace(componentId)) return;
        var removed = (_services.LocalState.Current.CustomComponents ??= new List<CustomComponentEntry>())
            .RemoveAll(c => string.Equals(c.Id, componentId, StringComparison.OrdinalIgnoreCase));
        if (removed > 0)
        {
            _services.LocalState.Save();
            RebuildManagedComponents();
            _main.Notify("已移除自定义组件", InfoBarSeverity.Informational);
        }
    }

    [RelayCommand]
    public async Task RefreshGpuStatusAsync()
    {
        if (!_main.IsBackendConnected)
        {
            ApplyGpuStatusTexts(null);
            return;
        }
        try
        {
            var gpu = await _services.ApiClient.GetGpuStatusAsync(refresh: true);
            _lastGpuStatus = gpu;
            _main.RunOnUi(() => ApplyGpuStatusTexts(gpu));
        }
        catch
        {
            _main.RunOnUi(() => ApplyGpuStatusTexts(null));
        }
    }

    [RelayCommand]
    public async Task CheckEngineDiagnosticsAsync()
    {
        EngineStatusSummary = _services.Loc.T("正在探测本机引擎环境...");
        try
        {
            var d = await _services.ApiClient.GetDiagnosticsAsync();
            _lastDiagnostics = d;
            var gpu = d.Gpu ?? await _services.ApiClient.GetGpuStatusAsync(refresh: true);
            _lastGpuStatus = gpu;
            _main.RunOnUi(() =>
            {
                ApplyDiagnosticsTexts(d);
                ApplyGpuStatusTexts(gpu);
            });
            RebuildManagedComponents();
        }
        catch (Exception ex)
        {
            _lastDiagnostics = null;
            EngineStatusSummary = ex.Message;
            IsEngineReady = false;
            EngineReadyBadge = _services.Loc.T("未就绪");
            RebuildManagedComponents();
        }
    }

    [RelayCommand]
    private async Task CheckUpdateAsync()
    {
        UpdateStatusText = _services.Loc.T("正在检查更新...");
        UpdateDownloadUrl = string.Empty;
        try
        {
            var resp = await _services.ApiClient.CheckUpdateAsync();
            if (resp.Update is not null && !string.IsNullOrWhiteSpace(resp.Update.Url))
            {
                UpdateStatusText = _services.Loc.T("发现新版本 v%s（当前 v%s）", resp.Update.Version, resp.CurrentVersion);
                UpdateDownloadUrl = resp.Update.Url;
            }
            else
            {
                UpdateStatusText = _services.Loc.T("当前已是最新引擎版本 (v%s)", resp.CurrentVersion);
            }
        }
        catch
        {
            UpdateStatusText = _services.Loc.T("暂时无法连接 GitHub Releases 检查更新，请检查网络或代理");
        }
    }

    [RelayCommand]
    private void OpenUpdateDownloadUrl()
    {
        if (HasUpdateUrl)
        {
            NativeShellHelper.OpenWithShell(UpdateDownloadUrl);
        }
    }

    [RelayCommand]
    private void ShowPlatformCatalog() => PlatformCatalogDialogRequested?.Invoke(this, EventArgs.Empty);
}
