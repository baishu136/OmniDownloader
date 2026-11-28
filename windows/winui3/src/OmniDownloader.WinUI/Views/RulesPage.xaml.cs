using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;

namespace OmniDownloader.WinUI.Views;

/// <summary>
/// Omni Download Rules 自定义规则管理页：列表排序、可视化与 JSON 双编辑器、 dry-run 测试与审计记录
/// </summary>
public sealed partial class RulesPage : Page
{
    public RulesViewModel Vm => AppServices.Current.MainVm.RulesVm;

    private bool _syncingCombos;
    private bool? _isNarrowLayout;
    private string? _appliedLanguage;

    public RulesPage()
    {
        InitializeComponent();
        DataContext = Vm;
        Loaded += RulesPage_Loaded;
        Unloaded += RulesPage_Unloaded;
    }

    private void RulesPage_Loaded(object sender, RoutedEventArgs e)
    {
        Vm.PropertyChanged += Vm_PropertyChanged;
        AppServices.Current.Loc.LanguageChanged += Loc_LanguageChanged;
        var lang = AppServices.Current.Loc.CurrentLanguage;
        if (!string.Equals(_appliedLanguage, lang, StringComparison.Ordinal))
        {
            _appliedLanguage = lang;
            Localized.RefreshTree(this);
            try { Bindings.Update(); } catch { }
        }
        SyncCombosFromVm();
        _ = Vm.LoadOverviewAsync();
    }

    private void RulesPage_Unloaded(object sender, RoutedEventArgs e)
    {
        Vm.PropertyChanged -= Vm_PropertyChanged;
        AppServices.Current.Loc.LanguageChanged -= Loc_LanguageChanged;
    }

    private void Loc_LanguageChanged(object? sender, string e)
    {
        _appliedLanguage = AppServices.Current.Loc.CurrentLanguage;
        Localized.RefreshTree(this);
        SyncCombosFromVm();
        try { Bindings.Update(); } catch { }
    }

