using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Helpers;

namespace OmniDownloader.WinUI.Services;

/// <summary>
/// 统一托管主窗口句柄与 ContentDialog XamlRoot：
/// 使用异步互斥锁严格串行化弹窗展示，杜绝 WinUI 3 多重 ContentDialog 并发导致的 COM 闪退。
/// </summary>
public sealed class DialogService : IDisposable
{
    private readonly SemaphoreSlim _dialogGate = new(1, 1);
    private Window? _owner;
    private DispatcherQueue? _dispatcher;
    private volatile bool _disposed;

    public Window? OwnerWindow => _owner;

    public void Attach(Window owner)
    {
        _owner = owner;
        _dispatcher = owner?.DispatcherQueue;
    }

    private XamlRoot? TryGetXamlRoot() => _disposed ? null : _owner?.Content?.XamlRoot;

    private static void PrepareDialog(ContentDialog dialog, Window? owner, XamlRoot xamlRoot)
    {
        dialog.XamlRoot = xamlRoot;
        if (owner?.Content is FrameworkElement root)
        {
            dialog.RequestedTheme = root.ActualTheme;
        }
    }

    private async Task<ContentDialogResult> ShowSerialOnUiAsync(Func<ContentDialog> factory)
    {
        if (_disposed || _dispatcher is null) return ContentDialogResult.None;

        await _dialogGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_disposed || _dispatcher is null) return ContentDialogResult.None;
            var tcs = new TaskCompletionSource<ContentDialogResult>(TaskCreationOptions.RunContinuationsAsynchronously);

            var enqueued = _dispatcher.TryEnqueue(async () =>
            {
                try
                {
                    var xamlRoot = TryGetXamlRoot();
                    if (xamlRoot is null)
                    {
                        tcs.TrySetResult(ContentDialogResult.None);
                        return;
                    }
                    var dialog = factory();
                    PrepareDialog(dialog, _owner, xamlRoot);
                    var res = await dialog.ShowAsync();
                    tcs.TrySetResult(res);
                }
                catch
                {
                    tcs.TrySetResult(ContentDialogResult.None);
                }
            });

            if (!enqueued) return ContentDialogResult.None;
            return await tcs.Task.ConfigureAwait(false);
        }
        finally
        {
            try { _dialogGate.Release(); } catch { }
        }
    }

    public async Task<bool> ConfirmAsync(
        string title,
        string message,
        string primaryText = "确认",
        string closeText = "取消",
        bool destructive = false)
    {
        var result = await ShowSerialOnUiAsync(() => new ContentDialog
        {
            Title = title,
            Content = new TextBlock
            {
                Text = message,
                TextWrapping = TextWrapping.WrapWholeWords
            },
            PrimaryButtonText = primaryText,
            CloseButtonText = closeText,
            DefaultButton = destructive ? ContentDialogButton.Close : ContentDialogButton.Primary
        });
        return result == ContentDialogResult.Primary;
    }

    public async Task ShowMessageAsync(string title, string message, string closeText = "关闭")
    {
        await ShowSerialOnUiAsync(() => new ContentDialog
        {
            Title = title,
            Content = new ScrollViewer
            {
                MaxHeight = 360,
                Content = new TextBlock
                {
                    Text = message,
                    TextWrapping = TextWrapping.Wrap,
                    IsTextSelectionEnabled = true
                }
            },
            CloseButtonText = closeText
        });
    }

    /// <summary>
    /// 展示完整错误日志详情弹窗，支持一键复制与跳转设置修复（账号/网络）
    /// </summary>
    public async Task ShowErrorDetailAsync(
        string title,
        string detail,
        string? settingsButtonText = null,
        Action? onNavigateSettings = null)
    {
        var res = await ShowSerialOnUiAsync(() =>
        {
            var stack = new StackPanel { Spacing = 10 };
            stack.Children.Add(new TextBlock
            {
                Text = detail,
                TextWrapping = TextWrapping.Wrap,
                IsTextSelectionEnabled = true
            });

            var copyBtn = new Button { Content = "复制错误日志" };
            copyBtn.Click += (_, _) =>
            {
                NativeShellHelper.CopyText(detail);
                copyBtn.Content = "已复制错误日志";
            };
            stack.Children.Add(copyBtn);

            var dialog = new ContentDialog
            {
                Title = title,
                Content = new ScrollViewer { MaxHeight = 360, Content = stack },
                CloseButtonText = "关闭",
                DefaultButton = ContentDialogButton.Close
            };

            if (!string.IsNullOrWhiteSpace(settingsButtonText) && onNavigateSettings is not null)
            {
                dialog.PrimaryButtonText = settingsButtonText;
                dialog.DefaultButton = ContentDialogButton.Primary;
            }
            return dialog;
        });

        if (res == ContentDialogResult.Primary && onNavigateSettings is not null)
        {
            onNavigateSettings();
        }
    }

    public async Task<ContentDialogResult> ShowCustomDialogAsync(ContentDialog dialog)
    {
        if (_disposed || dialog is null) return ContentDialogResult.None;

        await _dialogGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_disposed) return ContentDialogResult.None;
            if (_dispatcher is not null && !_dispatcher.HasThreadAccess)
            {
                var tcs = new TaskCompletionSource<ContentDialogResult>(TaskCreationOptions.RunContinuationsAsynchronously);
                if (!_dispatcher.TryEnqueue(async () =>
                {
                    try
                    {
                        var xr = TryGetXamlRoot();
                        if (xr is null) { tcs.TrySetResult(ContentDialogResult.None); return; }
                        PrepareDialog(dialog, _owner, xr);
                        tcs.TrySetResult(await dialog.ShowAsync());
                    }
                    catch
                    {
                        tcs.TrySetResult(ContentDialogResult.None);
                    }
                }))
                {
                    return ContentDialogResult.None;
                }
                return await tcs.Task.ConfigureAwait(false);
            }

            var xamlRoot = TryGetXamlRoot();
            if (xamlRoot is null) return ContentDialogResult.None;
            PrepareDialog(dialog, _owner, xamlRoot);
            try
            {
                return await dialog.ShowAsync();
            }
            catch
            {
                return ContentDialogResult.None;
            }
        }
        finally
        {
            try { _dialogGate.Release(); } catch { }
        }
    }

    public void Dispose()
    {
        _disposed = true;
        _owner = null;
        _dispatcher = null;
    }
}
