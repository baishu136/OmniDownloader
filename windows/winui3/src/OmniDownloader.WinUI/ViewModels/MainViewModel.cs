using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.ViewModels;

/// <summary>
/// 全局主 ViewModel：协调后端连接、SSE 任务事件流、导航与全局 InfoBar 消息提醒
/// </summary>
public sealed partial class MainViewModel : ObservableObject
{
    private static readonly JsonSerializerOptions SseJsonOptions = new() { PropertyNameCaseInsensitive = true };

    private readonly AppServices _services;
    private readonly object _sseLock = new();
    private readonly SemaphoreSlim _tasksRefreshGate = new(1, 1);
    private DispatcherQueue? _dispatcher;
    private CancellationTokenSource? _sseCts;
    private CancellationTokenSource? _toastTimerCts;
    private CancellationTokenSource? _tasksDebounceCts;
    private volatile bool _isShuttingDown;
    private long _lastSingleTaskUiTickMs;

    private string _selectedNavKey = "home";
    private bool _isBackendConnected;
    private string _backendStatusText = "正在连接本地核心引擎...";
    private int _activeTasksCount;
    private bool _isNoticeOpen;
    private string _noticeMessage = string.Empty;
    private InfoBarSeverity _noticeSeverity = InfoBarSeverity.Informational;

    public string SelectedNavKey { get => _selectedNavKey; set => SetProperty(ref _selectedNavKey, value); }
    public bool IsBackendConnected { get => _isBackendConnected; set => SetProperty(ref _isBackendConnected, value); }
    public string BackendStatusText { get => _backendStatusText; set => SetProperty(ref _backendStatusText, value); }
    public int ActiveTasksCount
    {
        get => _activeTasksCount;
        set
        {
            if (SetProperty(ref _activeTasksCount, value))
            {
                OnPropertyChanged(nameof(ActiveTasksBadgeVisibility));
            }
        }
    }
    public bool IsNoticeOpen { get => _isNoticeOpen; set => SetProperty(ref _isNoticeOpen, value); }
    public string NoticeMessage { get => _noticeMessage; set => SetProperty(ref _noticeMessage, value); }
    public InfoBarSeverity NoticeSeverity { get => _noticeSeverity; set => SetProperty(ref _noticeSeverity, value); }

    public HomeViewModel HomeVm { get; }
    public BatchViewModel BatchVm { get; }
    public TasksViewModel TasksVm { get; }
    public SettingsViewModel SettingsVm { get; }

    public Microsoft.UI.Xaml.Visibility ActiveTasksBadgeVisibility =>
        ActiveTasksCount > 0 ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;

    public LocalizationService Loc => _services.Loc;

    public event EventHandler<(string PageKey, string? SettingsCategory)>? NavigationRequested;
    public event EventHandler<(string Theme, bool PureBlack)>? ThemeAppearanceChanged;
    public event EventHandler? OnboardingRequested;
    public event EventHandler? WebServiceModeChanged;

    public void RequestOnboarding() => OnboardingRequested?.Invoke(this, EventArgs.Empty);

    public void NotifyWebServiceModeChanged() => WebServiceModeChanged?.Invoke(this, EventArgs.Empty);

    public MainViewModel(AppServices services)
    {
        _services = services;
        HomeVm = new HomeViewModel(services, this);
        BatchVm = new BatchViewModel(services, this);
        TasksVm = new TasksViewModel(services, this);
        SettingsVm = new SettingsViewModel(services, this);
        _backendStatusText = _services.Loc.T("正在连接本地核心引擎...");
        _services.Loc.LanguageChanged += (_, _) =>
        {
            var note = _services.BackendManager.GetLocalizedStatusNote();
            RunOnUi(() =>
            {
                BackendStatusText = !string.IsNullOrWhiteSpace(note)
                    ? note
                    : _services.Loc.T("正在连接本地核心引擎...");
            });
        };
    }

    public void AttachDispatcher(DispatcherQueue dispatcher) => _dispatcher = dispatcher;

    public void PrepareShutdown()
    {
        _isShuttingDown = true;
        StopSseListener();
        SettingsVm.Shutdown();
        try
        {
            _toastTimerCts?.Cancel();
            _toastTimerCts?.Dispose();
            _toastTimerCts = null;
        }
        catch { }

        try
        {
            _tasksDebounceCts?.Cancel();
            _tasksDebounceCts?.Dispose();
            _tasksDebounceCts = null;
        }
        catch { }
    }

