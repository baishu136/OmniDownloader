using System.Text.Json;
using OmniDownloader.WinUI.Core.Models;

namespace OmniDownloader.WinUI.Core.Services;

/// <summary>
/// WinUI 3 客户端本地轻量状态存储（服务端地址、语言、纯黑模式、自定义主题色、组件配置与批量草稿），存储于 %LOCALAPPDATA%\OmniDownloader\winui_state.json
/// </summary>
public sealed class LocalClientState
{
    public string ServiceBaseUrl { get; set; } = "http://127.0.0.1:58000";
    public bool AutoStartBackend { get; set; } = true;
    public string Language { get; set; } = "zh-CN";
    public string Theme { get; set; } = "auto"; // auto | light | dark
    public string AccentColor { get; set; } = "system"; // system | #RRGGBB
    public bool UsePureBlackDarkMode { get; set; } = false;
    public bool ShowRecentTasksOnHome { get; set; } = true;
    public bool EnableWebService { get; set; } = true;
    public bool HasCompletedOnboarding { get; set; } = false;
    public bool FollowDefaultDownloadDir { get; set; } = true;
    public string VideoDownloadDir { get; set; } = string.Empty;
    public string ImageDownloadDir { get; set; } = string.Empty;
    public string AudioDownloadDir { get; set; } = string.Empty;
    public bool AlwaysRememberCloseBehavior { get; set; } = false;
    public string SavedCloseBehavior { get; set; } = string.Empty; // "" | "exit" | "tray"
    public List<string> DisabledOptionalComponentIds { get; set; } = new();
    public List<CustomComponentEntry> CustomComponents { get; set; } = new();
    public string BatchRawInputDraft { get; set; } = string.Empty;
    public List<string> BatchDraftLinks { get; set; } = new();
}

public sealed class LocalStateService : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _stateFilePath;
    private readonly object _lock = new();
    private Timer? _saveTimer;
    private volatile bool _dirty;

    public LocalClientState Current { get; private set; }

    public LocalStateService(string? customDirectory = null)
    {
        customDirectory ??= Environment.GetEnvironmentVariable("OMNI_STATE_DIR");
        var baseDir = !string.IsNullOrWhiteSpace(customDirectory)
            ? customDirectory
            : Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OmniDownloader");
        _stateFilePath = Path.Combine(baseDir, "winui_state.json");
        Current = LoadInternal();
    }

    private LocalClientState LoadInternal()
    {
        try
        {
            if (File.Exists(_stateFilePath))
            {
                var json = File.ReadAllText(_stateFilePath);
                return JsonSerializer.Deserialize<LocalClientState>(json) ?? new LocalClientState();
            }
        }
        catch
        {
            // 读取损坏时使用默认配置而不崩溃
        }
        return new LocalClientState();
    }

    /// <summary>
    /// 防抖异步落盘，避免每次击键同步阻塞 UI 线程
    /// </summary>
    public void SaveDeferred(int delayMs = 350)
    {
        lock (_lock)
        {
            _dirty = true;
            _saveTimer ??= new Timer(_ => Save(), null, Timeout.Infinite, Timeout.Infinite);
            try
            {
                _saveTimer.Change(delayMs, Timeout.Infinite);
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }

    public void Flush()
    {
        bool needSave;
        lock (_lock)
        {
            needSave = _dirty;
        }
        if (needSave)
        {
            Save();
        }
    }

    public void Save()
    {
        lock (_lock)
        {
            _dirty = false;
            try
            {
                var dir = Path.GetDirectoryName(_stateFilePath);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                var tmp = _stateFilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(Current, JsonOptions));
                File.Move(tmp, _stateFilePath, overwrite: true);
            }
            catch
            {
                // 忽略瞬时磁盘锁定错误
            }
        }
    }

    public void Dispose()
    {
        Flush();
        lock (_lock)
        {
            _saveTimer?.Dispose();
            _saveTimer = null;
        }
    }
}
