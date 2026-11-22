using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;

namespace OmniDownloader.WinUI.Views;

/// <summary>
/// 分层设置视图：一级概览 + 六大二级设置子页（基于 BreadcrumbBar 面包屑导航）
/// </summary>
public sealed partial class SettingsPage : Page
{
    private static readonly Dictionary<string, string> CategoryNames = new()
    {
        ["root"] = "偏好设置",
        ["downloads"] = "下载与存储",
        ["network"] = "网络代理",
        ["relay"] = "中转站",
        ["accounts"] = "账号与 Cookie",
        ["engine"] = "下载引擎与组件",
        ["about"] = "关于"
    };

    public SettingsViewModel Vm => AppServices.Current.MainVm.SettingsVm;

    private bool _syncingControls;
    private bool _eventsHooked;
    private bool? _isNarrowLayout;

    public SettingsPage()
    {
        InitializeComponent();
        DataContext = Vm;

        Loaded += SettingsPage_Loaded;
        Unloaded += SettingsPage_Unloaded;
        AttachEvents();

        SyncCombosFromVm();
        UpdateSubpageVisibility();
        UpdateBreadcrumb();
    }

    private void SettingsPage_Loaded(object sender, RoutedEventArgs e)
    {
        AttachEvents();
        SyncCombosFromVm();
        UpdateSubpageVisibility();
        UpdateBreadcrumb();
        if (Vm.CurrentCategory == "accounts") _ = Vm.LoadSelectedSessionPolicyAsync();
        else if (Vm.CurrentCategory == "engine") _ = Vm.CheckEngineDiagnosticsAsync();
    }

    private void SettingsPage_Unloaded(object sender, RoutedEventArgs e) => DetachEvents();

    private void AttachEvents()
    {
        if (_eventsHooked) return;
        _eventsHooked = true;
        Vm.PropertyChanged += Vm_PropertyChanged;
        Vm.AddRelaySiteDialogRequested += Vm_AddRelaySiteDialogRequested;
        Vm.BilibiliGuideDialogRequested += Vm_BilibiliGuideDialogRequested;
        Vm.PlatformCatalogDialogRequested += Vm_PlatformCatalogDialogRequested;
        AppServices.Current.Loc.LanguageChanged += Loc_LanguageChanged;
    }

    private void DetachEvents()
    {
        if (!_eventsHooked) return;
        _eventsHooked = false;
        Vm.PropertyChanged -= Vm_PropertyChanged;
        Vm.AddRelaySiteDialogRequested -= Vm_AddRelaySiteDialogRequested;
        Vm.BilibiliGuideDialogRequested -= Vm_BilibiliGuideDialogRequested;
        Vm.PlatformCatalogDialogRequested -= Vm_PlatformCatalogDialogRequested;
        AppServices.Current.Loc.LanguageChanged -= Loc_LanguageChanged;
    }

    private void Loc_LanguageChanged(object? sender, string e) => UpdateBreadcrumb();

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

