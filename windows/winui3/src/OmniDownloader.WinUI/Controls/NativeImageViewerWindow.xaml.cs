using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.System;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生全功能图片查看窗口：支持无级滚轮/双击缩放、1:1 原尺寸、本地图/ZIP 图集翻页与在线原图预览保存
/// </summary>
public sealed partial class NativeImageViewerWindow : Window
{
    private readonly AppServices _services;
    private readonly IReadOnlyList<NativeImageEntry> _entries;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcher;
    private int _index;
    private int _loadGeneration;
    private int _pixelWidth;
    private int _pixelHeight;
    private bool _isFitMode = true;
    private bool _isClosed;
    private bool _isNarrowToolbar;
    private bool _hideFooterHint;
    private CancellationTokenSource? _loadCts;

    /// <summary>
    /// 按窗口宽度切换图片工具栏折行状态及底部操作提示可见性，避免窄窗口按钮挤压或重叠。
    /// </summary>
    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var narrowToolbar = e.NewSize.Width < 720;
        if (_isNarrowToolbar != narrowToolbar)
        {
            _isNarrowToolbar = narrowToolbar;
            Grid.SetRow(ToolbarActionsPanel, narrowToolbar ? 1 : 0);
            Grid.SetColumn(ToolbarActionsPanel, narrowToolbar ? 0 : 1);
            Grid.SetColumnSpan(ToolbarActionsPanel, narrowToolbar ? 2 : 1);
            ToolbarActionsPanel.HorizontalAlignment = narrowToolbar ? HorizontalAlignment.Left : HorizontalAlignment.Right;
        }

