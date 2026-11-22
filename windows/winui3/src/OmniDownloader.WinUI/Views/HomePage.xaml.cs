using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using OmniDownloader.WinUI.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;
using Windows.System;
using Windows.UI;

namespace OmniDownloader.WinUI.Views;

/// <summary>
/// 首页视图：链接解析、格式弹窗调起、图文网格多选、原生看图及内置浏览器辅助调起
/// </summary>
public sealed partial class HomePage : Page
{
    public HomeViewModel Vm => AppServices.Current.MainVm.HomeVm;

    private BrowserAssistWindow? _assistWindow;
    private bool _vmEventsHooked;
    private bool? _isNarrowLayout;

    public HomePage()
    {
        InitializeComponent();
        DataContext = Vm;

        Loaded += HomePage_Loaded;
        Unloaded += HomePage_Unloaded;
        AttachVmEvents();
    }

    private void HomePage_Loaded(object sender, RoutedEventArgs e) => AttachVmEvents();

    private void HomePage_Unloaded(object sender, RoutedEventArgs e) => DetachVmEvents();

    private void AttachVmEvents()
    {
        if (_vmEventsHooked) return;
        _vmEventsHooked = true;
        Vm.VideoAnalyzedOpenDialogRequested += Vm_VideoAnalyzedOpenDialogRequested;
        Vm.BuiltInBrowserAssistRequested += Vm_BuiltInBrowserAssistRequested;
        Vm.ImageViewerRequested += Vm_ImageViewerRequested;
    }

    private void DetachVmEvents()
    {
        if (!_vmEventsHooked) return;
        _vmEventsHooked = false;
        Vm.VideoAnalyzedOpenDialogRequested -= Vm_VideoAnalyzedOpenDialogRequested;
        Vm.BuiltInBrowserAssistRequested -= Vm_BuiltInBrowserAssistRequested;
        Vm.ImageViewerRequested -= Vm_ImageViewerRequested;
    }

    private void Page_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var w = e.NewSize.Width;
        var narrow = _isNarrowLayout switch
        {
            null => w < 460,
            true => w <= 476,
            false => w < 448
        };
        if (_isNarrowLayout == narrow) return;
        _isNarrowLayout = narrow;

        ContentContainer.Margin = narrow ? new Thickness(14, 10, 14, 24) : new Thickness(28, 18, 28, 32);
        HeroTitleText.FontSize = narrow ? 22 : 28;

        Grid.SetRow(HeroCatalogBtn, narrow ? 1 : 0);
        Grid.SetColumn(HeroCatalogBtn, narrow ? 0 : 1);
        HeroCatalogBtn.HorizontalAlignment = narrow ? HorizontalAlignment.Left : HorizontalAlignment.Right;

