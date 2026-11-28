using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.UI.Dispatching;
using Windows.ApplicationModel.DataTransfer;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 原生 Windows 系统剪贴板事件监听器：
/// 基于 user32 AddClipboardFormatListener + WM_CLIPBOARDUPDATE 实现零轮询监听，
/// 内置防抖、重复链接去重窗、非文本/文件剪贴板豁免、轻正则预过滤，绝不干扰正常复制与 CF_HDROP 文件传递。
/// </summary>
public sealed class ClipboardWatcherService : IDisposable
{
    private const uint WmClipboardUpdate = 0x031D;
    private const int MaxCandidateTextLength = 24000;
    private static readonly Regex CandidateUrlHintRegex = new(
        @"https?://[^\s<>""']{4,}|\bBV[A-Za-z0-9]{10}\b",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint cbSize;
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandleW(string? lpModuleName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassExW([In] ref WndClassEx lpWndClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool UnregisterClassW(string lpClassName, IntPtr hInstance);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowExW(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    private readonly DispatcherQueue _dispatcher;
    private readonly Func<string, Task> _onCandidateLinksDetected;
    private readonly WndProcDelegate _wndProcDelegate;
    private readonly IntPtr _hInstance;
    private readonly string _className;
    private readonly object _gate = new();

    private IntPtr _hwnd;
    private bool _listening;
    private bool _enabled;
    private bool _paused;
    private bool _disposed;
    private int _debounceMs = 550;
    private int _dedupWindowSec = 20;
    private uint _lastSequenceNumber;
    private CancellationTokenSource? _debounceCts;

    private string _lastSubmittedHash = string.Empty;
    private long _lastSubmittedTickMs;

    public bool IsEnabled => _enabled;
    public bool IsPaused => _paused;
    public bool IsActivelyListening => _enabled && !_paused && _listening;

    public ClipboardWatcherService(DispatcherQueue dispatcher, Func<string, Task> onCandidateLinksDetected)
    {
        _dispatcher = dispatcher;
        _onCandidateLinksDetected = onCandidateLinksDetected;
        _wndProcDelegate = WndProc;
        _hInstance = GetModuleHandleW(null);
        _className = "OmniClipWatch_" + Guid.NewGuid().ToString("N")[..8];
    }

    public void UpdatePolicy(bool enabled, bool paused, int debounceMs = 550, int dedupWindowSec = 20)
    {
        if (_disposed) return;
        _enabled = enabled;
        _paused = paused;
        _debounceMs = Math.Clamp(debounceMs, 200, 5000);
        _dedupWindowSec = Math.Clamp(dedupWindowSec, 3, 3600);

        if (_enabled && !_paused)
        {
            StartListening();
        }
        else
        {
            StopListening();
        }
    }

    /// <summary>
    /// 主动标记一段刚由应用内部复制/触发的文本，避免回声误触发。
    /// </summary>
    public void SuppressEcho(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        lock (_gate)
        {
            _lastSubmittedHash = ComputeSha256(text.Trim());
            _lastSubmittedTickMs = Environment.TickCount64;
        }
    }

    private void StartListening()
    {
        if (_disposed || _listening) return;
        EnsureMessageWindow();
        if (_hwnd != IntPtr.Zero)
        {
            _lastSequenceNumber = GetClipboardSequenceNumber();
            _listening = AddClipboardFormatListener(_hwnd);
        }
    }

    private void StopListening()
    {
        lock (_gate)
        {
            try
            {
                _debounceCts?.Cancel();
                _debounceCts?.Dispose();
                _debounceCts = null;
            }
            catch
            {
            }
        }
        if (_listening && _hwnd != IntPtr.Zero)
        {
            try { RemoveClipboardFormatListener(_hwnd); } catch { }
            _listening = false;
        }
    }

    private void EnsureMessageWindow()
    {
        if (_hwnd != IntPtr.Zero || _disposed) return;
        var wc = new WndClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WndClassEx>(),
            lpfnWndProc = _wndProcDelegate,
            hInstance = _hInstance,
            lpszClassName = _className
        };
        var atom = RegisterClassExW(ref wc);
        if (atom == 0)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        // Message-only 隐藏宿主窗口（HWND_MESSAGE = -3），不创建可见表面也不抢焦点
        _hwnd = CreateWindowExW(0, _className, "OmniClipboardHost", 0, 0, 0, 0, 0, new IntPtr(-3), IntPtr.Zero, _hInstance, IntPtr.Zero);
        if (_hwnd == IntPtr.Zero)
        {
            UnregisterClassW(_className, _hInstance);
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WmClipboardUpdate && _enabled && !_paused && !_disposed)
        {
            var seq = GetClipboardSequenceNumber();
            if (seq != 0 && seq == _lastSequenceNumber)
            {
                return IntPtr.Zero;
            }
            _lastSequenceNumber = seq;
            ScheduleDebouncedCheck();
            return IntPtr.Zero;
        }
        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }

    private void ScheduleDebouncedCheck()
    {
        CancellationTokenSource cts;
        var delay = _debounceMs;
        lock (_gate)
        {
            try
            {
                _debounceCts?.Cancel();
                _debounceCts?.Dispose();
            }
            catch
            {
            }
            cts = new CancellationTokenSource();
            _debounceCts = cts;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(delay, cts.Token).ConfigureAwait(false);
                if (cts.IsCancellationRequested || !_enabled || _paused || _disposed) return;
                _dispatcher.TryEnqueue(async () =>
                {
                    if (cts.IsCancellationRequested || !_enabled || _paused || _disposed) return;
                    await ReadAndProcessClipboardAsync().ConfigureAwait(false);
                });
            }
            catch (OperationCanceledException)
            {
            }
            catch
            {
            }
        }, cts.Token);
    }

    private async Task ReadAndProcessClipboardAsync()
    {
        string text;
        try
        {
            var view = Clipboard.GetContent();
            // 仅读取轻量文本；遇到文件复制（CF_HDROP / StorageItems）或无文本则立刻跳过，绝不干扰文件剪贴板
            if (view is null || view.Contains(StandardDataFormats.StorageItems) || !view.Contains(StandardDataFormats.Text))
            {
                return;
            }
            text = (await view.GetTextAsync()).Trim();
        }
        catch
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(text) || text.Length > MaxCandidateTextLength)
        {
            return;
        }

        // 本地轻正则预检：无链接特征时零后台请求、零日志记录直接忽略
        if (!CandidateUrlHintRegex.IsMatch(text))
        {
            return;
        }

        var hash = ComputeSha256(text);
        var now = Environment.TickCount64;
        var dedupWindowMs = _dedupWindowSec * 1000L;
        lock (_gate)
        {
            if (string.Equals(hash, _lastSubmittedHash, StringComparison.Ordinal) &&
                now - _lastSubmittedTickMs < dedupWindowMs)
            {
                return;
            }
            _lastSubmittedHash = hash;
            _lastSubmittedTickMs = now;
        }

        try
        {
            await _onCandidateLinksDetected(text).ConfigureAwait(false);
        }
        catch
        {
        }
    }

    private static string ComputeSha256(string input)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(input));
        return Convert.ToHexString(bytes);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        StopListening();
        if (_hwnd != IntPtr.Zero)
        {
            try { DestroyWindow(_hwnd); } catch { }
            try { UnregisterClassW(_className, _hInstance); } catch { }
            _hwnd = IntPtr.Zero;
        }
    }
}