        ContentContainer.Margin = narrow
            ? new Thickness(14, 10, 14, 24)
            : new Thickness(28, 18, 28, 32);
    }

    private void Vm_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(SettingsViewModel.CurrentCategory))
        {
            UpdateSubpageVisibility();
            UpdateBreadcrumb();
        }
        else if (e.PropertyName is nameof(SettingsViewModel.SelectedTheme)
                 or nameof(SettingsViewModel.SelectedLanguage)
                 or nameof(SettingsViewModel.MaxConcurrentTasks)
                 or nameof(SettingsViewModel.AutoStartWithWindows)
                 or nameof(SettingsViewModel.IsSelectedPlatformCookieDisabled))
        {
            SyncCombosFromVm();
        }
    }

    private void SyncCombosFromVm()
    {
        _syncingControls = true;
        try
        {
            SelectComboByTag(LanguageCombo, Vm.SelectedLanguage);
            SelectComboByTag(ThemeCombo, Vm.SelectedTheme);
            ConcurrentNumberBox.Value = Vm.MaxConcurrentTasks;
            AutoStartToggle.IsOn = Vm.AutoStartWithWindows;
            SessionDisableCookieToggle.IsOn = Vm.IsSelectedPlatformCookieDisabled;
        }
        finally
        {
            _syncingControls = false;
        }
    }

    private static void SelectComboByTag(ComboBox box, string tag)
    {
        for (var i = 0; i < box.Items.Count; i++)
        {
            if (box.Items[i] is ComboBoxItem item && string.Equals(item.Tag as string, tag, StringComparison.OrdinalIgnoreCase))
            {
                box.SelectedIndex = i;
                return;
            }
        }
    }

    private void UpdateSubpageVisibility()
    {
        var cat = Vm.CurrentCategory;
        PanelDownloads.Visibility = cat == "downloads" ? Visibility.Visible : Visibility.Collapsed;
        PanelNetwork.Visibility = cat == "network" ? Visibility.Visible : Visibility.Collapsed;
        PanelRelay.Visibility = cat == "relay" ? Visibility.Visible : Visibility.Collapsed;
        PanelAccounts.Visibility = cat == "accounts" ? Visibility.Visible : Visibility.Collapsed;
        PanelEngine.Visibility = cat == "engine" ? Visibility.Visible : Visibility.Collapsed;
        PanelAbout.Visibility = cat == "about" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateBreadcrumb()
    {
        var list = new List<string> { AppServices.Current.Loc.SettingsTitle };
        if (Vm.CurrentCategory != "root" && CategoryNames.TryGetValue(Vm.CurrentCategory, out var subName))
        {
            list.Add(AppServices.Current.Loc.T(subName));
        }
        SettingsBreadcrumb.ItemsSource = list;
    }

    private void SettingsBreadcrumb_ItemClicked(BreadcrumbBar sender, BreadcrumbBarItemClickedEventArgs args)
    {
        if (args.Index == 0 && Vm.CurrentCategory != "root")
        {
            Vm.GoBackToRoot();
        }
    }

    private void BackToRoot_Click(object sender, RoutedEventArgs e) => Vm.GoBackToRoot();

    private void CategoryCard_Clicked(object sender, RoutedEventArgs e)
    {
        if (sender is OmniSettingsCard card && card.Tag is string cat)
        {
            Vm.OpenCategory(cat);
        }
    }

    private void LanguageCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingControls) return;
        if (LanguageCombo.SelectedItem is ComboBoxItem item && item.Tag is string code)
        {
            Vm.SelectedLanguage = code;
        }
    }

    private void ThemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingControls) return;
        if (ThemeCombo.SelectedItem is ComboBoxItem item && item.Tag is string theme)
        {
            Vm.SelectedTheme = theme;
        }
    }

    private void ConcurrentNumberBox_ValueChanged(NumberBox sender, NumberBoxValueChangedEventArgs args)
    {
        if (_syncingControls || double.IsNaN(args.NewValue)) return;
        var val = Math.Clamp((int)Math.Round(args.NewValue), 1, 10);
        if (val != Vm.MaxConcurrentTasks)
        {
            Vm.MaxConcurrentTasks = val;
            _ = Vm.SaveMaxConcurrentTasksAsync(val);
        }
    }

    private void AutoStartToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_syncingControls) return;
        var isOn = AutoStartToggle.IsOn;
        if (isOn != Vm.AutoStartWithWindows)
        {
            Vm.AutoStartWithWindows = isOn;
            _ = Vm.SaveAutoStartAsync(isOn);
        }
    }

    private void SessionDisableCookieToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_syncingControls) return;
        var disabled = SessionDisableCookieToggle.IsOn;
        if (disabled != Vm.IsSelectedPlatformCookieDisabled)
        {
            Vm.IsSelectedPlatformCookieDisabled = disabled;
            _ = Vm.ToggleSessionCookieDisabledAsync(disabled);
        }
    }

    private void OpenRelayBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is RelaySite site) _ = Vm.OpenRelaySiteCommand.ExecuteAsync(site);
    }

    private void DeleteRelayBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is RelaySite site) _ = Vm.DeleteRelaySiteCommand.ExecuteAsync(site);
    }

    private async void Vm_AddRelaySiteDialogRequested(object? sender, EventArgs e)
    {
        try
        {
            var dialog = new AddRelaySiteDialog(AppServices.Current);
            await AppServices.Current.Dialogs.ShowCustomDialogAsync(dialog);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("SettingsPage.AddRelayDialog", ex);
        }
    }

    private async void Vm_BilibiliGuideDialogRequested(object? sender, EventArgs e)
    {
        try
        {
            await AppServices.Current.Dialogs.ShowMessageAsync(
                "如何获取哔哩哔哩 SESSDATA",
                "1. 在电脑浏览器中登录 bilibili.com；\n2. 按 F12 打开开发者工具，切换到 Application（应用） -> Cookies -> https://www.bilibili.com；\n3. 在列表中找到 Name 为 SESSDATA 的条目并复制其 Value；\n4. 回到此处粘贴保存，可用画质取决于账号权益、内容和平台接口。\n\n或者在“平台 Cookie 会话隔离仓库”中选择“哔哩哔哩”并导入导出的 Netscape cookies.txt 文件。",
                "我知道了");
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("SettingsPage.BilibiliGuide", ex);
        }
    }

    private async void Vm_PlatformCatalogDialogRequested(object? sender, EventArgs e)
    {
        try
        {
            var dialog = new PlatformCatalogDialog(AppServices.Current);
            await AppServices.Current.Dialogs.ShowCustomDialogAsync(dialog);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("SettingsPage.PlatformCatalog", ex);
        }
    }

    private void AboutPlatformsCard_Clicked(object sender, RoutedEventArgs e) =>
        Vm.ShowPlatformCatalogCommand.Execute(null);
}
