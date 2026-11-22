using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;

namespace OmniDownloader.WinUI.Views;

/// <summary>
/// 下载任务管理视图
/// </summary>
public sealed partial class TasksPage : Page
{
    public TasksViewModel Vm => AppServices.Current.MainVm.TasksVm;

    private bool? _isNarrowLayout;

    public TasksPage()
    {
        InitializeComponent();
        DataContext = Vm;
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
        PageTitleText.FontSize = narrow ? 22 : 28;
        Grid.SetRow(HeaderActionsPanel, narrow ? 1 : 0);
        Grid.SetColumn(HeaderActionsPanel, narrow ? 0 : 1);
    }

    private void TaskFilterBar_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (Vm is not null && sender.SelectedItem is SelectorBarItem item && item.Tag is string mode)
        {
            Vm.FilterMode = mode;
        }
    }

    private async void ThumbImage_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is Image img && img.Tag is string url && !string.IsNullOrWhiteSpace(url) && img.Source is null)
        {
            try
            {
                var bmp = await NativeShellHelper.LoadImageAsync(url, decodePixelWidth: 200);
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

    private void PlayTaskBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.PlayTaskCommand.ExecuteAsync(t);
    }

    private void LocateTaskBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.LocateFileCommand.ExecuteAsync(t);
    }

    private void RetryTaskBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.RetryTaskCommand.ExecuteAsync(t);
    }

    private void CancelTaskBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.CancelTaskCommand.ExecuteAsync(t);
    }

    private void DeleteSingleTaskBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.DeleteSingleTaskCommand.ExecuteAsync(t);
    }

    private void ShowTaskErrorBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is DownloadTask t) _ = Vm.ShowTaskErrorDetailCommand.ExecuteAsync(t);
    }

    private void ToggleCollectionBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is HyperlinkButton btn && btn.Tag is TaskGroupViewModel g) Vm.ToggleCollectionExpandedCommand.Execute(g);
    }

    private void PlayFirstCollectionBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is TaskGroupViewModel g) Vm.PlayFirstInCollectionCommand.Execute(g);
    }

    private void CancelCollectionBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is TaskGroupViewModel g) _ = Vm.CancelCollectionActiveCommand.ExecuteAsync(g);
    }

    private void DeleteCollectionBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is TaskGroupViewModel g) _ = Vm.DeleteCollectionCommand.ExecuteAsync(g);
    }
}
