using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using OmniDownloader.WinUI.ViewModels;
using Windows.UI;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 首次使用引导弹窗：依次配置主题色设置、下载保存目录（含跟随默认目录/视频/图片含GIF/音频）及是否开启网页服务
/// </summary>
public sealed partial class OnboardingDialog : ContentDialog
{
    private readonly AppServices _services;
    private int _stepIndex;
    private bool _syncing;
    private bool _initialized;
    private string _selectedLanguage;
    private string _selectedTheme;
    private string _selectedAccentColor;

    public OnboardingDialog(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _initialized = true;

        var st = _services.LocalState.Current;
        _selectedLanguage = st.Language;
        _selectedTheme = st.Theme;
        _selectedAccentColor = ThemeAccentHelper.NormalizeSetting(st.AccentColor);

        Loaded += OnboardingDialog_Loaded;
        Unloaded += (_, _) => _services.Loc.LanguageChanged -= Loc_LanguageChanged;
        _services.Loc.LanguageChanged += Loc_LanguageChanged;

        InitializeValuesFromState();
        SetStep(0);
    }

    private void OnboardingDialog_Loaded(object sender, RoutedEventArgs e)
    {
        Localized.RefreshTree(this);
        UpdateNavButtonsText();
        UpdateWebServiceHint();
    }

    private void Loc_LanguageChanged(object? sender, string e)
    {
        Localized.RefreshTree(this);
        RefreshAccentUi();
        UpdateNavButtonsText();
        UpdateWebServiceHint();
    }

