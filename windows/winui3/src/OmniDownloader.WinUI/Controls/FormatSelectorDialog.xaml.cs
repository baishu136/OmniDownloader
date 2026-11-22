using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生 WinUI 3 格式与画质选择器弹窗：支持单集/分P切换、合集多选、封面伴随保存与 4 种下载类型
/// </summary>
public sealed partial class FormatSelectorDialog : ContentDialog
{
    private readonly AppServices _services;
    private readonly VideoMetadata _metadata;

    private int _selectedPartIndex;
    private bool _isCollectionMode;
    private readonly HashSet<int> _selectedEpisodes = new();
    private string _downloadType = "VIDEO_WITH_AUDIO";
    private string _selectedFormatId = "best";
    private string _audioFormat = "mp3";
    private bool _suppressSelectionEvents;
    private bool _isClosed;
    private CancellationTokenSource? _thumbCts;

    /// <summary>
    /// 用户确认提交后生成的待入队任务列表；若为单独保存封面则为单条 COVER 任务
    /// </summary>
    public IReadOnlyList<CreateTaskItem>? SubmittedTasks { get; private set; }

    public FormatSelectorDialog(AppServices services, VideoMetadata metadata)
    {
        _services = services;
        _metadata = metadata;
        InitializeComponent();

        for (var i = 0; i < metadata.MultiMediaList.Count; i++)
        {
            _selectedEpisodes.Add(i);
        }

        QualityPolicyCombo.SelectedIndex = 0;
        ContainerCombo.SelectedIndex = 0;

        PrimaryButtonClick += FormatSelectorDialog_PrimaryButtonClick;
        SecondaryButtonClick += FormatSelectorDialog_SecondaryButtonClick;
        Closed += (_, _) =>
        {
            _isClosed = true;
            _thumbCts?.Cancel();
            _thumbCts?.Dispose();
            _thumbCts = null;
            ThumbImage.Source = null;
        };

        var first = CurrentMedia();
        _downloadType = first.IsGif ? "GIF" : "VIDEO_WITH_AUDIO";
        _selectedFormatId = first.AvailableVideoFormats.FirstOrDefault()?.FormatId ?? "best";

        RenderMetadata();
    }

    private VideoMetadata CurrentMedia() =>
        _metadata.MultiMediaList.Count > 0 && _selectedPartIndex < _metadata.MultiMediaList.Count
            ? _metadata.MultiMediaList[_selectedPartIndex]
            : _metadata;

    private void RenderMetadata()
    {
        if (_isClosed) return;
        var media = CurrentMedia();
        MediaTitleText.Text = string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title;
        SiteBadgeText.Text = string.IsNullOrWhiteSpace(media.SiteName) ? _metadata.SiteName : media.SiteName;
        GifBadge.Visibility = media.IsGif ? Visibility.Visible : Visibility.Collapsed;

        var author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author;
        DurationAuthorText.Text = string.Join(" · ", new[] { media.DurationText, author }.Where(s => !string.IsNullOrWhiteSpace(s)));

        if (SiteBadgeText.Text == "哔哩哔哩")
        {
            var acct = string.IsNullOrWhiteSpace(media.AccountName) ? _services.Loc.T("未验证") : media.AccountName;
            BiliAccessText.Text = _services.Loc.T("当前账号：%s · 访问状态：%s", acct, media.AccessState);
            BiliAccessText.Visibility = Visibility.Visible;
        }
        else
        {
            BiliAccessText.Visibility = Visibility.Collapsed;
        }

        QualityWarningBar.Message = media.QualityWarning;
        QualityWarningBar.IsOpen = !string.IsNullOrWhiteSpace(media.QualityWarning);
        QualityEvidenceText.Text = media.QualityEvidence;
        QualityEvidenceText.Visibility = string.IsNullOrWhiteSpace(media.QualityEvidence) ? Visibility.Collapsed : Visibility.Visible;

        var hasMulti = _metadata.MultiMediaList.Count > 1;
        CollectionSection.Visibility = hasMulti ? Visibility.Visible : Visibility.Collapsed;
        if (hasMulti)
        {
            CollectionModeItem.Text = _services.Loc.T("下载合集 (%d)", _metadata.MultiMediaList.Count);
            RenderSingleParts();
            RenderCollectionCheckboxes();
        }

        UpdateTypeButtonsUi();
        RenderVideoFormats(media.AvailableVideoFormats);
        UpdateActionButtonsText();

        var thumbUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl;
        CoverActionPanel.Visibility = string.IsNullOrWhiteSpace(thumbUrl) ? Visibility.Collapsed : Visibility.Visible;
        _ = LoadThumbnailAsync(thumbUrl);
    }

