using System.Diagnostics;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace OmniDownloader.WinUI.Core.Services;

public enum BackendConnectionMode
{
    Disconnected,
    ExternalService,
    ManagedBundledProcess
}

/// <summary>
/// 本地 Python 核心下载引擎探测与伴随生命周期管理器：
/// 优先无冲突复用版本兼容的 OmniDownloader 服务（例如 58000 端口），
/// 若未运行或既有的外部服务版本过低（缺少浏览器登录等 1.7.63+ 接口）且本地存在新版引擎时，
/// 自动拉起仓库 omni_server.py（并强制统一 OMNI_STATE_DIR 共享配置与凭据）并在退出时安全清理子进程。
/// </summary>
public sealed class BackendProcessManager : IDisposable
{
    public static readonly Version MinimumRequiredBackendVersion = new(1, 7, 63);

    private Process? _managedProcess;
    private bool _sharedWebService;
    private readonly object _lock = new();

    private string _statusNoteKey = string.Empty;
    private object[] _statusNoteArgs = Array.Empty<object>();

    public BackendConnectionMode Mode { get; private set; } = BackendConnectionMode.Disconnected;
    public string ActiveBaseUrl { get; private set; } = "http://127.0.0.1:58000";
    public string ConnectedVersion { get; private set; } = string.Empty;
    public bool IsOutdatedBackend { get; private set; }
    public string LastStatusNote { get; private set; } = string.Empty;

    private void SetStatusNote(string key, params object[] args)
    {
        _statusNoteKey = key;
        _statusNoteArgs = args ?? Array.Empty<object>();
        LastStatusNote = LocalizationService.Instance.T(key, _statusNoteArgs);
    }

    /// <summary>
    /// 按当前活动语言重新格式化最近一条引擎状态文案，确保运行时切换界面语言后标题栏同步更新。
    /// </summary>
    public string GetLocalizedStatusNote()
    {
        if (string.IsNullOrWhiteSpace(_statusNoteKey))
        {
            return LastStatusNote;
        }
        if (_statusNoteKey == "已连接旧版服务 v%s (缺少 1.7.63+ 普通浏览器登录修复)" && _statusNoteArgs.Length == 1 && _statusNoteArgs[0] is null)
        {
            return LocalizationService.Instance.T(_statusNoteKey, LocalizationService.Instance.T("未知"));
        }
        LastStatusNote = LocalizationService.Instance.T(_statusNoteKey, _statusNoteArgs);
        return LastStatusNote;
    }

    public async Task<bool> EnsureConnectedAsync(
        OmniApiClient apiClient,
        string configuredBaseUrl,
        bool autoStartIfOffline,
        CancellationToken ct = default)
    {
        var targetUrl = string.IsNullOrWhiteSpace(configuredBaseUrl)
            ? "http://127.0.0.1:58000"
            : configuredBaseUrl.Trim().TrimEnd('/');

        apiClient.UpdateBaseUrl(targetUrl);
        ActiveBaseUrl = apiClient.BaseUrl;
        IsOutdatedBackend = false;
        ConnectedVersion = string.Empty;

        // 1. 探测目标服务是否在线及其版本号
        var probe = await TryProbeBackendAsync(apiClient, 1000, ct).ConfigureAwait(false);
        if (probe.Reachable)
        {
            ConnectedVersion = probe.Version;
            var isManaged = HasRunningManagedProcess();
            var compatible = IsVersionAtLeast(probe.Version, MinimumRequiredBackendVersion);

            if (compatible || isManaged || !autoStartIfOffline || !IsLocalLoopbackUrl(ActiveBaseUrl))
            {
                Mode = isManaged ? BackendConnectionMode.ManagedBundledProcess : BackendConnectionMode.ExternalService;
                IsOutdatedBackend = !compatible;
                if (compatible)
                {
                    SetStatusNote(
                        isManaged ? "已启动本地伴随引擎 (%s)" : "已连接本机运行服务 (%s)",
                        ActiveBaseUrl);
                }
                else
                {
                    SetStatusNote(
                        "已连接旧版服务 v%s (缺少 1.7.63+ 普通浏览器登录修复)",
                        string.IsNullOrWhiteSpace(probe.Version) ? null! : probe.Version);
                }
                return true;
            }

            // A second core would compete for the same task/configuration files.
            Mode = BackendConnectionMode.ExternalService;
            IsOutdatedBackend = true;
            SetStatusNote("旧版共享服务 v%s 正在运行，请从托盘正常退出后重连新版", probe.Version);
            return true;
        }

        if (!autoStartIfOffline || !IsLocalLoopbackUrl(ActiveBaseUrl))
        {
            Mode = BackendConnectionMode.Disconnected;
            SetStatusNote("未检测到运行中的本地服务");
            return false;
        }

        // 2. 自动搜寻本地 windows/omni_server.py + Python，或同级 OmniDownloader_Web.exe 并伴随启动
        var port = FindAvailablePort(TryExtractPort(ActiveBaseUrl, 58000));
        var bundledWeb = LocateSiblingWebExe();
        if (bundledWeb is not null)
            return await StartBundledWebServerAsync(apiClient, bundledWeb, port, ct).ConfigureAwait(false);
        var layout = LocateServerAndPython();
        if (layout is not null)
            return await StartManagedServerAsync(apiClient, layout.Value, port, ct).ConfigureAwait(false);

        Mode = BackendConnectionMode.Disconnected;
        SetStatusNote("未在本机找到 omni_server.py 或配套服务程序");
        return false;
    }