    private void InitializeValuesFromState()
    {
        _syncing = true;
        try
        {
            var st = _services.LocalState.Current;
            SelectComboByTag(LanguageCombo, _selectedLanguage);
            SelectComboByTag(ThemeCombo, _selectedTheme);

            var fallbackDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads",
                "OmniDownloader");
            DefaultDirBox.Text = string.IsNullOrWhiteSpace(_services.MainVm.SettingsVm.DownloadDir)
                ? fallbackDir
                : _services.MainVm.SettingsVm.DownloadDir;

            FollowDefaultToggle.IsOn = st.FollowDefaultDownloadDir;
            VideoDirBox.Text = st.VideoDownloadDir;
            ImageDirBox.Text = st.ImageDownloadDir;
            AudioDirBox.Text = st.AudioDownloadDir;
            CategoryDirsPanel.Visibility = FollowDefaultToggle.IsOn ? Visibility.Collapsed : Visibility.Visible;

            WebServiceToggle.IsOn = st.EnableWebService;
            RefreshAccentUi();
            UpdateWebServiceHint();
        }
        finally
        {
            _syncing = false;
        }
    }

    private static void SelectComboByTag(ComboBox box, string tag)
    {
        for (var i = 0; i < box.Items.Count; i++)
        {
            if (box.Items[i] is ComboBoxItem ci && string.Equals(ci.Tag as string, tag, StringComparison.OrdinalIgnoreCase))
            {
                box.SelectedIndex = i;
                return;
            }
        }
    }

    private void SetStep(int index)
    {
        _stepIndex = Math.Clamp(index, 0, 2);
        StepAppearancePanel.Visibility = _stepIndex == 0 ? Visibility.Visible : Visibility.Collapsed;
        StepDirsPanel.Visibility = _stepIndex == 1 ? Visibility.Visible : Visibility.Collapsed;
        StepWebServicePanel.Visibility = _stepIndex == 2 ? Visibility.Visible : Visibility.Collapsed;

        _syncing = true;
        try
        {
            StepBar.SelectedItem = _stepIndex switch
            {
                1 => Step1Item,
                2 => Step2Item,
                _ => Step0Item
            };
        }
        finally
        {
            _syncing = false;
        }

        UpdateNavButtonsText();
    }

    private void UpdateNavButtonsText()
    {
        var loc = _services.Loc;
        SecondaryButtonText = _stepIndex > 0 ? loc.T("上一步") : string.Empty;
        PrimaryButtonText = _stepIndex < 2 ? loc.T("下一步") : loc.T("完成配置");
        CloseButtonText = loc.T("跳过设置");
    }

    private void RefreshAccentUi()
    {
        var loc = _services.Loc;
        var norm = ThemeAccentHelper.NormalizeSetting(_selectedAccentColor);
        if (norm == ThemeAccentHelper.SystemKey)
        {
            AccentPreviewLabel.Text = loc.T("跟随系统");
            if (Application.Current?.Resources is not null &&
                Application.Current.Resources.TryGetValue("SystemAccentColor", out var sysObj) &&
                sysObj is Color sysCol)
            {
                AccentPreviewDot.Background = new SolidColorBrush(sysCol);
            }
            else
            {
                AccentPreviewDot.Background = new SolidColorBrush(Color.FromArgb(255, 0, 120, 212));
            }
        }
        else if (ThemeAccentHelper.TryParseHexRgb(norm, out var rgb))
        {
            var preset = ThemeAccentHelper.Presets.FirstOrDefault(p => string.Equals(p.Key, norm, StringComparison.OrdinalIgnoreCase));
            AccentPreviewLabel.Text = preset is not null ? $"{loc.T(preset.NameKey)} ({norm})" : norm;
            AccentPreviewDot.Background = new SolidColorBrush(Color.FromArgb(255, rgb.R, rgb.G, rgb.B));
        }

        var items = new List<ThemeColorSwatchItem>();
        foreach (var preset in ThemeAccentHelper.Presets)
        {
            if (preset.Key == ThemeAccentHelper.SystemKey) continue;
            if (!ThemeAccentHelper.TryParseHexRgb(preset.PreviewHex, out var rgb)) continue;
            var darkCheck = ThemeAccentHelper.ShouldUseDarkForeground(rgb);
            items.Add(new ThemeColorSwatchItem
            {
                Key = preset.Key,
                ToolTipText = $"{loc.T(preset.NameKey)} ({preset.Key})",
                SwatchBrush = new SolidColorBrush(Color.FromArgb(255, rgb.R, rgb.G, rgb.B)),
                CheckBrush = new SolidColorBrush(darkCheck ? Colors.Black : Colors.White),
                IsSelected = string.Equals(norm, preset.Key, StringComparison.OrdinalIgnoreCase)
            });
        }
        PresetSwatchesControl.ItemsSource = items;
    }

    private void UpdateWebServiceHint()
    {
        WebServiceDetailText.Text = _services.Loc.T(WebServiceToggle.IsOn
            ? "开启网页服务后，系统托盘将提供「拉起网页版」右键菜单入口，下载与任务队列可在桌面客户端与浏览器之间共享。"
            : "不开启网页服务时，系统托盘不显示「拉起网页版」入口；当您关闭应用时，将直接退出与本应用关联的全部后台进程与组件。");
    }

    private void StepBar_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (!_initialized || _syncing) return;
        if (sender.SelectedItem is SelectorBarItem item && int.TryParse(item.Tag as string, out var idx))
        {
            SetStep(idx);
        }
    }

    private void LanguageCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || Localized.IsRefreshingSelection) return;
        if (LanguageCombo.SelectedItem is ComboBoxItem ci && ci.Tag is string code)
        {
            _selectedLanguage = code;
            _services.MainVm.SettingsVm.SelectedLanguage = code;
        }
    }

    private void ThemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || Localized.IsRefreshingSelection) return;
        if (ThemeCombo.SelectedItem is ComboBoxItem ci && ci.Tag is string theme)
        {
            _selectedTheme = theme;
            _services.MainVm.SettingsVm.SelectedTheme = theme;
            if (_services.Dialogs.OwnerWindow?.Content is FrameworkElement root)
            {
                RequestedTheme = root.ActualTheme;
            }
        }
    }

    private void SystemAccentBtn_Click(object sender, RoutedEventArgs e)
    {
        _selectedAccentColor = ThemeAccentHelper.SystemKey;
        _services.MainVm.SettingsVm.SelectedAccentColor = ThemeAccentHelper.SystemKey;
        RefreshAccentUi();
    }

    private void PresetSwatchBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string hex)
        {
            _selectedAccentColor = hex;
            _services.MainVm.SettingsVm.SelectedAccentColor = hex;
            RefreshAccentUi();
        }
    }

    private void PickerFlyout_Opening(object sender, object e)
    {
        if (ThemeAccentHelper.TryParseHexRgb(_selectedAccentColor, out var rgb))
        {
            OnboardingColorPicker.Color = Color.FromArgb(255, rgb.R, rgb.G, rgb.B);
        }
        else
        {
            OnboardingColorPicker.Color = Color.FromArgb(255, 59, 91, 219);
        }
    }

    private void ApplyPickerColorBtn_Click(object sender, RoutedEventArgs e)
    {
        var c = OnboardingColorPicker.Color;
        var hex = $"#{c.R:X2}{c.G:X2}{c.B:X2}";
        _selectedAccentColor = hex;
        _services.MainVm.SettingsVm.SelectedAccentColor = hex;
        RefreshAccentUi();
        PickerFlyout.Hide();
    }

    private void FollowDefaultToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (!_initialized || _syncing || CategoryDirsPanel is null) return;
        CategoryDirsPanel.Visibility = FollowDefaultToggle.IsOn ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void BrowseDefaultDir_Click(object sender, RoutedEventArgs e)
    {
        var folder = await PickFolderSafelyAsync();
        if (!string.IsNullOrWhiteSpace(folder)) DefaultDirBox.Text = folder;
    }

    private async void BrowseVideoDir_Click(object sender, RoutedEventArgs e)
    {
        var folder = await PickFolderSafelyAsync();
        if (!string.IsNullOrWhiteSpace(folder)) VideoDirBox.Text = folder;
    }

    private async void BrowseImageDir_Click(object sender, RoutedEventArgs e)
    {
        var folder = await PickFolderSafelyAsync();
        if (!string.IsNullOrWhiteSpace(folder)) ImageDirBox.Text = folder;
    }

    private async void BrowseAudioDir_Click(object sender, RoutedEventArgs e)
    {
        var folder = await PickFolderSafelyAsync();
        if (!string.IsNullOrWhiteSpace(folder)) AudioDirBox.Text = folder;
    }

    private async Task<string?> PickFolderSafelyAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return null;
        try
        {
            return await NativeShellHelper.PickFolderAsync(owner);
        }
        catch
        {
            return null;
        }
    }

    private void WebServiceToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (!_initialized || _syncing || WebServiceDetailText is null) return;
        UpdateWebServiceHint();
    }

    private void OnboardingDialog_PrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        if (_stepIndex < 2)
        {
            args.Cancel = true;
            SetStep(_stepIndex + 1);
            return;
        }
        SaveAndApplyChoices();
    }

    private void OnboardingDialog_SecondaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        if (_stepIndex > 0)
        {
            args.Cancel = true;
            SetStep(_stepIndex - 1);
        }
    }

    private void OnboardingDialog_CloseButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var st = _services.LocalState.Current;
        st.HasCompletedOnboarding = true;
        _services.LocalState.Save();
    }

    private void SaveAndApplyChoices()
    {
        var st = _services.LocalState.Current;
        st.Language = _selectedLanguage;
        st.Theme = _selectedTheme;
        st.AccentColor = _selectedAccentColor;
        st.FollowDefaultDownloadDir = FollowDefaultToggle.IsOn;
        st.VideoDownloadDir = VideoDirBox.Text.Trim();
        st.ImageDownloadDir = ImageDirBox.Text.Trim();
        st.AudioDownloadDir = AudioDirBox.Text.Trim();
        st.EnableWebService = WebServiceToggle.IsOn;
        st.HasCompletedOnboarding = true;
        _services.LocalState.Save();

        var svm = _services.MainVm.SettingsVm;
        if (!string.IsNullOrWhiteSpace(DefaultDirBox.Text))
        {
            svm.DownloadDir = DefaultDirBox.Text.Trim();
        }
        svm.RefreshFromLocalState();
        _services.MainVm.NotifyWebServiceModeChanged();

        if (_services.MainVm.IsBackendConnected)
        {
            _ = svm.SyncExtendedDirectoriesToBackendAsync();
        }
    }
}