    private void Vm_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(RulesViewModel.EditUrlMatchMode)
            or nameof(RulesViewModel.EditCollectionMode)
            or nameof(RulesViewModel.EditDownloadMode)
            or nameof(RulesViewModel.EditQualityStrategy)
            or nameof(RulesViewModel.EditQualityCodec)
            or nameof(RulesViewModel.EditQualityDynamicRange)
            or nameof(RulesViewModel.EditQualityContainer)
            or nameof(RulesViewModel.EditAudioFormat)
            or nameof(RulesViewModel.EditQualityFallback)
            or nameof(RulesViewModel.EditDuplicatePolicy)
            or nameof(RulesViewModel.EditExecutionAction))
        {
            SyncCombosFromVm();
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
        Grid.SetRow(HeaderActionsPanel, narrow ? 1 : 0);
        Grid.SetColumn(HeaderActionsPanel, narrow ? 0 : 1);
    }

    private void SyncCombosFromVm()
    {
        _syncingCombos = true;
        try
        {
            SelectTag(UrlMatchModeCombo, Vm.EditUrlMatchMode);
            SelectTag(CollectionModeCombo, Vm.EditCollectionMode);
            SelectTag(RuleDownloadModeCombo, Vm.EditDownloadMode);
            SelectTag(RuleQualityStrategyCombo, Vm.EditQualityStrategy);
            SelectTag(RuleCodecCombo, Vm.EditQualityCodec);
            SelectTag(RuleDynamicRangeCombo, Vm.EditQualityDynamicRange);
            SelectTag(RuleContainerCombo, Vm.EditQualityContainer);
            SelectTag(RuleAudioFormatCombo, Vm.EditAudioFormat);
            SelectTag(RuleFallbackCombo, Vm.EditQualityFallback);
            SelectTag(RuleDuplicateCombo, Vm.EditDuplicatePolicy);
            SelectTag(RuleExecutionCombo, Vm.EditExecutionAction);
        }
        finally
        {
            _syncingCombos = false;
        }
    }

    private static void SelectTag(ComboBox combo, string tag)
    {
        if (combo is null) return;
        for (var i = 0; i < combo.Items.Count; i++)
        {
            if (combo.Items[i] is ComboBoxItem item && string.Equals(item.Tag as string, tag, StringComparison.OrdinalIgnoreCase))
            {
                combo.SelectedIndex = i;
                return;
            }
        }
        if (combo.Items.Count > 0) combo.SelectedIndex = 0;
    }

    public bool NotBusy(bool busy) => !busy;

    public InfoBarSeverity JsonMsgSeverity(bool isError) => isError ? InfoBarSeverity.Error : InfoBarSeverity.Success;

    public string FormatDedupSummary(int count) => AppServices.Current.Loc.T("跨入口去重索引中当前记录共 %d 条（自动屏蔽重复触发）", count);

    private void RulesSectionBar_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        var tag = (sender.SelectedItem as SelectorBarItem)?.Tag as string ?? "rules";
        PanelRules.Visibility = tag == "rules" ? Visibility.Visible : Visibility.Collapsed;
        PanelTest.Visibility = tag == "test" ? Visibility.Visible : Visibility.Collapsed;
        PanelLogs.Visibility = tag == "logs" ? Visibility.Visible : Visibility.Collapsed;
        if (tag == "logs") _ = Vm.LoadOverviewAsync();
    }

    private void NewRuleScroll_Click(object sender, RoutedEventArgs e)
    {
        RulesSectionBar.SelectedItem = SectionRulesItem;
        RuleEditorCard.StartBringIntoView();
    }

    private void ClipboardPolicyField_Changed(object sender, RoutedEventArgs e) => _ = Vm.PersistRulePolicyAsync();

    private void ApproveConfirmBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is string cid) _ = Vm.ApproveConfirmationAsync(cid);
    }

    private void DismissConfirmBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is string cid) _ = Vm.DismissConfirmationAsync(cid);
    }

    private void RuleEnabledSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch ts && ts.Tag is RuleListItemVm item && ts.IsOn != item.IsEnabled)
        {
            _ = Vm.ToggleRuleEnabledAsync(item, ts.IsOn);
        }
    }

    private void MoveRuleUpBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is RuleListItemVm item) _ = Vm.MoveRuleOrderAsync(item, -1);
    }

    private void MoveRuleDownBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is RuleListItemVm item) _ = Vm.MoveRuleOrderAsync(item, 1);
    }

    private void EditRuleBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is RuleListItemVm item)
        {
            Vm.LoadRuleIntoEditor(item.Rule);
            SyncCombosFromVm();
            RuleEditorCard.StartBringIntoView();
        }
    }

    private void DuplicateRuleBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is RuleListItemVm item) _ = Vm.DuplicateRuleAsync(item);
    }

    private void DeleteRuleBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b && b.Tag is RuleListItemVm item) _ = Vm.DeleteRuleAsync(item);
    }

    private void SyncFormToJsonBtn_Click(object sender, RoutedEventArgs e)
    {
        Vm.SyncVisualFormToJson();
        _ = Vm.ValidateEditorJsonAsync();
    }

    private static string? SelectedTag(ComboBox combo) => (combo.SelectedItem as ComboBoxItem)?.Tag as string;

    private void UrlMatchModeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(UrlMatchModeCombo) is string t) Vm.EditUrlMatchMode = t;
    }

    private void CollectionModeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(CollectionModeCombo) is string t) Vm.EditCollectionMode = t;
    }

    private void RuleDownloadModeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleDownloadModeCombo) is string t) Vm.EditDownloadMode = t;
    }

    private void RuleQualityStrategyCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleQualityStrategyCombo) is string t) Vm.EditQualityStrategy = t;
    }

    private void RuleCodecCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleCodecCombo) is string t) Vm.EditQualityCodec = t;
    }

    private void RuleDynamicRangeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleDynamicRangeCombo) is string t) Vm.EditQualityDynamicRange = t;
    }

    private void RuleContainerCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleContainerCombo) is string t) Vm.EditQualityContainer = t;
    }

    private void RuleAudioFormatCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleAudioFormatCombo) is string t) Vm.EditAudioFormat = t;
    }

    private void RuleFallbackCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleFallbackCombo) is string t) Vm.EditQualityFallback = t;
    }

    private void RuleDuplicateCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleDuplicateCombo) is string t) Vm.EditDuplicatePolicy = t;
    }

    private void RuleExecutionCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncingCombos && SelectedTag(RuleExecutionCombo) is string t) Vm.EditExecutionAction = t;
    }

    private void TestTriggerCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SelectedTag(TestTriggerCombo) is string t) Vm.TestTrigger = t;
    }
}