    private async Task LoadThumbnailAsync(string thumbUrl)
    {
        _thumbCts?.Cancel();
        _thumbCts?.Dispose();
        _thumbCts = null;

        if (_isClosed || string.IsNullOrWhiteSpace(thumbUrl))
        {
            ThumbImage.Source = null;
            return;
        }

        var cts = new CancellationTokenSource();
        _thumbCts = cts;
        try
        {
            var bmp = await NativeShellHelper.LoadImageAsync(thumbUrl, decodePixelWidth: 260, ct: cts.Token);
            if (!_isClosed && !cts.IsCancellationRequested)
            {
                ThumbImage.Source = bmp;
            }
        }
        catch
        {
        }
    }

    private void RenderSingleParts()
    {
        SinglePartsPrompt.Text = _metadata.SiteName == "哔哩哔哩"
            ? "点击切换当前预览与下载的分P/分集："
            : "点击切换当前预览与独立下载的视频：";

        SinglePartsChipsPanel.Children.Clear();
        for (var i = 0; i < _metadata.MultiMediaList.Count; i++)
        {
            var index = i;
            var item = _metadata.MultiMediaList[i];
            var tagName = item.Title.Contains(" - P", StringComparison.Ordinal) ? $"P{i + 1}" : $"视频 {i + 1}";
            var label = string.IsNullOrWhiteSpace(item.DurationText) ? tagName : $"{tagName} {item.DurationText}";
            var toggle = new ToggleButton
            {
                Content = label,
                IsChecked = i == _selectedPartIndex
            };
            toggle.Click += (_, _) =>
            {
                _selectedPartIndex = index;
                var cur = CurrentMedia();
                _selectedFormatId = cur.AvailableVideoFormats.FirstOrDefault()?.FormatId ?? "best";
                RenderMetadata();
            };
            SinglePartsChipsPanel.Children.Add(toggle);
        }
    }

    private void RenderCollectionCheckboxes()
    {
        EpisodesCheckListPanel.Children.Clear();
        for (var i = 0; i < _metadata.MultiMediaList.Count; i++)
        {
            var idx = i;
            var ep = _metadata.MultiMediaList[i];
            var badge = ep.Title.Contains(" - P", StringComparison.Ordinal) ? $"P{i + 1}" : $"{i + 1}";
            var dur = string.IsNullOrWhiteSpace(ep.DurationText) ? string.Empty : $" ({ep.DurationText})";
            var cb = new CheckBox
            {
                Content = $"[{badge}] {ep.Title}{dur}",
                IsChecked = _selectedEpisodes.Contains(idx)
            };
            cb.Checked += (_, _) =>
            {
                _selectedEpisodes.Add(idx);
                UpdateEpisodesCountText();
                UpdateActionButtonsText();
            };
            cb.Unchecked += (_, _) =>
            {
                _selectedEpisodes.Remove(idx);
                UpdateEpisodesCountText();
                UpdateActionButtonsText();
            };
            EpisodesCheckListPanel.Children.Add(cb);
        }
        UpdateEpisodesCountText();
    }

