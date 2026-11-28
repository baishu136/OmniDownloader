using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;

namespace OmniDownloader.WinUI.Views;

/// <summary>
/// 批量下载页视图
/// </summary>
public sealed partial class BatchPage : Page
{
    public BatchViewModel Vm => AppServices.Current.MainVm.BatchVm;

    private bool? _isNarrowLayout;
    private bool _langHooked;
    private string? _appliedLanguage;

    public BatchPage()
    {
        InitializeComponent();
        DataContext = Vm;
        Loaded += BatchPage_Loaded;
        Unloaded += BatchPage_Unloaded;
    }

    private void BatchPage_Loaded(object sender, RoutedEventArgs e)
    {
        if (!_langHooked)
        {
            _langHooked = true;
            AppServices.Current.Loc.LanguageChanged += Loc_LanguageChanged;
        }
        var lang = AppServices.Current.Loc.CurrentLanguage;
        if (!string.Equals(_appliedLanguage, lang, StringComparison.Ordinal))
        {
            _appliedLanguage = lang;
            Localized.RefreshTree(this);
            RefreshLocalizedTexts();
            try { Bindings.Update(); } catch { }
        }
        _ = Vm.RefreshBatchesIfVisibleAsync();
    }

    private void BatchPage_Unloaded(object sender, RoutedEventArgs e)
    {
        if (_langHooked)
        {
            _langHooked = false;
            AppServices.Current.Loc.LanguageChanged -= Loc_LanguageChanged;
        }
    }

    private void Loc_LanguageChanged(object? sender, string e)
    {
        _appliedLanguage = AppServices.Current.Loc.CurrentLanguage;
        Localized.RefreshTree(this);
        RefreshLocalizedTexts();
        try { Bindings.Update(); } catch { }
    }

    private void RefreshLocalizedTexts()
    {
        if (BatchRawBox is not null)
        {
            BatchRawBox.PlaceholderText = AppServices.Current.Loc.T(Vm.IsManualMode
                ? "粘贴单条链接..."
                : "粘贴包含多个视频或图文链接的分享文本，每批最多 100 条...");
        }
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

        ContentContainer.Margin = narrow ? new Thickness(12, 10, 12, 20) : new Thickness(22, 14, 22, 26);
        PageTitleText.FontSize = narrow ? 22 : 28;
    }

    public string AddButtonLabel(bool isManual) => AppServices.Current.Loc.T(isManual ? "添加链接" : "识别并添加");

    public bool NotSubmitting(bool submitting) => !submitting;

    private void InputModeBar_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (Vm is null) return;
        var tag = (sender.SelectedItem as SelectorBarItem)?.Tag as string;
        if (tag == "manual") Vm.SetManualModeCommand.Execute(null);
        else Vm.SetSmartModeCommand.Execute(null);

        if (BatchRawBox is not null)
        {
            BatchRawBox.PlaceholderText = AppServices.Current.Loc.T(tag == "manual"
                ? "粘贴单条链接..."
                : "粘贴包含多个视频或图文链接的分享文本，每批最多 100 条...");
        }
    }

    private void BatchTypeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (Localized.IsRefreshingSelection) return;
        if (Vm is not null && BatchTypeCombo?.SelectedItem is ComboBoxItem item && item.Tag is string tag)
        {
            Vm.SelectedDownloadType = tag;
        }
    }

    private void BatchAudioCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (Localized.IsRefreshingSelection) return;
        if (Vm is not null && BatchAudioCombo?.SelectedItem is ComboBoxItem item && item.Tag is string tag)
        {
            Vm.SelectedAudioFormat = tag;
        }
    }

    private void BatchRuleCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (Vm is not null && BatchRuleCombo?.SelectedItem is Core.Models.RuleSelectorChoice choice)
        {
            Vm.SelectedRuleMode = choice.Mode;
            Vm.SelectedRuleId = choice.RuleId;
        }
    }

    private void RemoveDraftBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string url)
        {
            Vm.RemoveDraftLinkCommand.Execute(url);
        }
    }

    private void CancelBatchBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string jobId)
        {
            _ = Vm.CancelBatchJobCommand.ExecuteAsync(jobId);
        }
    }

    private void RetryBatchBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string jobId)
        {
            _ = Vm.RetryBatchJobCommand.ExecuteAsync(jobId);
        }
    }

    private void SendSingleAnalyzeBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string url)
        {
            Vm.SendRowToSingleAnalyzeCommand.Execute(url);
        }
    }
}
