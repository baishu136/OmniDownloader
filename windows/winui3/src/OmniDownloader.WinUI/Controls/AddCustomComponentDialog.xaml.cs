using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 添加自定义组件弹窗，用于配置并预留后续扩展组件的接入名称、说明、类型与入口
/// </summary>
public sealed partial class AddCustomComponentDialog : ContentDialog
{
    private readonly AppServices _services;

    public AddCustomComponentDialog(AppServices services)
    {
        _services = services;
        InitializeComponent();
    }

    private void AddCustomComponentDialog_PrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var name = NameBox.Text.Trim();
        var desc = DescriptionBox.Text.Trim();
        var entry = EntryPointBox.Text.Trim();
        var kind = (KindCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "extractor";

        if (string.IsNullOrWhiteSpace(name))
        {
            args.Cancel = true;
            ErrorBar.Message = _services.Loc.T("请填写组件名称");
            ErrorBar.IsOpen = true;
            return;
        }

        _services.MainVm.SettingsVm.AddCustomComponent(name, desc, kind, entry);
    }
}
