using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 原生 Win32 系统托盘管理器：不依赖 WinForms，支持显示窗口、按开关动态展示「拉起网页版」以及彻底退出程序
/// </summary>
public sealed class TrayIconManager : IDisposable
{
    private const int WmNull = 0x0000;
    private const int WmDestroy = 0x0002;
    private const int WmLButtonUp = 0x0202;
    private const int WmLButtonDblClk = 0x0203;
    private const int WmRButtonUp = 0x0205;
    private const int WmContextMenu = 0x007B;
    private const int WmApp = 0x8000;
    private const int WmTrayIcon = WmApp + 1;

    private const uint NimAdd = 0x00000000;
    private const uint NimModify = 0x00000001;
    private const uint NimDelete = 0x00000002;

    private const uint NifMessage = 0x00000001;
    private const uint NifIcon = 0x00000002;
    private const uint NifTip = 0x00000004;

    private const uint MfString = 0x00000000;
    private const uint MfSeparator = 0x00000800;

    private const uint TpmRightButton = 0x0002;
    private const uint TpmBottomAlign = 0x0020;
    private const uint TpmReturnCmd = 0x0100;

    private const uint ImageIcon = 1;
    private const uint LrLoadFromFile = 0x0010;
    private const uint LrDefaultSize = 0x0040;
    private static readonly IntPtr IdiApplication = new(32512);

    private const int SwHide = 0;
    private const int SwShow = 5;
    private const int SwRestore = 9;

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

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint cbSize;
        public IntPtr hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public uint uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PointStruct
    {
        public int X;
        public int Y;
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

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessageW(string lpString);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadImageW(IntPtr hInst, string name, uint type, int cx, int cy, uint fuLoad);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadIconW(IntPtr hInstance, IntPtr lpIconName);

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr hIcon);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIconW(uint dwMessage, [In] ref NotifyIconData lpData);

    [DllImport("user32.dll")]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool AppendMenuW(IntPtr hMenu, uint uFlags, UIntPtr uIDNewItem, string? lpNewItem);

    [DllImport("user32.dll")]
    private static extern uint TrackPopupMenuEx(IntPtr hMenu, uint uFlags, int x, int y, IntPtr hwnd, IntPtr lptpm);

