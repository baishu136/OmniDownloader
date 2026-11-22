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
/// 若未运行或既有的外部服务版本过低（缺少浏览器登录等 1.7.59+ 接口）且本地存在新版引擎时，
/// 自动拉起仓库 omni_server.py（并强制统一 OMNI_STATE_DIR 共享配置与凭据）并在退出时安全清理子进程。
/// </summary>
public sealed class BackendProcessManager : IDisposable
{
    public static readonly Version MinimumRequiredBackendVersion = new(1, 7, 59);

    private Process? _managedProcess;
    private readonly object _lock = new();

    public BackendConnectionMode Mode { get; private set; } = BackendConnectionMode.Disconnected;
    public string ActiveBaseUrl { get; private set; } = "http://127.0.0.1:58000";
    public string ConnectedVersion { get; private set; } = string.Empty;
    public bool IsOutdatedBackend { get; private set; }
    public string LastStatusNote { get; private set; } = string.Empty;

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
                LastStatusNote = compatible
                    ? (isManaged ? $"已启动本地伴随引擎 ({ActiveBaseUrl})" : $"已连接本机运行服务 ({ActiveBaseUrl})")
                    : $"已连接旧版服务 v{(string.IsNullOrWhiteSpace(probe.Version) ? "未知" : probe.Version)} (缺少 1.7.59+ 登录接口)";
                return true;
            }

            // 既有外部本机服务版本过旧：若本机仓库有新版源码核心，则自动于相邻端口拉起新版伴随引擎，避开 404
            var upgradeLayout = LocateServerAndPython();
            var localVersion = upgradeLayout is null ? string.Empty : ReadLocalScriptVersion(upgradeLayout.Value.WindowsDir);
            if (upgradeLayout is null || !IsVersionAtLeast(localVersion, MinimumRequiredBackendVersion))
            {
                Mode = BackendConnectionMode.ExternalService;
                IsOutdatedBackend = true;
                LastStatusNote = $"已连接旧版服务 v{probe.Version} ({ActiveBaseUrl} · 请升级服务)";
                return true;
            }

            var upgradePort = FindAvailablePort(TryExtractPort(ActiveBaseUrl, 58000) + 1);
            if (await StartManagedServerAsync(apiClient, upgradeLayout.Value, upgradePort, ct).ConfigureAwait(false))
            {
                IsOutdatedBackend = false;
                LastStatusNote = $"已启新版伴随核心 ({ActiveBaseUrl} · 原服务 v{probe.Version} 过旧)";
                return true;
            }

            // 伴随启动意外失败时回退连接原端口，不阻塞界面使用
            apiClient.UpdateBaseUrl(targetUrl);
            ActiveBaseUrl = apiClient.BaseUrl;
            Mode = BackendConnectionMode.ExternalService;
            IsOutdatedBackend = true;
            LastStatusNote = $"已连接旧版服务 v{probe.Version} ({ActiveBaseUrl})";
            return true;
        }

        if (!autoStartIfOffline || !IsLocalLoopbackUrl(ActiveBaseUrl))
        {
            Mode = BackendConnectionMode.Disconnected;
            LastStatusNote = "未检测到运行中的本地服务";
            return false;
        }

        // 2. 自动搜寻本地 windows/omni_server.py + Python，或同级 OmniDownloader_Web.exe 并伴随启动
        var layout = LocateServerAndPython();
        var port = FindAvailablePort(TryExtractPort(ActiveBaseUrl, 58000));
        if (layout is not null)
        {
            return await StartManagedServerAsync(apiClient, layout.Value, port, ct).ConfigureAwait(false);
        }

        var bundledWeb = LocateSiblingWebExe();
        if (bundledWeb is not null)
        {
            return await StartBundledWebServerAsync(apiClient, bundledWeb, port, ct).ConfigureAwait(false);
        }

        Mode = BackendConnectionMode.Disconnected;
        LastStatusNote = "未在本机找到 omni_server.py 或配套服务程序";
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
                _managedProcess = Process.Start(psi);
            }
        }
        catch (Exception ex)
        {
            Mode = BackendConnectionMode.Disconnected;
            LastStatusNote = $"启动伴随 Python 服务失败 ({ex.Message})";
            return false;
        }

        if (!HasRunningManagedProcess() && !TryGetManagedExitCode(out _))
        {
            Mode = BackendConnectionMode.Disconnected;
            LastStatusNote = "未能拉起伴随 Python 服务进程";
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
                    Arguments = "--silent",
                    WorkingDirectory = Path.GetDirectoryName(webExePath) ?? AppContext.BaseDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                };
                psi.Environment["OMNI_PORT"] = port.ToString();
                psi.Environment["OMNI_STATE_DIR"] = stateDir;
                _managedProcess = Process.Start(psi);
            }
        }
        catch (Exception ex)
        {
            Mode = BackendConnectionMode.Disconnected;
            LastStatusNote = $"启动内置服务程序失败 ({ex.Message})";
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
                LastStatusNote = $"伴随服务启动后提前退出 (Code {exitCode})";
                return false;
            }
            var probe = await TryProbeBackendAsync(apiClient, 1200, ct).ConfigureAwait(false);
            if (probe.Reachable)
            {
                Mode = BackendConnectionMode.ManagedBundledProcess;
                ConnectedVersion = probe.Version;
                IsOutdatedBackend = !IsVersionAtLeast(probe.Version, MinimumRequiredBackendVersion);
                LastStatusNote = $"已启动本地伴随引擎 ({ActiveBaseUrl})";
                return true;
            }
            var delayMs = i < 8 ? 100 : (i < 20 ? 150 : 250);
            await Task.Delay(delayMs, ct).ConfigureAwait(false);
        }

        Mode = BackendConnectionMode.Disconnected;
        LastStatusNote = "等待伴随引擎就绪超时";
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

    private void StopManagedProcessInternal()
    {
        if (_managedProcess is null) return;
        try
        {
            if (!_managedProcess.HasExited)
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