        var hideHint = e.NewSize.Width < 460;
        if (_hideFooterHint != hideHint)
        {
            _hideFooterHint = hideHint;
            FooterHintText.Visibility = hideHint ? Visibility.Collapsed : Visibility.Visible;
        }
    }

    public NativeImageViewerWindow(
        AppServices services,
        string windowTitle,
        IReadOnlyList<NativeImageEntry> entries,
        int startIndex = 0)
    {
        _services = services;
        _entries = entries;
        _index = Math.Clamp(startIndex, 0, Math.Max(0, entries.Count - 1));

        InitializeComponent();
        _dispatcher = DispatcherQueue;
        _services.RegisterSecondaryWindow(this);
        Title = $"OmniDownloader · {windowTitle}";
        WindowTitleText.Text = windowTitle;

        NativeShellHelper.ConfigureWindowChrome(this, TitleBarDragRegion, baseWidth: 980, baseHeight: 720);

        ApplyTheme();

        RootGrid.ActualThemeChanged += (_, _) =>
        {
            if (ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged(_services.LocalState.Current.Theme))
            {
                ApplyTheme();
            }
        };
        _services.MainVm.ThemeAppearanceChanged += OnThemeChanged;
        Closed += NativeImageViewerWindow_Closed;

        _ = LoadCurrentAsync();
    }

    private void OnThemeChanged(object? sender, (string Theme, bool PureBlack) e)
    {
        try
        {
            if (_dispatcher.HasThreadAccess) ApplyTheme();
            else _dispatcher.TryEnqueue(ApplyTheme);
        }
        catch
        {
        }
    }

    private void ApplyTheme()
    {
        if (_isClosed || NativeShellHelper.IsApplyingThemeRefresh) return;
        var state = _services.LocalState.Current;
        NativeShellHelper.ApplyThemeAndTitleBarColors(this, state.Theme, state.UsePureBlackDarkMode, state.AccentColor);
    }

    private void NativeImageViewerWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        _loadCts?.Cancel();
        _loadCts?.Dispose();
        _loadCts = null;
        _services.MainVm.ThemeAppearanceChanged -= OnThemeChanged;
        PreviewImage.Source = null;
    }

    private async Task LoadCurrentAsync()
    {
        if (_entries.Count == 0 || _isClosed) return;
        _loadCts?.Cancel();
        _loadCts?.Dispose();
        var cts = new CancellationTokenSource();
        _loadCts = cts;

        var gen = ++_loadGeneration;
        var item = _entries[_index];

        EntryTitleText.Text = string.IsNullOrWhiteSpace(item.Title) ? _services.Loc.T("第 %d 张", _index + 1) : item.Title;
        UpdateMetaText(item);

        PrevBtn.IsEnabled = _entries.Count > 1;
        NextBtn.IsEnabled = _entries.Count > 1;
        DownloadSingleBtn.Visibility = item.AllowDownloadSingle ? Visibility.Visible : Visibility.Collapsed;

        var hasLocal = !string.IsNullOrWhiteSpace(item.LocalFilePath) && File.Exists(item.LocalFilePath);
        var hasZip = !string.IsNullOrWhiteSpace(item.ZipArchivePath) && File.Exists(item.ZipArchivePath);
        LocateBtn.Visibility = hasLocal || hasZip || !string.IsNullOrWhiteSpace(item.TaskId) ? Visibility.Visible : Visibility.Collapsed;
        OpenExternalBtn.Visibility = hasLocal ? Visibility.Visible : Visibility.Collapsed;
        FallbackOpenBtn.Visibility = hasLocal ? Visibility.Visible : Visibility.Collapsed;

        LoadingRing.IsActive = true;
        FailedPanel.Visibility = Visibility.Collapsed;
        PreviewImage.Source = null;
        _pixelWidth = 0;
        _pixelHeight = 0;
        _isFitMode = true;

        var bmp = await LoadBitmapAsync(item, cts.Token);
        if (_isClosed || gen != _loadGeneration || cts.IsCancellationRequested) return;

        LoadingRing.IsActive = false;
        if (bmp is not null)
        {
            _pixelWidth = bmp.PixelWidth;
            _pixelHeight = bmp.PixelHeight;
            if (_pixelWidth > 0 && _pixelHeight > 0)
            {
                PreviewImage.Width = _pixelWidth;
                PreviewImage.Height = _pixelHeight;
            }
            else
            {
                PreviewImage.ClearValue(FrameworkElement.WidthProperty);
                PreviewImage.ClearValue(FrameworkElement.HeightProperty);
            }

            PreviewImage.Source = bmp;
            NativeShellHelper.PlayFadeIn(PreviewImage, durationMs: 130);
    
            UpdateMetaText(item);
            ApplyFitZoom();
        }
        else
        {
            FailedPanel.Visibility = Visibility.Visible;
        }
    }

    private async Task<Microsoft.UI.Xaml.Media.Imaging.BitmapImage?> LoadBitmapAsync(NativeImageEntry item, CancellationToken ct)
    {
        if (!string.IsNullOrWhiteSpace(item.LocalFilePath) && File.Exists(item.LocalFilePath))
        {
            var localBmp = await NativeShellHelper.LoadLocalOrZipImageAsync(localPath: item.LocalFilePath, ct: ct);
            if (localBmp is not null || ct.IsCancellationRequested) return localBmp;
        }

        if (!string.IsNullOrWhiteSpace(item.ZipArchivePath) && !string.IsNullOrWhiteSpace(item.ZipEntryName) && File.Exists(item.ZipArchivePath))
        {
            var zipBmp = await NativeShellHelper.LoadLocalOrZipImageAsync(null, item.ZipArchivePath, item.ZipEntryName, ct);
            if (zipBmp is not null || ct.IsCancellationRequested) return zipBmp;
        }

        if (!string.IsNullOrWhiteSpace(item.RemoteUrl))
        {
            return await NativeShellHelper.LoadImageAsync(item.RemoteUrl, ct: ct);
        }

        return null;
    }

    private void UpdateMetaText(NativeImageEntry item)
    {
        var parts = new List<string> { $"{_index + 1} / {_entries.Count}" };
        if (_pixelWidth > 0 && _pixelHeight > 0)
        {
            parts.Add($"{_pixelWidth}×{_pixelHeight}");
        }
        if (!string.IsNullOrWhiteSpace(item.Subtitle))
        {
            parts.Add(item.Subtitle);
        }
        MetaText.Text = string.Join(" · ", parts);
    }

    private float ComputeFitFactor()
    {
        if (_pixelWidth <= 0 || _pixelHeight <= 0) return 1.0f;
        var vw = ZoomScroller.ViewportWidth;
        var vh = ZoomScroller.ViewportHeight;
        if (vw <= 20 || vh <= 20) return 1.0f;

        var scaleX = (vw - 24.0) / _pixelWidth;
        var scaleY = (vh - 24.0) / _pixelHeight;
        return (float)Math.Clamp(Math.Min(scaleX, scaleY), 0.15, 1.0);
    }

    private void ApplyFitZoom()
    {
        _isFitMode = true;
        var factor = ComputeFitFactor();
        DispatcherQueue.TryEnqueue(() =>
        {
            if (_isClosed) return;
            ZoomScroller.ChangeView(0, 0, factor, disableAnimation: true);
            UpdateZoomLabel(factor);
        });
    }

    private void ApplyZoomFactor(float targetFactor)
    {
        _isFitMode = false;
        var clamped = Math.Clamp(targetFactor, ZoomScroller.MinZoomFactor, ZoomScroller.MaxZoomFactor);
        ZoomScroller.ChangeView(null, null, clamped, disableAnimation: false);
        UpdateZoomLabel(clamped);
    }

    private void UpdateZoomLabel(float factor)
    {
        var pct = (int)Math.Round(factor * 100);
        ZoomBadgeBtn.Content = _isFitMode ? _services.Loc.T("适应 (%d%%)", pct) : $"{pct}%";
    }

    private void ZoomScroller_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_isFitMode && _pixelWidth > 0 && _pixelHeight > 0)
        {
            ApplyFitZoom();
        }
    }

    private void ZoomScroller_ViewChanged(object sender, ScrollViewerViewChangedEventArgs e)
    {
        UpdateZoomLabel(ZoomScroller.ZoomFactor);
    }

    private void ZoomScroller_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (_isFitMode || ZoomScroller.ZoomFactor < 0.99f)
        {
            ApplyZoomFactor(1.0f);
        }
        else
        {
            ApplyFitZoom();
        }
    }

    private void FitZoomBtn_Click(object sender, RoutedEventArgs e) => ApplyFitZoom();
    private void ActualZoomBtn_Click(object sender, RoutedEventArgs e) => ApplyZoomFactor(1.0f);
    private void ZoomInBtn_Click(object sender, RoutedEventArgs e) => ApplyZoomFactor(ZoomScroller.ZoomFactor * 1.25f);
    private void ZoomOutBtn_Click(object sender, RoutedEventArgs e) => ApplyZoomFactor(ZoomScroller.ZoomFactor / 1.25f);

    private void PrevBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_entries.Count <= 1) return;
        _index = (_index - 1 + _entries.Count) % _entries.Count;
        _ = LoadCurrentAsync();
    }

    private void NextBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_entries.Count <= 1) return;
        _index = (_index + 1) % _entries.Count;
        _ = LoadCurrentAsync();
    }

    private void OpenExternalBtn_Click(object sender, RoutedEventArgs e)
    {
        var item = _entries[_index];
        if (!string.IsNullOrWhiteSpace(item.LocalFilePath) && File.Exists(item.LocalFilePath))
        {
            NativeShellHelper.OpenWithShell(item.LocalFilePath);
        }
    }

    private async void LocateBtn_Click(object sender, RoutedEventArgs e)
    {
        var item = _entries[_index];
        if (!string.IsNullOrWhiteSpace(item.TaskId))
        {
            try
            {
                await _services.ApiClient.OpenTaskFileAsync(item.TaskId);
                return;
            }
            catch
            {
            }
        }
        var target = !string.IsNullOrWhiteSpace(item.LocalFilePath) ? item.LocalFilePath : item.ZipArchivePath;
        if (!string.IsNullOrWhiteSpace(target) && File.Exists(target))
        {
            var dir = Path.GetDirectoryName(target);
            if (!string.IsNullOrWhiteSpace(dir)) NativeShellHelper.OpenWithShell(dir);
        }
    }

    private async void DownloadSingleBtn_Click(object sender, RoutedEventArgs e)
    {
        var item = _entries[_index];
        if (!item.AllowDownloadSingle || string.IsNullOrWhiteSpace(item.ImageResourceId)) return;

        DownloadSingleBtn.IsEnabled = false;
        try
        {
            await _services.ApiClient.SubmitDownloadTasksAsync(new[]
            {
                new CreateTaskItem
                {
                    Url = item.PostUrl,
                    Title = item.Title,
                    Author = item.Author,
                    DownloadType = "IMAGE",
                    ImagePlatform = item.Platform,
                    SelectedImageIds = new List<string> { item.ImageResourceId },
                    MediaId = item.ContentId
                }
            });
            _services.MainVm.Notify("已加入原图下载任务", InfoBarSeverity.Success);
            await _services.MainVm.RefreshTasksAsync();
        }
        catch (Exception ex)
        {
            _services.MainVm.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            DownloadSingleBtn.IsEnabled = true;
        }
    }

    private void RootGrid_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        switch (e.Key)
        {
            case VirtualKey.Left:
                PrevBtn_Click(this, new RoutedEventArgs());
                e.Handled = true;
                break;
            case VirtualKey.Right:
                NextBtn_Click(this, new RoutedEventArgs());
                e.Handled = true;
                break;
            case VirtualKey.Number0:
            case VirtualKey.NumberPad0:
                ApplyFitZoom();
                e.Handled = true;
                break;
            case VirtualKey.Number1:
            case VirtualKey.NumberPad1:
                ApplyZoomFactor(1.0f);
                e.Handled = true;
                break;
            case VirtualKey.Add:
                ApplyZoomFactor(ZoomScroller.ZoomFactor * 1.25f);
                e.Handled = true;
                break;
            case VirtualKey.Subtract:
                ApplyZoomFactor(ZoomScroller.ZoomFactor / 1.25f);
                e.Handled = true;
                break;
            case VirtualKey.Escape:
                Close();
                e.Handled = true;
                break;
        }
    }
}