    [DllImport("user32.dll")]
    private static extern bool DestroyMenu(IntPtr hMenu);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out PointStruct lpPoint);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool PostMessageW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr hWnd);

    private readonly Func<bool> _isWebServiceEnabled;
    private readonly Action _onShowWindow;
    private readonly Action _onLaunchWeb;
    private readonly Action _onExitApp;
    private readonly WndProcDelegate _wndProcDelegate;
    private readonly string _className;
    private readonly IntPtr _hInstance;
    private readonly uint _taskbarCreatedMsg;

    private IntPtr _hwnd;
    private IntPtr _hIcon;
    private bool _ownsIcon;
    private bool _disposed;
    private bool _menuOpen;

    public TrayIconManager(
        Func<bool> isWebServiceEnabled,
        Action onShowWindow,
        Action onLaunchWeb,
        Action onExitApp)
    {
        _isWebServiceEnabled = isWebServiceEnabled;
        _onShowWindow = onShowWindow;
        _onLaunchWeb = onLaunchWeb;
        _onExitApp = onExitApp;
        _wndProcDelegate = WndProc;
        _hInstance = GetModuleHandleW(null);
        _className = "OmniWinUITrayHost_" + Guid.NewGuid().ToString("N")[..8];
        _taskbarCreatedMsg = RegisterWindowMessageW("TaskbarCreated");

        CreateMessageWindow();
        LoadTrayIcon();
        AddOrUpdateNotifyIcon(isNew: true);
        LocalizationService.Instance.LanguageChanged += OnLanguageChanged;
    }

    private void CreateMessageWindow()
    {
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

        _hwnd = CreateWindowExW(0, _className, "OmniDownloaderTrayHost", 0, 0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, _hInstance, IntPtr.Zero);
        if (_hwnd == IntPtr.Zero)
        {
            UnregisterClassW(_className, _hInstance);
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }

    private void LoadTrayIcon()
    {
        var icoPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icoPath))
        {
            _hIcon = LoadImageW(IntPtr.Zero, icoPath, ImageIcon, 0, 0, LrLoadFromFile | LrDefaultSize);
            if (_hIcon != IntPtr.Zero)
            {
                _ownsIcon = true;
                return;
            }
        }
        _hIcon = LoadIconW(IntPtr.Zero, IdiApplication);
        _ownsIcon = false;
    }

    private void AddOrUpdateNotifyIcon(bool isNew)
    {
        if (_disposed || _hwnd == IntPtr.Zero) return;
        var tip = LocalizationService.Instance.T("OmniDownloader · 点击显示主窗口");
        if (tip.Length > 120) tip = tip[..120];

        var nid = new NotifyIconData
        {
            cbSize = (uint)Marshal.SizeOf<NotifyIconData>(),
            hWnd = _hwnd,
            uID = 1,
            uFlags = NifMessage | NifIcon | NifTip,
            uCallbackMessage = WmTrayIcon,
            hIcon = _hIcon,
            szTip = tip
        };

        Shell_NotifyIconW(isNew ? NimAdd : NimModify, ref nid);
    }

    private void OnLanguageChanged(object? sender, string e) => RefreshTooltip();

    public void RefreshTooltip() => AddOrUpdateNotifyIcon(isNew: false);

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == _taskbarCreatedMsg && _taskbarCreatedMsg != 0)
        {
            AddOrUpdateNotifyIcon(isNew: true);
            return IntPtr.Zero;
        }

        if (msg == WmTrayIcon)
        {
            var mouseMsg = (int)(lParam.ToInt64() & 0xFFFF);
            if (mouseMsg is WmRButtonUp or WmContextMenu)
            {
                ShowContextMenu();
                return IntPtr.Zero;
            }
            if (mouseMsg is WmLButtonUp or WmLButtonDblClk)
            {
                _onShowWindow();
                return IntPtr.Zero;
            }
        }

        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }

    private void ShowContextMenu()
    {
        if (_disposed || _hwnd == IntPtr.Zero || _menuOpen) return;
        _menuOpen = true;
        var hMenu = IntPtr.Zero;
        try
        {
            hMenu = CreatePopupMenu();
            if (hMenu == IntPtr.Zero) return;

            var items = TrayMenuCatalog.BuildMenuItems(
                _isWebServiceEnabled(),
                key => LocalizationService.Instance.T(key));

            foreach (var entry in items)
            {
                if (entry.IsSeparator)
                {
                    AppendMenuW(hMenu, MfSeparator, UIntPtr.Zero, null);
                }
                else
                {
                    AppendMenuW(hMenu, MfString, (UIntPtr)(uint)entry.Action, entry.Label);
                }
            }

            if (!GetCursorPos(out var pt)) return;
            SetForegroundWindow(_hwnd);
            var cmd = TrackPopupMenuEx(hMenu, TpmReturnCmd | TpmRightButton | TpmBottomAlign, pt.X, pt.Y, _hwnd, IntPtr.Zero);
            PostMessageW(_hwnd, WmNull, IntPtr.Zero, IntPtr.Zero);

            switch ((TrayMenuAction)cmd)
            {
                case TrayMenuAction.ShowWindow:
                    _onShowWindow();
                    break;
                case TrayMenuAction.LaunchWeb:
                    _onLaunchWeb();
                    break;
                case TrayMenuAction.ExitApp:
                    _onExitApp();
                    break;
            }
        }
        finally
        {
            if (hMenu != IntPtr.Zero) DestroyMenu(hMenu);
            _menuOpen = false;
        }
    }

    /// <summary>
    /// 将主窗口隐藏到系统托盘
    /// </summary>
    public static void HideToTray(Window? window)
    {
        if (window is null) return;
        try
        {
            window.AppWindow?.Hide();
        }
        catch
        {
            var hwnd = WindowNative.GetWindowHandle(window);
            if (hwnd != IntPtr.Zero) ShowWindow(hwnd, SwHide);
        }
    }

    /// <summary>
    /// 从系统托盘恢复并前置聚焦主窗口
    /// </summary>
    public static void RestoreWindow(Window? window)
    {
        if (window is null) return;
        try
        {
            var hwnd = WindowNative.GetWindowHandle(window);
            window.AppWindow?.Show(true);
            if (hwnd != IntPtr.Zero)
            {
                if (IsIconic(hwnd)) ShowWindow(hwnd, SwRestore);
                else ShowWindow(hwnd, SwShow);
                SetForegroundWindow(hwnd);
            }
            window.Activate();
        }
        catch
        {
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        LocalizationService.Instance.LanguageChanged -= OnLanguageChanged;

        if (_hwnd != IntPtr.Zero)
        {
            var nid = new NotifyIconData
            {
                cbSize = (uint)Marshal.SizeOf<NotifyIconData>(),
                hWnd = _hwnd,
                uID = 1
            };
            Shell_NotifyIconW(NimDelete, ref nid);
            DestroyWindow(_hwnd);
            UnregisterClassW(_className, _hInstance);
            _hwnd = IntPtr.Zero;
        }

        if (_ownsIcon && _hIcon != IntPtr.Zero)
        {
            DestroyIcon(_hIcon);
            _hIcon = IntPtr.Zero;
            _ownsIcon = false;
        }
    }
}
