using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.ViewModels;

namespace OmniDownloader.WinUI.Services;

/// <summary>
/// 应用级服务聚合容器：持有后端连接器、API 客户端、本地状态、子窗口注册表与四大页面 ViewModel
/// </summary>
public sealed class AppServices : IDisposable
{
    public static AppServices Current { get; } = new();

    private readonly object _windowsLock = new();
    private readonly List<Window> _secondaryWindows = new();
    private volatile bool _isShuttingDown;

    public LocalStateService LocalState { get; }
    public OmniApiClient ApiClient { get; }
    public BackendProcessManager BackendManager { get; }
    public DialogService Dialogs { get; }
    public LocalizationService Loc => LocalizationService.Instance;
    public bool IsShuttingDown => _isShuttingDown;

    public MainViewModel MainVm { get; }

    private AppServices()
    {
        LocalState = new LocalStateService();
        Loc.SetLanguage(LocalState.Current.Language);

        ApiClient = new OmniApiClient(LocalState.Current.ServiceBaseUrl);
        BackendManager = new BackendProcessManager();
        Dialogs = new DialogService();
        MainVm = new MainViewModel(this);
    }

    public void AttachUiDispatcher(DispatcherQueue dispatcher)
    {
        MainVm.AttachDispatcher(dispatcher);
    }

    public void RegisterSecondaryWindow(Window window)
    {
        if (window is null) return;
        lock (_windowsLock)
        {
            if (!_secondaryWindows.Contains(window))
            {
                _secondaryWindows.Add(window);
            }
        }
        window.Closed += (_, _) => UnregisterSecondaryWindow(window);
    }

    public void UnregisterSecondaryWindow(Window window)
    {
        if (window is null) return;
        lock (_windowsLock)
        {
            _secondaryWindows.Remove(window);
        }
    }

    public void CloseAllSecondaryWindows()
    {
        List<Window> snapshot;
        lock (_windowsLock)
        {
            snapshot = _secondaryWindows.ToList();
            _secondaryWindows.Clear();
        }

        foreach (var w in snapshot)
        {
            try
            {
                w.DispatcherQueue?.TryEnqueue(() =>
                {
                    try { w.Close(); } catch { }
                });
            }
            catch
            {
            }
        }
    }

    public static void WriteCrashLog(string source, Exception? ex)
    {
        if (ex is null) return;
        try
        {
            var baseDir = Environment.GetEnvironmentVariable("OMNI_STATE_DIR");
            if (string.IsNullOrWhiteSpace(baseDir))
            {
                baseDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "OmniDownloader");
            }
            Directory.CreateDirectory(baseDir);
            var logPath = Path.Combine(baseDir, "winui_crash.log");
            if (File.Exists(logPath) && new FileInfo(logPath).Length > 256 * 1024)
            {
                File.WriteAllText(logPath, string.Empty);
            }
            var entry = $"[{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss zzz}] [{source}] {ex.GetType().FullName}: {ex.Message}\n{ex.StackTrace}\n\n";
            File.AppendAllText(logPath, entry);
        }
        catch
        {
        }
    }

    public async Task ShutdownAsync(bool stopAssociatedBackend)
    {
        if (_isShuttingDown) return;
        _isShuttingDown = true;

        LocalState.Flush();
        MainVm.PrepareShutdown();
        CloseAllSecondaryWindows();
        Dialogs.Dispose();
        await BackendManager.ShutdownAllAssociatedAsync(ApiClient, stopAssociatedBackend).ConfigureAwait(false);
        ApiClient.Dispose();
        LocalState.Dispose();
    }

    public void Dispose()
    {
        if (_isShuttingDown) return;
        _isShuttingDown = true;

        LocalState.Flush();
        MainVm.PrepareShutdown();
        CloseAllSecondaryWindows();
        Dialogs.Dispose();
        BackendManager.Dispose();
        ApiClient.Dispose();
        LocalState.Dispose();
    }
}