        // 窄窗口时双主按钮垂直堆叠全宽展示，防止文字裁断
        if (narrow)
        {
            Grid.SetRow(PasteActionBtn, 0);
            Grid.SetColumn(PasteActionBtn, 0);
            Grid.SetColumnSpan(PasteActionBtn, 2);

            Grid.SetRow(AnalyzeActionBtn, 1);
            Grid.SetColumn(AnalyzeActionBtn, 0);
            Grid.SetColumnSpan(AnalyzeActionBtn, 2);
        }
        else
        {
            Grid.SetRow(PasteActionBtn, 0);
            Grid.SetColumn(PasteActionBtn, 0);
            Grid.SetColumnSpan(PasteActionBtn, 1);

            Grid.SetRow(AnalyzeActionBtn, 0);
            Grid.SetColumn(AnalyzeActionBtn, 1);
            Grid.SetColumnSpan(AnalyzeActionBtn, 1);
        }
    }

    public string InlineIconGlyph(bool hasInput) => hasInput ? "\uE711" : "\uE77F";

    public bool CanClickAnalyze(bool hasInput, bool isAnalyzing) => hasInput || isAnalyzing;

    public string AnalyzeButtonText(bool isAnalyzing) =>
        isAnalyzing ? Vm.Loc.BtnAnalyzing : Vm.Loc.BtnAnalyze;

    public Visibility IsActionableSettingsError(string action) =>
        action is "accounts" or "network" ? Visibility.Visible : Visibility.Collapsed;

    public bool HasSelectedImages(int count) => count > 0;

    private void UrlInputBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter && !Vm.IsAnalyzing && Vm.HasInput)
        {
            e.Handled = true;
            _ = Vm.AnalyzeOrCancelCommand.ExecuteAsync(null);
        }
    }

    private async void Vm_VideoAnalyzedOpenDialogRequested(object? sender, VideoMetadata metadata)
    {
        try
        {
            var dialog = new FormatSelectorDialog(AppServices.Current, metadata);
            var result = await AppServices.Current.Dialogs.ShowCustomDialogAsync(dialog);

            var tasks = dialog.SubmittedTasks;
            if (tasks is null || tasks.Count == 0) return;

            if (result == ContentDialogResult.Primary || result == ContentDialogResult.Secondary || result == ContentDialogResult.None)
            {
                await AppServices.Current.ApiClient.SubmitDownloadTasksAsync(tasks);
                AppServices.Current.MainVm.Notify($"已加入 {tasks.Count} 个下载任务", InfoBarSeverity.Success);
                await AppServices.Current.MainVm.RefreshTasksAsync();
                AppServices.Current.MainVm.NavigateTo("tasks");
            }
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("HomePage.VideoDialog", ex);
            AppServices.Current.MainVm.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    private void Vm_BuiltInBrowserAssistRequested(object? sender, string url)
    {
        if (_assistWindow is not null)
        {
            try
            {
                _assistWindow.Activate();
                return;
            }
            catch
            {
                _assistWindow = null;
            }
        }

        _assistWindow = new BrowserAssistWindow(AppServices.Current, url);
        _assistWindow.Closed += (_, _) => _assistWindow = null;
        _assistWindow.Activate();
    }

    private void Vm_ImageViewerRequested(object? sender, (ImagePost Post, int StartIndex) args)
    {
        MediaWindowCoordinator.OpenImagePost(AppServices.Current, args.Post, args.StartIndex);
    }

    private async void OpenPlatformCatalog_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var dialog = new PlatformCatalogDialog(AppServices.Current);
            await AppServices.Current.Dialogs.ShowCustomDialogAsync(dialog);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("HomePage.OpenCatalog", ex);
        }
    }

    private void ResumeFormatDialog_Click(object sender, RoutedEventArgs e) => Vm.OpenResumeVideoDialog();

    private void GoRelaySettings_Click(object sender, RoutedEventArgs e) =>
        AppServices.Current.MainVm.NavigateTo("settings", "relay");

    private void GoAllTasks_Click(object sender, RoutedEventArgs e) =>
        AppServices.Current.MainVm.NavigateTo("tasks");

    private void QuickRelayBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is RelaySite site)
        {
            _ = Vm.OpenRelaySiteWithConfirmAsync(site);
        }
    }

    private void ExportImagesZip_Click(object sender, RoutedEventArgs e) =>
        _ = Vm.DownloadSelectedImagesCommand.ExecuteAsync("zip");

    private void SaveSelectedImages_Click(object sender, RoutedEventArgs e) =>
        _ = Vm.DownloadSelectedImagesCommand.ExecuteAsync("image");

    private void ImagePreviewCard_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is int idx)
        {
            Vm.RequestImageViewer(idx);
        }
    }

    private void RecentTaskCard_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is RecentTaskItem item)
        {
            _ = Vm.OpenRecentTaskAsync(item);
        }
    }

    private async void PreviewImage_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is Image img && img.Tag is string url && !string.IsNullOrWhiteSpace(url) && img.Source is null)
        {
            try
            {
                var bmp = await NativeShellHelper.LoadImageAsync(url, decodePixelWidth: 320);
                if (bmp is not null && img.IsLoaded && string.Equals(img.Tag as string, url, StringComparison.Ordinal))
                {
                    img.Source = bmp;
                }
            }
            catch
            {
            }
        }
    }
}
