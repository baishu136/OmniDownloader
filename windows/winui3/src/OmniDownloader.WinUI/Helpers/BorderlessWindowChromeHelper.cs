using System.Runtime.InteropServices;
using Microsoft.UI.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using Windows.UI;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 透明边缘拖拽调整手柄控件：在 Windows 10 无原生框线模式下提供 8 向光标提示与窗口缩放交互。
/// </summary>
public sealed class EdgeResizeGrip : Grid
{
    private InputSystemCursorShape _cursorShape = InputSystemCursorShape.Arrow;

    public EdgeResizeGrip()
    {
        Background = new SolidColorBrush(Color.FromArgb(0, 0, 0, 0));
    }

    public InputSystemCursorShape CursorShape
    {
        get => _cursorShape;
        set
        {
            if (_cursorShape != value)
            {
                _cursorShape = value;
                ProtectedCursor = InputSystemCursor.Create(value);
            }
        }
    }
}

/// <summary>
/// Windows 10 无边框无缝标题栏控制器：
/// 通过 OverlappedPresenter.SetBorderAndTitleBar(false, false) 彻底剥离会绘制顶部 1px 亮线的 Win10 DWM 非客户区标题边线，
/// 同时完整提供标题栏拖动、双击最大化/还原、工作区对齐最大化以及八方向窗口缩放体验。
/// </summary>
public sealed class BorderlessWindowChromeHelper
{
    public enum ResizeDirection
    {
        Left,
        Right,
        Top,
        Bottom,
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight
    }

    private const int MinWindowDipWidth = 600;
    private const int MinWindowDipHeight = 480;
    private const uint MonitorDefaultToNearest = 0x00000002;
    private const int DwmwaNcRenderingPolicy = 2;
    private const int DwmncrpEnabled = 2;
    private const int GwlStyle = -16;
    private const int GwlExStyle = -20;
    private const uint WsCaptionAndThickFrame = 0x00C40000;
    private const uint WsExWindowEdge = 0x00000100;
    private const uint WmNcCalcSize = 0x0083;
    private const uint WmNcDestroy = 0x0082;
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpFrameChanged = 0x0020;
    private static readonly UIntPtr BorderlessSubclassId = new(0x4F4D424C);

    private delegate IntPtr SubclassProcDelegate(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam, UIntPtr uIdSubclass, UIntPtr dwRefData);