    public void RunOnUi(Action action)
    {
        if (_isShuttingDown) return;
        var dispatcher = _dispatcher;
        if (dispatcher is null)
        {
            // 启动未附着 Dispatcher 或关闭后绝不在后台线程直接操作 UI 对象，防止 RPC_E_WRONG_THREAD 闪退
            return;
        }

        if (dispatcher.HasThreadAccess)
        {
            try
            {
                if (!_isShuttingDown) action();
            }
            catch (Exception ex)
            {
                AppServices.WriteCrashLog("RunOnUi.Direct", ex);
            }
            return;
        }

        dispatcher.TryEnqueue(() =>
        {
            if (_isShuttingDown) return;
            try
            {
                action();
            }
            catch (Exception ex)
            {
                AppServices.WriteCrashLog("RunOnUi.Enqueue", ex);
            }
        });
    }

    public void Notify(string keyOrMessage, InfoBarSeverity severity = InfoBarSeverity.Success, params object[] args)
    {
        var text = _services.Loc.T(keyOrMessage, args);
        RunOnUi(() =>
        {
            NoticeMessage = text;
            NoticeSeverity = severity;
            IsNoticeOpen = true;

            _toastTimerCts?.Cancel();
            _toastTimerCts?.Dispose();
            var cts = new CancellationTokenSource();
            _toastTimerCts = cts;
            _ = AutoDismissNoticeAsync(cts.Token);
        });
    }

    private async Task AutoDismissNoticeAsync(CancellationToken ct)
    {
        try
        {
            await Task.Delay(3400, ct);
            RunOnUi(() => IsNoticeOpen = false);
        }
        catch (TaskCanceledException)
        {
        }
    }

    public void NavigateTo(string pageKey, string? settingsCategory = null)
    {
        SelectedNavKey = pageKey;
        if (pageKey == "settings" && !string.IsNullOrWhiteSpace(settingsCategory))
        {
            SettingsVm.OpenCategory(settingsCategory);
        }
        NavigationRequested?.Invoke(this, (pageKey, settingsCategory));
    }

    public void RaiseThemeChanged()
    {
        ThemeAppearanceChanged?.Invoke(
            this,
            (_services.LocalState.Current.Theme, _services.LocalState.Current.UsePureBlackDarkMode));
    }

    public async Task InitializeAsync()
    {
        if (_isShuttingDown) return;
        var baseUrl = _services.LocalState.Current.ServiceBaseUrl;
        var autoStart = _services.LocalState.Current.AutoStartBackend;

        // 后台线程完成进程探测或冷启动等待，绝不阻塞 UI 消息泵
        var ok = await Task.Run(() => _services.BackendManager.EnsureConnectedAsync(
            _services.ApiClient,
            baseUrl,
            autoStart)).ConfigureAwait(false);

        if (_isShuttingDown) return;

        RunOnUi(() =>
        {
            IsBackendConnected = ok;
            BackendStatusText = _services.BackendManager.GetLocalizedStatusNote();
        });

        if (!ok)
        {
            Notify("无法读取设置，本地服务可能未启动", InfoBarSeverity.Warning);
            return;
        }

        if (_services.BackendManager.IsOutdatedBackend)
        {
            Notify(
                "当前连接的服务版本 (v%s) 过低，缺少浏览器登录等 1.7.59+ 接口。请退出旧版服务或开启自动伴随启动。",
                InfoBarSeverity.Warning,
                _services.BackendManager.ConnectedVersion);
        }

        await RefreshAllFromBackendAsync().ConfigureAwait(false);
        if (!_isShuttingDown)
        {
            StartSseListener();
        }
    }

    public async Task ReconnectBackendAsync()
    {
        RunOnUi(() => BackendStatusText = _services.Loc.T("正在重新连接..."));
        StopSseListener();
        await InitializeAsync();
        if (IsBackendConnected)
        {
            Notify("已连接本地引擎", InfoBarSeverity.Success);
        }
    }

    public async Task RefreshAllFromBackendAsync()
    {
        try
        {
            // 启动阶段优先并行加载精简设置与任务列表；诊断与会话策略延迟至进入设置子页再按需读取
            await Task.WhenAll(
                SettingsVm.LoadInitialAsync(),
                RefreshTasksAsync()).ConfigureAwait(false);

            if (string.Equals(SelectedNavKey, "batch", StringComparison.Ordinal))
            {
                await BatchVm.RefreshBatchesAsync().ConfigureAwait(false);
            }
        }
        catch
        {
            // 各子模块会优雅提示错误，避免阻断其余初始化
        }
    }

