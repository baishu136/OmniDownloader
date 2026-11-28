using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Services;
using Windows.Foundation;
using WinRT.Interop;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 共享文件剪贴板通知与 OLE 跨应用拖拽阈值控制器（供任务页与首页最近任务共用）
/// </summary>
public static class FileTransferUiHelper
{
    public static IntPtr GetOwnerWindowHandle(AppServices services)
    {
        var owner = services.Dialogs.OwnerWindow;
        return owner is null ? IntPtr.Zero : WindowNative.GetWindowHandle(owner);
    }

    public static void CopyTasksFilesWithNotice(AppServices services, IReadOnlyList<DownloadTask> tasks)
    {
        var result = NativeFileTransferService.CopyTaskFilesToClipboard(tasks, GetOwnerWindowHandle(services));
        if (!result.Success)
        {
            services.MainVm.Notify(
                string.IsNullOrWhiteSpace(result.ErrorMessage) ? "本地文件不存在或已被移动" : result.ErrorMessage,
                InfoBarSeverity.Warning);
            return;
        }

        if (result.CopiedCount == 1 && result.MissingCount == 0)
        {
            services.MainVm.Notify("已复制文件到剪贴板，可直接粘贴至其他应用", InfoBarSeverity.Success);
        }
        else if (result.MissingCount > 0)
        {
            services.MainVm.Notify("已复制 %d 个文件到剪贴板（另有 %d 个文件已缺失）", InfoBarSeverity.Success, result.CopiedCount, result.MissingCount);
        }
        else
        {
            services.MainVm.Notify("已复制 %d 个文件到剪贴板，可直接粘贴至其他应用", InfoBarSeverity.Success, result.CopiedCount);
        }
    }

    public static void CopyTasksPathsWithNotice(AppServices services, IReadOnlyList<DownloadTask> tasks)
    {
        var result = NativeFileTransferService.CopyTaskFilePathsToClipboard(tasks, GetOwnerWindowHandle(services));
        if (!result.Success)
        {
            services.MainVm.Notify(
                string.IsNullOrWhiteSpace(result.ErrorMessage) ? "未找到有效的本地文件路径" : result.ErrorMessage,
                InfoBarSeverity.Warning);
            return;
        }

        if (result.CopiedCount <= 1)
        {
            services.MainVm.Notify("已复制文件路径到剪贴板", InfoBarSeverity.Informational);
        }
        else
        {
            services.MainVm.Notify("已复制 %d 条文件路径到剪贴板", InfoBarSeverity.Informational, result.CopiedCount);
        }
    }
}

/// <summary>
/// 轻量鼠标拖拽阈值状态机：区分点击与拖出，跨越系统 SM_CXDRAG/SM_CYDRAG 后再释放捕获并调用原生 DoDragDrop。
/// </summary>
public sealed class OleDragThresholdController
{
    private Point _dragStartPoint;
    private FrameworkElement? _dragSourceElement;
    private IReadOnlyList<DownloadTask> _pendingDragTasks = Array.Empty<DownloadTask>();
    private bool _isDragPending;
    private bool _isOleDragRunning;

    public bool TryArm(FrameworkElement hostElement, PointerRoutedEventArgs e, IReadOnlyList<DownloadTask> tasks)
    {
        if (_isOleDragRunning || tasks.Count == 0) return false;
        if (e.Pointer.PointerDeviceType != PointerDeviceType.Mouse) return false;
        var pt = e.GetCurrentPoint(null);
        if (!pt.Properties.IsLeftButtonPressed) return false;
        if (IsInteractiveAncestor(e.OriginalSource as DependencyObject, hostElement)) return false;

        _dragSourceElement = hostElement;
        _dragStartPoint = pt.Position;
        _pendingDragTasks = tasks;
        _isDragPending = true;
        try { hostElement.CapturePointer(e.Pointer); } catch { }
        return true;
    }

    public void HandlePointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!_isDragPending || _isOleDragRunning || !ReferenceEquals(sender, _dragSourceElement)) return;
        var pt = e.GetCurrentPoint(null);
        if (!pt.Properties.IsLeftButtonPressed)
        {
            Reset();
            return;
        }

        var thresholdX = Math.Max(4, NativeFileTransferService.GetSystemMetrics(68));
        var thresholdY = Math.Max(4, NativeFileTransferService.GetSystemMetrics(69));
        if (Math.Abs(pt.Position.X - _dragStartPoint.X) < thresholdX &&
            Math.Abs(pt.Position.Y - _dragStartPoint.Y) < thresholdY)
        {
            return;
        }

        var tasks = _pendingDragTasks;
        var dragEl = _dragSourceElement;
        _isDragPending = false;
        _isOleDragRunning = true;
        try { dragEl?.ReleasePointerCaptures(); } catch { }

        try
        {
            var result = NativeFileTransferService.BeginOleFileDragDrop(tasks);
            if (!result.Success && !string.IsNullOrWhiteSpace(result.ErrorMessage))
            {
                AppServices.Current.MainVm.Notify(result.ErrorMessage, InfoBarSeverity.Warning);
            }
        }
        finally
        {
            Reset();
        }
    }

    public void HandlePointerEnded(object sender)
    {
        if (ReferenceEquals(sender, _dragSourceElement) && !_isOleDragRunning)
        {
            Reset();
        }
    }

    public void Reset()
    {
        _isDragPending = false;
        _isOleDragRunning = false;
        _pendingDragTasks = Array.Empty<DownloadTask>();
        var prev = _dragSourceElement;
        _dragSourceElement = null;
        if (prev is not null)
        {
            try { prev.ReleasePointerCaptures(); } catch { }
        }
    }

    private static bool IsInteractiveAncestor(DependencyObject? leaf, DependencyObject stopAt)
    {
        var cur = leaf;
        while (cur is not null && !ReferenceEquals(cur, stopAt))
        {
            if (cur is ButtonBase or ScrollBar or SelectorBarItem)
            {
                return true;
            }
            cur = VisualTreeHelper.GetParent(cur);
        }
        return false;
    }
}