    private bool HasRunningManagedProcess()
    {
        lock (_lock)
        {
            try
            {
                return _managedProcess is { HasExited: false };
            }
            catch
            {
                return false;
            }
        }
    }

    private bool TryGetManagedExitCode(out int exitCode)
    {
        lock (_lock)
        {
            try
            {
                if (_managedProcess is { HasExited: true })
                {
                    exitCode = _managedProcess.ExitCode;
                    return true;
                }
            }
            catch
            {
            }
            exitCode = 0;
            return false;
        }
    }

    private async Task<bool> StartManagedServerAsync(
        OmniApiClient apiClient,
        (string PythonExe, string ServerScript, string WindowsDir) layout,
        int port,
        CancellationToken ct)
    {
        var stateDir = ResolveStateDir();
        try
        {
            lock (_lock)
            {
                StopManagedProcessInternal();
                var psi = new ProcessStartInfo
                {
                    FileName = layout.PythonExe,
                    Arguments = $"\"{layout.ServerScript}\" --host 127.0.0.1 --port {port}",
                    WorkingDirectory = layout.WindowsDir,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                };
                psi.Environment["PYTHONUTF8"] = "1";
                psi.Environment["PYTHONIOENCODING"] = "utf-8";
                psi.Environment["OMNI_STATE_DIR"] = stateDir;
                _sharedWebService = false;
                _managedProcess = Process.Start(psi);
            }
        }
        catch (Exception ex)
        {
            Mode = BackendConnectionMode.Disconnected;
            SetStatusNote("启动伴随 Python 服务失败 (%s)", ex.Message);
            return false;
        }

        if (!HasRunningManagedProcess() && !TryGetManagedExitCode(out _))
        {
            Mode = BackendConnectionMode.Disconnected;
            SetStatusNote("未能拉起伴随 Python 服务进程");
            return false;
        }

        return await WaitForManagedReadyAsync(apiClient, port, ct).ConfigureAwait(false);
    }

    private async Task<bool> StartBundledWebServerAsync(
        OmniApiClient apiClient,
        string webExePath,
        int port,
        CancellationToken ct)
    {
        var stateDir = ResolveStateDir();
        try
        {
            lock (_lock)
            {
                StopManagedProcessInternal();
                var psi = new ProcessStartInfo
                {
                    FileName = webExePath,
                    Arguments = "--silent --no-tray",
                    WorkingDirectory = Path.GetDirectoryName(webExePath) ?? AppContext.BaseDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                };
                psi.Environment["OMNI_PORT"] = port.ToString();
                psi.Environment["OMNI_STATE_DIR"] = stateDir;
                _sharedWebService = true;
                _managedProcess = Process.Start(psi);
            }
        }
        catch (Exception ex)
        {
            Mode = BackendConnectionMode.Disconnected;
            SetStatusNote("启动内置服务程序失败 (%s)", ex.Message);
            return false;
        }

        return await WaitForManagedReadyAsync(apiClient, port, ct).ConfigureAwait(false);
    }