    private void UpdateEpisodesCountText()
    {
        EpisodesCountText.Text = _services.Loc.T("已选 %d / %d 集", _selectedEpisodes.Count, _metadata.MultiMediaList.Count);
    }

    private void RenderVideoFormats(IReadOnlyList<FormatOption> formats)
    {
        _suppressSelectionEvents = true;
        try
        {
            VideoFormatRadioButtons.Items.Clear();
            if (formats.Count == 0)
            {
                VideoFormatRadioButtons.Items.Add(new RadioButton
                {
                    Content = "将按上方画质策略自动选择最佳可用源",
                    IsChecked = true,
                    IsEnabled = false
                });
                return;
            }

            var chosen = 0;
            for (var i = 0; i < formats.Count; i++)
            {
                var fmt = formats[i];
                if (fmt.FormatId == _selectedFormatId) chosen = i;
                VideoFormatRadioButtons.Items.Add(new RadioButton
                {
                    Content = fmt.DisplaySummary,
                    Tag = fmt.FormatId
                });
            }
            VideoFormatRadioButtons.SelectedIndex = chosen;
        }
        finally
        {
            _suppressSelectionEvents = false;
        }
    }

    private void UpdateTypeButtonsUi()
    {
        TypeVideoAudioBtn.IsChecked = _downloadType == "VIDEO_WITH_AUDIO";
        TypeVideoOnlyBtn.IsChecked = _downloadType == "VIDEO_ONLY";
        TypeAudioOnlyBtn.IsChecked = _downloadType == "AUDIO_ONLY";
        TypeGifBtn.IsChecked = _downloadType == "GIF";

        var showVideo = _downloadType is "VIDEO_WITH_AUDIO" or "VIDEO_ONLY";
        VideoOptionsPanel.Visibility = showVideo ? Visibility.Visible : Visibility.Collapsed;
        AudioOptionsPanel.Visibility = _downloadType == "AUDIO_ONLY" ? Visibility.Visible : Visibility.Collapsed;
        GifHintBar.Visibility = _downloadType == "GIF" ? Visibility.Visible : Visibility.Collapsed;

        ResolutionHintText.Text = _downloadType == "VIDEO_ONLY"
            ? "选择纯视频分辨率 (完全去除声音轨，静音素材)"
            : "选择视频分辨率 (自动合成最佳声音)";
    }