    [StructLayout(LayoutKind.Sequential)]
    private struct NcCalcSizeParams
    {
        public Win32Rect Rgrc0;
        public Win32Rect Rgrc1;
        public Win32Rect Rgrc2;
        public IntPtr Lppos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MonitorInfo
    {
        public int CbSize;
        public Win32Rect RcMonitor;
        public Win32Rect RcWork;
        public uint DwFlags;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out Win32Point lpPoint);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MonitorInfo lpmi);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int dwAttribute, ref int pvAttribute, int cbAttribute);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(IntPtr hWnd);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(IntPtr hWnd, SubclassProcDelegate pfnSubclass, UIntPtr uIdSubclass, UIntPtr dwRefData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(IntPtr hWnd, SubclassProcDelegate pfnSubclass, UIntPtr uIdSubclass);

    [DllImport("comctl32.dll")]
    private static extern IntPtr DefSubclassProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam);

    private readonly Window _window;
    private readonly AppWindow _appWindow;
    private readonly IntPtr _hwnd;
    private readonly Action<bool>? _onMaximizedChanged;
    private Grid? _resizeLayer;
    private SubclassProcDelegate? _subclassProc;

    private bool _isCustomMaximized;
    private RectInt32 _restoredBounds;

    private bool _isDraggingTitle;
    private Win32Point _dragStartCursor;
    private PointInt32 _dragStartWindowPos;

    private bool _isResizing;
    private ResizeDirection _resizeDir;
    private Win32Point _resizeStartCursor;
    private RectInt32 _resizeStartBounds;

    public bool IsMaximized => _isCustomMaximized;

    public BorderlessWindowChromeHelper(
        Window window,
        FrameworkElement dragRegion,
        Grid resizeLayer,
        Action<bool>? onMaximizedChanged = null)
    {
        _window = window;
        _appWindow = window.AppWindow;
        _hwnd = WindowNative.GetWindowHandle(window);
        _resizeLayer = resizeLayer;
        _onMaximizedChanged = onMaximizedChanged;

        ConfigureBorderlessPresenter();
        AttachDragEvents(dragRegion);
        BuildResizeGrips(resizeLayer);

        _restoredBounds = new RectInt32(
            _appWindow.Position.X,
            _appWindow.Position.Y,
            _appWindow.Size.Width,
            _appWindow.Size.Height);
    }

    private void ConfigureBorderlessPresenter()
    {
        if (_appWindow.Presenter is OverlappedPresenter presenter)
        {
            // 关闭 Win10 DWM 原生框线与系统标题栏，从源头杜绝 1px 灰色/白色顶部细线
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsResizable = true;
            presenter.IsMinimizable = true;
            presenter.IsMaximizable = true;
        }

        if (_hwnd != IntPtr.Zero)
        {
            try
            {
                // 彻底清除残留的 WS_DLGFRAME / WS_THICKFRAME / WS_EX_WINDOWEDGE，并拦截 WM_NCCALCSIZE 让客户区 100% 铺满整个窗口（0px 边框）
                var style = unchecked((ulong)GetWindowLongPtr(_hwnd, GwlStyle).ToInt64());
                var strippedStyle = style & ~WsCaptionAndThickFrame;
                if (strippedStyle != style)
                {
                    _ = SetWindowLongPtr(_hwnd, GwlStyle, unchecked((IntPtr)(long)strippedStyle));
                }

                var exStyle = unchecked((ulong)GetWindowLongPtr(_hwnd, GwlExStyle).ToInt64());
                var strippedEx = exStyle & ~WsExWindowEdge;
                if (strippedEx != exStyle)
                {
                    _ = SetWindowLongPtr(_hwnd, GwlExStyle, unchecked((IntPtr)(long)strippedEx));
                }

                _subclassProc = BorderlessWindowProc;
                _ = SetWindowSubclass(_hwnd, _subclassProc, BorderlessSubclassId, UIntPtr.Zero);

                var policy = DwmncrpEnabled;
                _ = DwmSetWindowAttribute(_hwnd, DwmwaNcRenderingPolicy, ref policy, sizeof(int));
                _ = SetWindowPos(_hwnd, IntPtr.Zero, 0, 0, 0, 0, SwpNoMove | SwpNoSize | SwpNoZOrder | SwpNoActivate | SwpFrameChanged);
            }
            catch
            {
            }
        }
    }

    private IntPtr BorderlessWindowProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam, UIntPtr uId, UIntPtr dwRefData)
    {
        if (uMsg == WmNcCalcSize && wParam != IntPtr.Zero)
        {
            if (IsZoomed(hWnd))
            {
                var info = Marshal.PtrToStructure<NcCalcSizeParams>(lParam);
                info.Rgrc0 = GetCurrentWorkArea();
                Marshal.StructureToPtr(info, lParam, false);
            }
            return IntPtr.Zero;
        }

        if (uMsg == WmNcDestroy && _subclassProc is not null)
        {
            var proc = _subclassProc;
            _subclassProc = null;
            try { RemoveWindowSubclass(hWnd, proc, uId); } catch { }
        }

        return DefSubclassProc(hWnd, uMsg, wParam, lParam);
    }

    private void AttachDragEvents(FrameworkElement dragRegion)
    {
        dragRegion.PointerPressed += (_, e) =>
        {
            var pt = e.GetCurrentPoint(dragRegion);
            if (!pt.Properties.IsLeftButtonPressed) return;
            if (IsInteractiveAncestor(e.OriginalSource as DependencyObject, dragRegion)) return;
            if (!GetCursorPos(out var cur)) return;

            _isDraggingTitle = true;
            _dragStartCursor = cur;
            _dragStartWindowPos = _appWindow.Position;
            try { dragRegion.CapturePointer(e.Pointer); } catch { }
        };

        dragRegion.PointerMoved += (_, e) =>
        {
            if (!_isDraggingTitle) return;
            var pt = e.GetCurrentPoint(dragRegion);
            if (!pt.Properties.IsLeftButtonPressed)
            {
                EndTitleDrag(dragRegion);
                return;
            }
            if (!GetCursorPos(out var cur)) return;

            var dx = cur.X - _dragStartCursor.X;
            var dy = cur.Y - _dragStartCursor.Y;

            if (_isCustomMaximized)
            {
                if (Math.Abs(dx) < 6 && Math.Abs(dy) < 6) return;
                // 最大化状态下拖拽标题栏，平滑还原并居中跟随鼠标
                var targetW = _restoredBounds.Width > 200 ? _restoredBounds.Width : 1100;
                var targetH = _restoredBounds.Height > 200 ? _restoredBounds.Height : 760;
                var work = GetCurrentWorkArea();
                var ratio = Math.Clamp((cur.X - work.Left) / (double)Math.Max(1, work.Right - work.Left), 0.15, 0.85);
                var newX = cur.X - (int)(targetW * ratio);
                var newY = cur.Y - 18;
                SetMaximizedVisualState(false);
                _appWindow.MoveAndResize(new RectInt32(newX, newY, targetW, targetH));
                _dragStartCursor = cur;
                _dragStartWindowPos = new PointInt32(newX, newY);
                return;
            }

            _appWindow.Move(new PointInt32(_dragStartWindowPos.X + dx, _dragStartWindowPos.Y + dy));
        };

        dragRegion.PointerReleased += (_, _) => EndTitleDrag(dragRegion);
        dragRegion.PointerCaptureLost += (_, _) => EndTitleDrag(dragRegion);
        dragRegion.DoubleTapped += (_, e) =>
        {
            if (IsInteractiveAncestor(e.OriginalSource as DependencyObject, dragRegion)) return;
            ToggleMaximizeRestore();
        };
    }

    private static bool IsInteractiveAncestor(DependencyObject? leaf, DependencyObject stopAt)
    {
        var cur = leaf;
        while (cur is not null && !ReferenceEquals(cur, stopAt))
        {
            if (cur is ButtonBase) return true;
            cur = VisualTreeHelper.GetParent(cur);
        }
        return false;
    }

    private void EndTitleDrag(FrameworkElement dragRegion)
    {
        if (!_isDraggingTitle) return;
        _isDraggingTitle = false;
        try { dragRegion.ReleasePointerCaptures(); } catch { }
        if (!_isCustomMaximized)
        {
            CaptureCurrentRestoredBounds();
        }
    }

    private void BuildResizeGrips(Grid layer)
    {
        layer.Children.Clear();

        void AddGrip(ResizeDirection dir, InputSystemCursorShape shape, HorizontalAlignment hAlign, VerticalAlignment vAlign, double width, double height, Thickness margin = default)
        {
            var grip = new EdgeResizeGrip
            {
                CursorShape = shape,
                HorizontalAlignment = hAlign,
                VerticalAlignment = vAlign,
                Margin = margin
            };
            if (!double.IsNaN(width)) grip.Width = width;
            if (!double.IsNaN(height)) grip.Height = height;

            grip.PointerPressed += (_, e) =>
            {
                if (_isCustomMaximized) return;
                var pt = e.GetCurrentPoint(grip);
                if (!pt.Properties.IsLeftButtonPressed) return;
                if (!GetCursorPos(out var cur)) return;
                _isResizing = true;
                _resizeDir = dir;
                _resizeStartCursor = cur;
                _resizeStartBounds = new RectInt32(
                    _appWindow.Position.X,
                    _appWindow.Position.Y,
                    _appWindow.Size.Width,
                    _appWindow.Size.Height);
                try { grip.CapturePointer(e.Pointer); } catch { }
            };

            grip.PointerMoved += (_, e) =>
            {
                if (!_isResizing || _isCustomMaximized) return;
                var pt = e.GetCurrentPoint(grip);
                if (!pt.Properties.IsLeftButtonPressed)
                {
                    EndResize(grip);
                    return;
                }
                if (!GetCursorPos(out var cur)) return;
                ApplyResizeDelta(cur.X - _resizeStartCursor.X, cur.Y - _resizeStartCursor.Y);
            };

            grip.PointerReleased += (_, _) => EndResize(grip);
            grip.PointerCaptureLost += (_, _) => EndResize(grip);
            layer.Children.Add(grip);
        }

        // 四条边与四个角均保留 4~10px 命中宽度；右上角控制按钮区域让行（Fitts 定律），确保顶格点击关闭按钮不被截获
        AddGrip(ResizeDirection.Left, InputSystemCursorShape.SizeWestEast, HorizontalAlignment.Left, VerticalAlignment.Stretch, 5, double.NaN);
        AddGrip(ResizeDirection.Right, InputSystemCursorShape.SizeWestEast, HorizontalAlignment.Right, VerticalAlignment.Stretch, 5, double.NaN, new Thickness(0, 38, 0, 0));
        AddGrip(ResizeDirection.Top, InputSystemCursorShape.SizeNorthSouth, HorizontalAlignment.Stretch, VerticalAlignment.Top, double.NaN, 4, new Thickness(8, 0, 140, 0));
        AddGrip(ResizeDirection.Bottom, InputSystemCursorShape.SizeNorthSouth, HorizontalAlignment.Stretch, VerticalAlignment.Bottom, double.NaN, 5);

        AddGrip(ResizeDirection.TopLeft, InputSystemCursorShape.SizeNorthwestSoutheast, HorizontalAlignment.Left, VerticalAlignment.Top, 10, 10);
        AddGrip(ResizeDirection.BottomLeft, InputSystemCursorShape.SizeNortheastSouthwest, HorizontalAlignment.Left, VerticalAlignment.Bottom, 10, 10);
        AddGrip(ResizeDirection.BottomRight, InputSystemCursorShape.SizeNorthwestSoutheast, HorizontalAlignment.Right, VerticalAlignment.Bottom, 10, 10);

        layer.Visibility = Visibility.Visible;
    }

    private void EndResize(EdgeResizeGrip grip)
    {
        if (!_isResizing) return;
        _isResizing = false;
        try { grip.ReleasePointerCaptures(); } catch { }
        CaptureCurrentRestoredBounds();
    }

    private void ApplyResizeDelta(int dx, int dy)
    {
        var dpiScale = _hwnd != IntPtr.Zero ? GetDpiForWindow(_hwnd) / 96.0 : 1.0;
        var minW = (int)Math.Round(MinWindowDipWidth * Math.Max(1.0, dpiScale));
        var minH = (int)Math.Round(MinWindowDipHeight * Math.Max(1.0, dpiScale));

        var left = _resizeStartBounds.X;
        var top = _resizeStartBounds.Y;
        var right = left + _resizeStartBounds.Width;
        var bottom = top + _resizeStartBounds.Height;

        if (_resizeDir is ResizeDirection.Left or ResizeDirection.TopLeft or ResizeDirection.BottomLeft)
        {
            left = Math.Min(right - minW, left + dx);
        }
        if (_resizeDir is ResizeDirection.Right or ResizeDirection.TopRight or ResizeDirection.BottomRight)
        {
            right = Math.Max(left + minW, right + dx);
        }
        if (_resizeDir is ResizeDirection.Top or ResizeDirection.TopLeft or ResizeDirection.TopRight)
        {
            top = Math.Min(bottom - minH, top + dy);
        }
        if (_resizeDir is ResizeDirection.Bottom or ResizeDirection.BottomLeft or ResizeDirection.BottomRight)
        {
            bottom = Math.Max(top + minH, bottom + dy);
        }

        _appWindow.MoveAndResize(new RectInt32(left, top, right - left, bottom - top));
    }

    public void Minimize()
    {
        if (_appWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.Minimize();
        }
    }

    public void ToggleMaximizeRestore()
    {
        if (_isCustomMaximized)
        {
            var bounds = _restoredBounds;
            SetMaximizedVisualState(false);
            if (bounds.Width >= 200 && bounds.Height >= 200)
            {
                _appWindow.MoveAndResize(bounds);
            }
            return;
        }

        CaptureCurrentRestoredBounds();
        var work = GetCurrentWorkArea();
        var workW = Math.Max(400, work.Right - work.Left);
        var workH = Math.Max(300, work.Bottom - work.Top);
        SetMaximizedVisualState(true);
        _appWindow.MoveAndResize(new RectInt32(work.Left, work.Top, workW, workH));
    }

    private void CaptureCurrentRestoredBounds()
    {
        _restoredBounds = new RectInt32(
            _appWindow.Position.X,
            _appWindow.Position.Y,
            _appWindow.Size.Width,
            _appWindow.Size.Height);
    }

    private void SetMaximizedVisualState(bool maximized)
    {
        _isCustomMaximized = maximized;
        if (_resizeLayer is not null)
        {
            _resizeLayer.Visibility = maximized ? Visibility.Collapsed : Visibility.Visible;
        }
        _onMaximizedChanged?.Invoke(maximized);
    }

    private Win32Rect GetCurrentWorkArea()
    {
        var mi = new MonitorInfo { CbSize = Marshal.SizeOf<MonitorInfo>() };
        var monitor = MonitorFromWindow(_hwnd, MonitorDefaultToNearest);
        if (monitor != IntPtr.Zero && GetMonitorInfo(monitor, ref mi))
        {
            return mi.RcWork;
        }
        return new Win32Rect { Left = 0, Top = 0, Right = 1920, Bottom = 1040 };
    }
}
