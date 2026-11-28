using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 共享 WebView2 运行环境管理器：
/// 复用统一 UserDataFolder 降低多窗口 Chromium 浏览器进程内存与冷启动开销，
/// 按硬件加速开关显式配置 GPU 光栅化与零拷贝合成（异常时自动安全回退软件渲染）。
/// </summary>
public static class WebViewEnvironmentHelper
{
    private static readonly SemaphoreSlim EnvLock = new(1, 1);
    private static CoreWebView2Environment? _gpuEnv;
    private static CoreWebView2Environment? _swEnv;

    private static string ResolveWebViewRootDir()
    {
        var stateDir = Environment.GetEnvironmentVariable("OMNI_STATE_DIR");
        var baseDir = !string.IsNullOrWhiteSpace(stateDir)
            ? stateDir
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniDownloader");
        return Path.Combine(baseDir, "WebView2");
    }

    public static async Task<CoreWebView2Environment> GetOrCreateSharedEnvironmentAsync(bool enableGpu)
    {
        var cached = enableGpu ? _gpuEnv : _swEnv;
        if (cached is not null)
        {
            return cached;
        }

        await EnvLock.WaitAsync().ConfigureAwait(false);
        try
        {
            cached = enableGpu ? _gpuEnv : _swEnv;
            if (cached is not null)
            {
                return cached;
            }

            var root = ResolveWebViewRootDir();
            var dataDir = Path.Combine(root, enableGpu ? "Shared" : "SharedSw");
            Directory.CreateDirectory(dataDir);

            CoreWebView2Environment env;
            if (enableGpu)
            {
                try
                {
                    var opts = new CoreWebView2EnvironmentOptions
                    {
                        AdditionalBrowserArguments = "--enable-gpu-rasterization --enable-zero-copy --ignore-gpu-blocklist"
                    };
                    env = await CoreWebView2Environment.CreateWithOptionsAsync(null, dataDir, opts);
                }
                catch
                {
                    // 驱动或策略受限时自动回退默认环境，避免阻塞辅助浏览器与登录窗口
                    env = await CoreWebView2Environment.CreateWithOptionsAsync(null, dataDir, null);
                }
                _gpuEnv = env;
            }
            else
            {
                var opts = new CoreWebView2EnvironmentOptions
                {
                    AdditionalBrowserArguments = "--disable-gpu"
                };
                env = await CoreWebView2Environment.CreateWithOptionsAsync(null, dataDir, opts);
                _swEnv = env;
            }

            return env;
        }
        finally
        {
            EnvLock.Release();
        }
    }

    public static async Task EnsureInitializedAsync(WebView2 webView, AppServices? services = null)
    {
        var enableGpu = services?.LocalState.Current.GpuAccelerationEnabled ?? true;
        var env = await GetOrCreateSharedEnvironmentAsync(enableGpu);
        await webView.EnsureCoreWebView2Async(env);
    }
}