    private void UpdateActionButtonsText()
    {
        var media = CurrentMedia();
        var hasCover = !string.IsNullOrWhiteSpace(media.ThumbnailUrl) || !string.IsNullOrWhiteSpace(_metadata.ThumbnailUrl);
        var coverSuffix = SaveCoverToggle.IsOn && hasCover ? " + 封面" : string.Empty;

        var policyTag = (QualityPolicyCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "BEST_AVAILABLE";
        var selectedFmt = media.AvailableVideoFormats.FirstOrDefault(f => f.FormatId == _selectedFormatId);
        var resText = policyTag == "EXACT_FORMAT"
            ? (selectedFmt?.ResolutionLabel ?? "精确画质")
            : ((QualityPolicyCombo.SelectedItem as ComboBoxItem)?.Content?.ToString()?.Split('（')[0] ?? "最高画质");

        var hasMulti = _metadata.MultiMediaList.Count > 1;
        if (_isCollectionMode && hasMulti)
        {
            var count = _selectedEpisodes.Count;
            IsPrimaryButtonEnabled = count > 0;
            PrimaryButtonText = count == 0
                ? "请至少勾选一个视频"
                : _downloadType switch
                {
                    "AUDIO_ONLY" => $"下载所选音频 ({count} 首 · {_audioFormat.ToUpperInvariant()}){coverSuffix}",
                    "GIF" => $"下载所选动图 ({count} 个){coverSuffix}",
                    _ => $"下载所选合集 ({count} 集 · {resText}){coverSuffix}"
                };
            SecondaryButtonText = $"全部下载 ({_metadata.MultiMediaList.Count} 集){coverSuffix}";
        }
        else
        {
            IsPrimaryButtonEnabled = true;
            PrimaryButtonText = _downloadType switch
            {
                "VIDEO_WITH_AUDIO" => $"开始下载 ({resText}){coverSuffix}",
                "VIDEO_ONLY" => $"纯画面下载 ({resText}){coverSuffix}",
                "AUDIO_ONLY" => $"提取音频 ({_audioFormat.ToUpperInvariant()}){coverSuffix}",
                "GIF" => $"导出 GIF{coverSuffix}",
                _ => "开始下载"
            };
            SecondaryButtonText = hasMulti ? $"切换为合集多选 ({_metadata.MultiMediaList.Count} 集)" : string.Empty;
        }
    }

    private QualitySelection BuildPolicy(bool isBatchCollection)
    {
        var policyTag = (QualityPolicyCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "BEST_AVAILABLE";
        var containerTag = (ContainerCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "mp4";
        var allowLower = AllowLowerQualityCheck.IsChecked == true;
        var selectedFmt = CurrentMedia().AvailableVideoFormats.FirstOrDefault(f => f.FormatId == _selectedFormatId);
        return OmniDomainLogic.BuildQualityPolicy(policyTag, containerTag, allowLower, isBatchCollection, selectedFmt);
    }

    private List<CreateTaskItem> BuildTasks(bool allEpisodes)
    {
        var tasks = new List<CreateTaskItem>();
        var media = CurrentMedia();
        var multi = _metadata.MultiMediaList;

        if (_isCollectionMode && multi.Count > 1)
        {
            var indices = allEpisodes
                ? Enumerable.Range(0, multi.Count).ToList()
                : _selectedEpisodes.OrderBy(i => i).ToList();

            var policy = BuildPolicy(isBatchCollection: true);
            foreach (var index in indices)
            {
                var ep = multi[index];
                tasks.Add(new CreateTaskItem
                {
                    Url = ep.Url,
                    Title = $"{_metadata.Title} - {ep.Title}",
                    DownloadType = _downloadType,
                    SelectedResolution = "best",
                    MediaId = ep.MediaId,
                    QualitySelection = policy,
                    AudioFormat = _audioFormat,
                    ThumbnailUrl = string.IsNullOrWhiteSpace(ep.ThumbnailUrl) ? _metadata.ThumbnailUrl : ep.ThumbnailUrl,
                    Author = _metadata.Author,
                    CollectionId = _metadata.Url,
                    CollectionTitle = _metadata.Title,
                    EpisodeIndex = index + 1,
                    EpisodeTotal = multi.Count
                });
            }
        }
        else
        {
            var policy = BuildPolicy(isBatchCollection: false);
            tasks.Add(new CreateTaskItem
            {
                Url = string.IsNullOrWhiteSpace(media.Url) ? _metadata.Url : media.Url,
                Title = string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title,
                DownloadType = _downloadType,
                SelectedResolution = policy.Mode == "EXACT_FORMAT" ? _selectedFormatId : "best",
                SelectedVideoFormatId = policy.Mode == "EXACT_FORMAT" ? _selectedFormatId : string.Empty,
                MediaId = media.MediaId,
                QualitySelection = policy,
                AudioFormat = _audioFormat,
                ThumbnailUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl,
                Author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author
            });
        }

        if (SaveCoverToggle.IsOn)
        {
            var coverUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl;
            if (!string.IsNullOrWhiteSpace(coverUrl))
            {
                tasks.Add(new CreateTaskItem
                {
                    Url = coverUrl,
                    Title = $"{(string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title)} - 封面",
                    DownloadType = "COVER",
                    ThumbnailUrl = coverUrl,
                    Author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author
                });
            }
        }

        return tasks;
    }

    private void FormatSelectorDialog_PrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var tasks = BuildTasks(allEpisodes: false);
        if (tasks.Count == 0)
        {
            args.Cancel = true;
            return;
        }
        SubmittedTasks = tasks;
    }

    private void FormatSelectorDialog_SecondaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        if (_metadata.MultiMediaList.Count <= 1) return;

        if (!_isCollectionMode)
        {
            // 仅切换为合集多选模式，不关闭弹窗
            args.Cancel = true;
            _isCollectionMode = true;
            ModeSelectorBar.SelectedItem = CollectionModeItem;
            UpdateCollectionPanels();
            UpdateActionButtonsText();
            return;
        }

        // 已在合集模式：提交全部集下载
        SubmittedTasks = BuildTasks(allEpisodes: true);
    }

    private void ModeSelectorBar_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        var tag = (sender.SelectedItem as SelectorBarItem)?.Tag as string;
        _isCollectionMode = tag == "collection";
        UpdateCollectionPanels();
        UpdateActionButtonsText();
    }