    private async Task<bool> WaitForManagedReadyAsync(OmniApiClient apiClient, int port, CancellationToken ct)
    {
        var launchedUrl = $"http://127.0.0.1:{port}";
        apiClient.UpdateBaseUrl(launchedUrl);
        ActiveBaseUrl = apiClient.BaseUrl;

        for (var i = 0; i < 60; i++)
        {
            ct.ThrowIfCancellationRequested();
            if (TryGetManagedExitCode(out var exitCode))
            {
                Mode = BackendConnectionMode.Disconnected;
                SetStatusNote("伴随服务启动后提前退出 (Code %d)", exitCode);
                return false;
            }
            var probe = await TryProbeBackendAsync(apiClient, 1200, ct).ConfigureAwait(false);
            if (probe.Reachable)
            {
                Mode = BackendConnectionMode.ManagedBundledProcess;
                ConnectedVersion = probe.Version;
                IsOutdatedBackend = !IsVersionAtLeast(probe.Version, MinimumRequiredBackendVersion);
                SetStatusNote("已启动本地伴随引擎 (%s)", ActiveBaseUrl);
                return true;
            }
            var delayMs = i < 8 ? 100 : (i < 20 ? 150 : 250);
            await Task.Delay(delayMs, ct).ConfigureAwait(false);
        }

        Mode = BackendConnectionMode.Disconnected;
        SetStatusNote("等待伴随引擎就绪超时");
        return false;
    }

    private static string ResolveStateDir()
    {
        var stateDir = Environment.GetEnvironmentVariable("OMNI_STATE_DIR");
        return !string.IsNullOrWhiteSpace(stateDir)
            ? stateDir
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniDownloader");
    }

    public static bool IsVersionAtLeast(string? versionText, Version minimum)
    {
        var parsed = ParseSemanticVersion(versionText);
        return parsed is not null && parsed >= minimum;
    }

    public static Version? ParseSemanticVersion(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var m = Regex.Match(raw.Trim(), @"v?(\d+)\.(\d+)\.(\d+)");
        if (!m.Success) return null;
        if (int.TryParse(m.Groups[1].Value, out var major) &&
            int.TryParse(m.Groups[2].Value, out var minor) &&
            int.TryParse(m.Groups[3].Value, out var patch))
        {
            return new Version(major, minor, patch);
        }
        return null;
    }

    private static string ReadLocalScriptVersion(string windowsDir)
    {
        try
        {
            var featureFile = Path.Combine(windowsDir, "engine", "service_features.py");
            if (File.Exists(featureFile))
            {
                var content = File.ReadAllText(featureFile);
                var m = Regex.Match(content, @"VERSION\s*=\s*['""]([^'""]+)['""]");
                if (m.Success) return m.Groups[1].Value;
            }
        }
        catch
        {
        }
        return string.Empty;
    }

