using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.ViewModels;

/// <summary>
/// 设置页面 ViewModel：管理外观、语言、连接配置、下载存储、网络代理、中转站、13 平台 Cookie 会话、引擎诊断与平台目录
/// </summary>
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private bool _suppressThemeSync;

    public static readonly IReadOnlyList<string> SupportedSessionPlatforms = new[]
    {
        "YouTube", "X (Twitter)", "小红书", "小黑盒", "Pixiv",
        "抖音", "哔哩哔哩", "Pinterest", "Instagram", "Facebook",
        "TikTok", "快手", "微博"
    };

    public IReadOnlyList<string> SessionPlatforms => SupportedSessionPlatforms;

    private string _currentCategory = "root";
    private string _selectedLanguage;
    private string _selectedTheme;
    private bool _usePureBlackDark;
    private string _serviceBaseUrl;
    private bool _autoStartBackend;
    private string _downloadDir = string.Empty;
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

    public string ServiceBaseUrl { get => _serviceBaseUrl; set => SetProperty(ref _serviceBaseUrl, value); }
    public bool AutoStartBackend { get => _autoStartBackend; set => SetProperty(ref _autoStartBackend, value); }
    public string DownloadDir { get => _downloadDir; set => SetProperty(ref _downloadDir, value); }
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
        ? $"当前连接的后端服务版本 (v{(string.IsNullOrWhiteSpace(_services.BackendManager.ConnectedVersion) ? "未知" : _services.BackendManager.ConnectedVersion)}) 低于 1.7.59，暂不支持浏览器登录接口。请退出旧版 Web 托盘服务，并点击下方按钮重连最新版核心引擎。"
        : string.Empty;
    public string BilibiliCookieInput { get => _bilibiliCookieInput; set => SetProperty(ref _bilibiliCookieInput, value); }
    public bool HasBilibiliSession { get => _hasBilibiliSession; set => SetProperty(ref _hasBilibiliSession, value); }

    // 中转站
    public ObservableCollection<RelaySite> RelaySites { get; } = new();

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
    public event EventHandler? PlatformCatalogDialogRequested;

    public SettingsViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        _selectedLanguage = services.LocalState.Current.Language;
        _selectedTheme = services.LocalState.Current.Theme;
        _usePureBlackDark = services.LocalState.Current.UsePureBlackDarkMode;
        _serviceBaseUrl = services.LocalState.Current.ServiceBaseUrl;
        _autoStartBackend = services.LocalState.Current.AutoStartBackend;

        _services.Loc.LanguageChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(RelaySectionTitle));
            _ = LoadSelectedSessionPolicyAsync();
        };
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
                    DownloadDir = s.DownloadDir;
                    MaxConcurrentTasks = Math.Clamp(s.MaxConcurrentTasks, 1, 10);
                    AutoStartWithWindows = s.AutoStart;
                    ProxyUrl = s.ProxyUrl;
                    HasBilibiliSession = s.HasBilibiliSession;
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
            _main.Notify(enabled ? "已开启开机自启动" : "已关闭开机自启动", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
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
            StartLoginStatusPolling(SelectedSessionPlatform);
            _main.Notify("正在打开独立 Edge 登录窗口。Google 登录后请返回目标平台，再点击保存会话并关闭。", InfoBarSeverity.Informational);
        }
        catch (OmniApiException ex) when (ex.StatusCode == 404)
        {
            const string hint = "当前连接的本地后台服务未提供浏览器登录接口（可能仍在运行旧版 1.7.51 服务）。请退出旧版托盘服务或升级后点击顶部刷新重连。";
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
            _main.Notify($"已保存 {result.CookieCount} 项 Cookie 并关闭登录窗口；请重新解析验证账号。", InfoBarSeverity.Success);
            StopLoginStatusPolling();
            await LoadSelectedSessionPolicyAsync();
        }
        catch (OmniApiException ex) when (ex.StatusCode == 404)
        {
            const string hint = "当前连接的本地后台服务未提供会话捕获接口（版本低于 1.7.59）。请退出旧版服务并重连最新版核心。";
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
                    if (!st.Running) break;
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
        var baseText = st.Phase switch
        {
            "OPENING" => "正在打开独立 Edge 登录窗口…",
            "WAITING_LOGIN" => "请在 Edge 中完成登录；Google 登录后返回目标平台，再点击「保存会话并关闭」。",
            "CAPTURED" => $"已保存目标平台 Cookie（{st.CookieCount} 项）；未验证账号权益，请返回重新解析原链接。",
            "ERROR" => $"登录捕获未完成（{(string.IsNullOrWhiteSpace(st.ErrorCode) ? "CAPTURE_FAILED" : st.ErrorCode)}），原有凭据已保留。",
            _ => "使用独立 Edge 窗口登录。Google 登录后请返回目标平台，再点击保存会话；已保存不等于账号已验证。"
        };
        if (st.SkippedPartitionedCookies > 0)
        {
            baseText += $" 已跳过 {st.SkippedPartitionedCookies} 项分区 Cookie。";
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
            var countSuffix = info is { CookieCount: > 0 } ? $" · {info.CookieCount} 项" : string.Empty;

            _main.RunOnUi(() =>
            {
                IsSelectedPlatformCookieDisabled = !enabled;
                SessionStatusBadge = !enabled
                    ? _services.Loc.T("已停用（保留原凭证）")
                    : st switch
                    {
                        "IMPORTED" => $"已保存（未验证账号{countSuffix}）",
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
            $"确定清除「{SelectedSessionPlatform}」的 Cookie 和独立登录浏览器状态吗？操作后需要重新登录。",
            _services.Loc.T("清除"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!ok) return;

        try
        {
            StopLoginStatusPolling();
            await _services.ApiClient.ClearSessionAsync(SelectedSessionPlatform);
            BrowserLoginStatusText = "会话及受控登录浏览器状态已清除，可重新打开登录窗口或导入 Cookie 文件。";
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

    // === 引擎与更新 ===

    [RelayCommand]
    public async Task CheckEngineDiagnosticsAsync()
    {
        EngineStatusSummary = "正在探测本机引擎环境...";
        try
        {
            var d = await _services.ApiClient.GetDiagnosticsAsync();
            _main.RunOnUi(() =>
            {
                EngineStatusSummary =
                    $"Python {d.Python} · yt-dlp {d.YtDlp} · EJS {d.Ejs} · FFmpeg {(d.FfmpegAvailable ? "就绪" : "未就绪")} · JS 运行时 {(d.DenoAvailable ? "可用" : "缺失")}";
                IsEngineReady = !string.IsNullOrWhiteSpace(d.YtDlp);
                EngineReadyBadge = IsEngineReady ? _services.Loc.T("正常运行") : _services.Loc.T("未就绪");
            });
        }
        catch (Exception ex)
        {
            EngineStatusSummary = ex.Message;
            IsEngineReady = false;
            EngineReadyBadge = _services.Loc.T("未就绪");
        }
    }

    [RelayCommand]
    private async Task CheckUpdateAsync()
    {
        UpdateStatusText = "正在检查更新...";
        UpdateDownloadUrl = string.Empty;
        try
        {
            var resp = await _services.ApiClient.CheckUpdateAsync();
            if (resp.Update is not null && !string.IsNullOrWhiteSpace(resp.Update.Url))
            {
                UpdateStatusText = $"发现新版本 v{resp.Update.Version}（当前 v{resp.CurrentVersion}）";
                UpdateDownloadUrl = resp.Update.Url;
            }
            else
            {
                UpdateStatusText = $"当前已是最新引擎版本 (v{resp.CurrentVersion})";
            }
        }
        catch
        {
            UpdateStatusText = "暂时无法连接 GitHub Releases 检查更新，请检查网络或代理";
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