    private void UpdateCollectionPanels()
    {
        SinglePartsPanel.Visibility = _isCollectionMode ? Visibility.Collapsed : Visibility.Visible;
        CollectionChecklistPanel.Visibility = _isCollectionMode ? Visibility.Visible : Visibility.Collapsed;
    }

    private void SelectAllEpisodes_Click(object sender, RoutedEventArgs e)
    {
        for (var i = 0; i < _metadata.MultiMediaList.Count; i++) _selectedEpisodes.Add(i);
        RenderCollectionCheckboxes();
        UpdateActionButtonsText();
    }

    private void InvertEpisodes_Click(object sender, RoutedEventArgs e)
    {
        for (var i = 0; i < _metadata.MultiMediaList.Count; i++)
        {
            if (!_selectedEpisodes.Remove(i)) _selectedEpisodes.Add(i);
        }
        RenderCollectionCheckboxes();
        UpdateActionButtonsText();
    }

    private void SelectNoneEpisodes_Click(object sender, RoutedEventArgs e)
    {
        _selectedEpisodes.Clear();
        RenderCollectionCheckboxes();
        UpdateActionButtonsText();
    }

    private void SaveCoverToggle_Toggled(object sender, RoutedEventArgs e) => UpdateActionButtonsText();

    private void SaveCoverDirectBtn_Click(object sender, RoutedEventArgs e)
    {
        var media = CurrentMedia();
        var coverUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl;
        if (string.IsNullOrWhiteSpace(coverUrl)) return;

        SubmittedTasks = new[]
        {
            new CreateTaskItem
            {
                Url = coverUrl,
                Title = $"{(string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title)} - 封面",
                DownloadType = "COVER",
                ThumbnailUrl = coverUrl,
                Author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author
            }
        };
        Hide();
    }

    private void TypeButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleButton btn && btn.Tag is string type)
        {
            _downloadType = type;
            UpdateTypeButtonsUi();
            UpdateActionButtonsText();
        }
    }

    private void QualityPolicyCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        UpdateActionButtonsText();
    }

    private void VideoFormatRadioButtons_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelectionEvents) return;
        if (VideoFormatRadioButtons.SelectedItem is RadioButton rb && rb.Tag is string fmtId)
        {
            _selectedFormatId = fmtId;
            // 选中某条具体格式流时自动切为精确格式策略
            var exactIdx = QualityPolicyCombo.Items
                .OfType<ComboBoxItem>()
                .ToList()
                .FindIndex(item => item.Tag as string == "EXACT_FORMAT");
            if (exactIdx >= 0) QualityPolicyCombo.SelectedIndex = exactIdx;
            UpdateActionButtonsText();
        }
    }

    private void AudioFormatRadioButtons_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (AudioFormatRadioButtons.SelectedItem is RadioButton rb && rb.Tag is string af)
        {
            _audioFormat = af;
            UpdateActionButtonsText();
        }
    }
}