    private static async Task<(bool Reachable, string Version)> TryProbeBackendAsync(
        OmniApiClient apiClient,
        int timeoutMs,
        CancellationToken ct)
    {
        try
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(TimeSpan.FromMilliseconds(timeoutMs));
            var settings = await apiClient.GetSettingsAsync(timeoutCts.Token).ConfigureAwait(false);

            // 新版 /api/settings 直接附带 version，避免启动时触发 /api/platforms 导入 1800+ yt-dlp 提取器
            var version = settings?.Version ?? string.Empty;
            if (string.IsNullOrWhiteSpace(version))
            {
                try
                {
                    var catalog = await apiClient.GetPlatformCatalogAsync(timeoutCts.Token).ConfigureAwait(false);
                    version = catalog.Version ?? string.Empty;
                }
                catch
                {
                }
            }
            return (true, version);
        }
        catch
        {
            return (false, string.Empty);
        }
    }

    private static bool IsLocalLoopbackUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        return uri.Host.Equals("127.0.0.1", StringComparison.OrdinalIgnoreCase)
               || uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase);
    }

    private static int TryExtractPort(string url, int fallback)
    {
        return Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Port > 0 ? uri.Port : fallback;
    }

    private static int FindAvailablePort(int startPort)
    {
        for (var port = Math.Clamp(startPort, 1024, 65000); port < 65500; port++)
        {
            try
            {
                using var listener = new TcpListener(System.Net.IPAddress.Loopback, port);
                listener.Start();
                listener.Stop();
                return port;
            }
            catch
            {
                // 端口已被占用，尝试下一个
            }
        }
        return startPort;
    }

    private static (string PythonExe, string ServerScript, string WindowsDir)? LocateServerAndPython()
    {
        var probeRoots = new[]
        {
            AppContext.BaseDirectory,
            Directory.GetCurrentDirectory()
        };

        foreach (var start in probeRoots)
        {
            var dir = new DirectoryInfo(start);
            for (var depth = 0; depth < 10 && dir is not null; depth++, dir = dir.Parent)
            {
                // 场景 A：就在 windows/ 或其子目录下
                var directServer = Path.Combine(dir.FullName, "omni_server.py");
                if (File.Exists(directServer))
                {
                    var repoRoot = dir.Parent?.FullName ?? dir.FullName;
                    var py = FindPythonExecutable(repoRoot, dir.FullName);
                    if (py is not null) return (py, directServer, dir.FullName);
                }

                // 场景 B：在仓库根目录，下层存在 windows/omni_server.py
                var windowsSub = Path.Combine(dir.FullName, "windows");
                var subServer = Path.Combine(windowsSub, "omni_server.py");
                if (File.Exists(subServer))
                {
                    var py = FindPythonExecutable(dir.FullName, windowsSub);
                    if (py is not null) return (py, subServer, windowsSub);
                }
            }
        }

        return null;
    }

    private static string? LocateSiblingWebExe()
    {
        var baseDir = new DirectoryInfo(AppContext.BaseDirectory);
        for (var depth = 0; depth < 4 && baseDir is not null; depth++, baseDir = baseDir.Parent)
        {
            var direct = Path.Combine(baseDir.FullName, "OmniDownloader_Web.exe");
            if (File.Exists(direct)) return direct;

            var sibling = Path.Combine(baseDir.FullName, "OmniDownloader_Web", "OmniDownloader_Web.exe");
            if (File.Exists(sibling)) return sibling;
        }
        return null;
    }

    private static string? FindPythonExecutable(string repoRoot, string windowsDir)
    {
        var candidates = new[]
        {
            Path.Combine(repoRoot, ".venv", "Scripts", "python.exe"),
            Path.Combine(windowsDir, ".venv", "Scripts", "python.exe")
        };

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var segment in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var exe = Path.Combine(segment.Trim(), "python.exe");
                if (File.Exists(exe)) return exe;
            }
            catch
            {
            }
        }
        return null;
    }

    /// <summary>
    /// 在桌面端关闭时按网页服务开关决定是否彻底结束关联的后台服务与全部子组件进程
    /// </summary>
    public async Task ShutdownAllAssociatedAsync(OmniApiClient? apiClient, bool stopAssociatedBackend)
    {
        if (stopAssociatedBackend && apiClient is not null)
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(1200));
                await apiClient.ShutdownBackendAsync(cts.Token).ConfigureAwait(false);
            }
            catch
            {
                // 本地服务可能尚未监听或已退出
            }
        }

        lock (_lock)
        {
            StopManagedProcessInternal(forceKillShared: stopAssociatedBackend);
            if (stopAssociatedBackend)
            {
                Mode = BackendConnectionMode.Disconnected;
            }
        }
    }

    private void StopManagedProcessInternal(bool forceKillShared = false)
    {
        if (_managedProcess is null) return;
        try
        {
            if ((forceKillShared || !_sharedWebService) && !_managedProcess.HasExited)
            {
                _managedProcess.Kill(entireProcessTree: true);
            }
        }
        catch
        {
            // 进程已退出时忽略
        }
        finally
        {
            _managedProcess.Dispose();
            _managedProcess = null;
            _sharedWebService = false;
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            StopManagedProcessInternal();
            Mode = BackendConnectionMode.Disconnected;
        }
    }
}
