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
/// 首页视图：链接解析、页内格式选择、图文网格多选、原生看图及内置浏览器辅助调起
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

    private void HomePage_Loaded(object sender, RoutedEventArgs e)
    {
        AttachVmEvents();
        Localized.RefreshTree(this);
        try { Bindings.Update(); } catch { }

        if (Vm.CurrentVideoMetadata is not null) ShowVideoOptions(Vm.CurrentVideoMetadata);
    }

    private void HomePage_Unloaded(object sender, RoutedEventArgs e) => DetachVmEvents();

    private void AttachVmEvents()
    {
        if (_vmEventsHooked) return;
        _vmEventsHooked = true;
        Vm.VideoAnalyzedOptionsRequested += Vm_VideoAnalyzedOptionsRequested;
        Vm.PropertyChanged += Vm_PropertyChanged;
        Vm.BuiltInBrowserAssistRequested += Vm_BuiltInBrowserAssistRequested;
        Vm.ImageViewerRequested += Vm_ImageViewerRequested;
        AppServices.Current.Loc.LanguageChanged += Loc_LanguageChanged;
    }

    private void DetachVmEvents()
    {
        if (!_vmEventsHooked) return;
        _vmEventsHooked = false;
        Vm.VideoAnalyzedOptionsRequested -= Vm_VideoAnalyzedOptionsRequested;
        Vm.PropertyChanged -= Vm_PropertyChanged;
        Vm.BuiltInBrowserAssistRequested -= Vm_BuiltInBrowserAssistRequested;
        Vm.ImageViewerRequested -= Vm_ImageViewerRequested;
        AppServices.Current.Loc.LanguageChanged -= Loc_LanguageChanged;
    }

    private void Loc_LanguageChanged(object? sender, string e)
    {
        Localized.RefreshTree(this);
        try { Bindings.Update(); } catch { }
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
        Grid.SetColumnSpan(HeroTitlePanel, narrow ? 2 : 1);

        Grid.SetRow(HeroCatalogBtn, narrow ? 1 : 0);
        Grid.SetColumn(HeroCatalogBtn, narrow ? 0 : 1);
        HeroCatalogBtn.HorizontalAlignment = narrow ? HorizontalAlignment.Left : HorizontalAlignment.Right;

        if (narrow && w < 320)
        {
            AnalyzeActionBtn.Width = double.NaN;
            AnalyzeActionBtn.HorizontalAlignment = HorizontalAlignment.Stretch;
        }
        else
        {
            AnalyzeActionBtn.Width = 190;
            AnalyzeActionBtn.HorizontalAlignment = HorizontalAlignment.Right;
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

    private void Vm_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(HomeViewModel.CurrentVideoMetadata) && Vm.CurrentVideoMetadata is null)
            FormatOptionsHost.Content = null;
    }

    private void Vm_VideoAnalyzedOptionsRequested(object? sender, VideoMetadata metadata) => ShowVideoOptions(metadata);

    private void ShowVideoOptions(VideoMetadata metadata)
    {
        if (FormatOptionsHost.Content is FormatSelectorPanel current && ReferenceEquals(current.Metadata, metadata)) return;
        var panel = new FormatSelectorPanel(AppServices.Current, metadata);
        panel.SubmitRequested += SubmitVideoTasks;
        FormatOptionsHost.Content = panel;
        NativeShellHelper.PlayFadeIn(FormatOptionsHost, durationMs: 170, fromOffsetY: 8);
    }

    private async void SubmitVideoTasks(FormatSelectorPanel panel, IReadOnlyList<CreateTaskItem> tasks)
    {
        try
        {
            await AppServices.Current.ApiClient.SubmitDownloadTasksAsync(tasks);
            AppServices.Current.MainVm.Notify("已加入 %d 个下载任务", InfoBarSeverity.Success, tasks.Count);
            await AppServices.Current.MainVm.RefreshTasksAsync();
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("HomePage.VideoDownload", ex);
            AppServices.Current.MainVm.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally { panel.SetSubmitting(false); }
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
}