    public async Task<bool> RefreshTasksAsync()
    {
        if (_isShuttingDown) return false;
        await _tasksRefreshGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_isShuttingDown) return false;
            var list = await _services.ApiClient.GetTasksAsync().ConfigureAwait(false);
            ApplyTaskSnapshot(list);
            return true;
        }
        catch
        {
            return false;
        }
        finally
        {
            _tasksRefreshGate.Release();
        }
    }

    private void ApplyTaskSnapshot(IReadOnlyList<DownloadTask> tasks)
    {
        RunOnUi(() =>
        {
            ActiveTasksCount = tasks.Count(t => t.IsActive);
            TasksVm.UpdateFromTasks(tasks);
            HomeVm.UpdateRecentTasks(tasks);
        });
    }

    private void ScheduleTasksRefresh(int delayMs = 220)
    {
        if (_isShuttingDown) return;
        lock (_sseLock)
        {
            _tasksDebounceCts?.Cancel();
            _tasksDebounceCts?.Dispose();
            var cts = new CancellationTokenSource();
            _tasksDebounceCts = cts;
            _ = DebouncedRefreshTasksAsync(delayMs, cts.Token);
        }
    }

    private async Task DebouncedRefreshTasksAsync(int delayMs, CancellationToken ct)
    {
        try
        {
            await Task.Delay(delayMs, ct).ConfigureAwait(false);
            if (!ct.IsCancellationRequested && !_isShuttingDown)
            {
                await RefreshTasksAsync().ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    public void StartSseListener()
    {
        if (_isShuttingDown) return;
        CancellationToken token;
        lock (_sseLock)
        {
            StopSseListenerUnsafe();
            var cts = new CancellationTokenSource();
            _sseCts = cts;
            token = cts.Token;
        }
        _ = Task.Run(() => SseLoopAsync(token), token);
    }

    public void StopSseListener()
    {
        lock (_sseLock)
        {
            StopSseListenerUnsafe();
        }
    }

    private void StopSseListenerUnsafe()
    {
        try { _sseCts?.Cancel(); } catch { }
        try { _sseCts?.Dispose(); } catch { }
        _sseCts = null;
    }

    private async Task SseLoopAsync(CancellationToken ct)
    {
        var delayMs = 1500;
        while (!ct.IsCancellationRequested && !_isShuttingDown)
        {
            try
            {
                await foreach (var doc in _services.ApiClient.ListenEventsAsync(ct).ConfigureAwait(false))
                {
                    delayMs = 1500;
                    HandleSsePayload(doc);
                    doc.Dispose();
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested || _isShuttingDown)
            {
                break;
            }
            catch
            {
                RunOnUi(() =>
                {
                    IsBackendConnected = false;
                    BackendStatusText = Loc.T("事件连接中断，正在重试...");
                });
            }

            try
            {
                await Task.Delay(delayMs, ct).ConfigureAwait(false);
                delayMs = Math.Min(delayMs * 2, 10000);
                var ok = await RefreshTasksAsync().ConfigureAwait(false);
                if (ok)
                {
                    RunOnUi(() =>
                    {
                        IsBackendConnected = true;
                        BackendStatusText = _services.BackendManager.LastStatusNote;
                    });
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private void HandleSsePayload(JsonDocument doc)
    {
        try
        {
            var root = doc.RootElement;
            if (!root.TryGetProperty("type", out var typeProp)) return;
            var eventType = typeProp.GetString();

            if (eventType == "task_update" && root.TryGetProperty("task", out var taskEl))
            {
                var task = taskEl.Deserialize<DownloadTask>(SseJsonOptions);
                if (task is not null)
                {
                    var nowMs = Environment.TickCount64;
                    var isActiveTick = task.IsActive;
                    // 下载中进度节流合并至 ~120ms 刷新一帧，终态变更立即刷新，免除 HTTP 重拉风暴
                    if (!isActiveTick || nowMs - Interlocked.Read(ref _lastSingleTaskUiTickMs) >= 120)
                    {
                        Interlocked.Exchange(ref _lastSingleTaskUiTickMs, nowMs);
                        RunOnUi(() =>
                        {
                            TasksVm.ApplySingleTaskUpdate(task);
                            HomeVm.ApplySingleRecentTaskUpdate(task);
                            ActiveTasksCount = TasksVm.ActiveCount;
                        });
                    }
                    _ = BatchVm.RefreshBatchesIfVisibleAsync();
                    return;
                }
            }

            if (eventType is "tasks_updated" or "task_update" or "progress" or "task_deleted" or "tasks_cleared")
            {
                ScheduleTasksRefresh(eventType == "progress" ? 280 : 120);
                _ = BatchVm.RefreshBatchesIfVisibleAsync();
            }
        }
        catch
        {
        }
    }
}
