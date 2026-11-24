using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 添加第三方中转网站弹窗，支持自动通过服务端抓取目标网页标题
/// </summary>
public sealed partial class AddRelaySiteDialog : ContentDialog
{
    private readonly AppServices _services;
    private bool _fetching;

    public AddRelaySiteDialog(AppServices services)
    {
        _services = services;
        InitializeComponent();
    }

    private async void UrlBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(NameBox.Text) && !string.IsNullOrWhiteSpace(UrlBox.Text))
        {
            await TryFetchTitleAsync();
        }
    }

    private async void FetchTitleBtn_Click(object sender, RoutedEventArgs e) => await TryFetchTitleAsync();

    private async Task TryFetchTitleAsync()
    {
        var url = UrlBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url) || _fetching) return;

        _fetching = true;
        FetchTitleBtn.IsEnabled = false;
        try
        {
            var title = await _services.ApiClient.FetchRelayTitleAsync(url);
            if (!string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(NameBox.Text))
            {
                NameBox.Text = title;
            }
        }
        catch
        {
        }
        finally
        {
            FetchTitleBtn.IsEnabled = true;
            _fetching = false;
        }
    }

    private async void AddRelaySiteDialog_PrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var url = UrlBox.Text.Trim();
        var name = NameBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(name))
        {
            args.Cancel = true;
            ErrorBar.Message = _services.Loc.T("请填写网站名称与有效网址");
            ErrorBar.IsOpen = true;
            return;
        }

        var deferral = args.GetDeferral();
        try
        {
            await _services.ApiClient.AddRelaySiteAsync(name, url);
            _services.MainVm.Notify("已添加中转站", InfoBarSeverity.Success);
            await _services.MainVm.SettingsVm.RefreshRelaySitesAsync();
        }
        catch (Exception ex)
        {
            args.Cancel = true;
            ErrorBar.Message = ex.Message;
            ErrorBar.IsOpen = true;
        }
        finally
        {
            deferral.Complete();
        }
    }
}
