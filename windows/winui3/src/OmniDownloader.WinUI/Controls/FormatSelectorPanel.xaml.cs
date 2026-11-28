using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生 WinUI 3 页内格式与画质选择器：顶部右侧控制封面保存，三栏并排下拉管理模式/策略/容器，多列方格卡片展示分辨率、格式与体积
/// </summary>
public sealed partial class FormatSelectorPanel : UserControl
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

    private double _currentTileWidth = 172;
    private int _currentLayoutMode = -1;
    private bool? _isCompactHeader;

    /// <summary>
    /// 关联的视频解析结果元数据
    /// </summary>
    public VideoMetadata Metadata => _metadata;

    /// <summary>
    /// 用户确认提交后生成的待入队任务列表；若为单独保存封面则为单条 COVER 任务
    /// </summary>
    public event Action<FormatSelectorPanel, IReadOnlyList<CreateTaskItem>>? SubmitRequested;

    private bool _submitting;
    private bool _canSubmit = true;
    private bool IsPrimaryButtonEnabled { set { _canSubmit = value; PrimaryAction.IsEnabled = value && !_submitting; } }
    private string PrimaryButtonText { set => PrimaryAction.Content = value; }
    private string SecondaryButtonText
    {
        set { SecondaryAction.Content = value; SecondaryAction.Visibility = string.IsNullOrEmpty(value) ? Visibility.Collapsed : Visibility.Visible; }
    }

    public void SetSubmitting(bool value)
    {
        _submitting = value;
        PrimaryAction.IsEnabled = !value && _canSubmit;
        SecondaryAction.IsEnabled = !value;
        SaveCoverDirectBtn.IsEnabled = !value;
    }

    private void RequestSubmit(IReadOnlyList<CreateTaskItem> tasks)
    {
        if (_submitting || tasks.Count == 0 || SubmitRequested is null) return;
        SetSubmitting(true);
        SubmitRequested.Invoke(this, tasks);
    }

    public FormatSelectorPanel(AppServices services, VideoMetadata metadata)
    {
        _services = services;
        _metadata = metadata;
        InitializeComponent();

        for (var i = 0; i < metadata.MultiMediaList.Count; i++)
        {
            _selectedEpisodes.Add(i);
        }

        _suppressSelectionEvents = true;
        try
        {
            var first = CurrentMedia();
            _downloadType = first.IsGif ? "GIF" : "VIDEO_WITH_AUDIO";
            _selectedFormatId = first.AvailableVideoFormats.FirstOrDefault()?.FormatId ?? string.Empty;

            SelectComboByTag(DownloadTypeCombo, _downloadType, fallbackIndex: 0);
            ContainerCombo.SelectedIndex = 0;
            CollectionRuleCombo.ItemsSource = _services.MainVm.RulesVm.QuickRuleChoices;
            if (CollectionRuleCombo.Items.Count > 0) CollectionRuleCombo.SelectedIndex = 0;
        }
        finally
        {
            _suppressSelectionEvents = false;
        }

        var needsRenderOnLoad = false;
        Loaded += (_, _) =>
        {
            _isClosed = false;
            _services.Loc.LanguageChanged -= OnLanguageChanged;
            _services.Loc.LanguageChanged += OnLanguageChanged;
            Localized.RefreshTree(this);
            ApplyResponsiveLayout(ActualWidth);
            if (needsRenderOnLoad)
            {
                needsRenderOnLoad = false;
                RenderMetadata();
            }
        };
        Unloaded += (_, _) =>
        {
            _services.Loc.LanguageChanged -= OnLanguageChanged;
            _isClosed = true;
            needsRenderOnLoad = true;
            _thumbCts?.Cancel();
            _thumbCts?.Dispose();
            _thumbCts = null;
            ThumbImage.Source = null;
        };

        RenderMetadata();
    }

    private static void SelectComboByTag(ComboBox combo, string tag, int fallbackIndex = 0)
    {
        var items = combo.Items.OfType<ComboBoxItem>().ToList();
        var idx = items.FindIndex(item => string.Equals(item.Tag as string, tag, StringComparison.Ordinal));
        combo.SelectedIndex = idx >= 0 ? idx : fallbackIndex;
    }

    private void FormatSelectorPanel_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        ApplyResponsiveLayout(e.NewSize.Width);
    }

    private void ApplyResponsiveLayout(double width)
    {
        if (width <= 0) return;

        // 1. 摘要头右侧封面控制区：宽窗口居右列，窄窗口折至第二行
        var compactHeader = _isCompactHeader switch
        {
            null => width < 500,
            true => width <= 516,
            false => width < 484
        };
        if (_isCompactHeader != compactHeader)
        {
            _isCompactHeader = compactHeader;
            if (compactHeader)
            {
                Grid.SetRow(CoverActionPanel, 1);
                Grid.SetColumn(CoverActionPanel, 0);
                Grid.SetColumnSpan(CoverActionPanel, 3);
                CoverActionPanel.Orientation = Orientation.Horizontal;
                CoverActionPanel.Spacing = 10;
                CoverActionPanel.HorizontalAlignment = HorizontalAlignment.Left;
                SaveCoverDirectBtn.HorizontalAlignment = HorizontalAlignment.Left;
            }
            else
            {
                Grid.SetRow(CoverActionPanel, 0);
                Grid.SetColumn(CoverActionPanel, 2);
                Grid.SetColumnSpan(CoverActionPanel, 1);
                CoverActionPanel.Orientation = Orientation.Vertical;
                CoverActionPanel.Spacing = 4;
                CoverActionPanel.HorizontalAlignment = HorizontalAlignment.Right;
                SaveCoverDirectBtn.HorizontalAlignment = HorizontalAlignment.Stretch;
            }
        }

        // 2. 双栏并排下拉：>=400 单行 2 列；<400 两行竖排
        var layoutMode = width >= 400 ? 0 : 1;
        if (_currentLayoutMode != layoutMode)
        {
            _currentLayoutMode = layoutMode;
            if (layoutMode == 0)
            {
                SetGridPlacement(DownloadTypeCombo, 0, 0, 1);
                SetGridPlacement(ContainerCombo, 0, 1, 1);
            }
            else
            {
                SetGridPlacement(DownloadTypeCombo, 0, 0, 2);
                SetGridPlacement(ContainerCombo, 1, 0, 2);
            }
        }

        // 3. 同步调整现有视频流方格宽度
        var (_, tileWidth) = OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(width, VideoFormatGrid.HorizontalSpacing);
        if (Math.Abs(tileWidth - _currentTileWidth) >= 1)
        {
            _currentTileWidth = tileWidth;
            foreach (var child in VideoFormatGrid.Children)
            {
                if (child is Button btn && btn.Tag is string)
                {
                    btn.Width = tileWidth;
                }
            }
        }
    }

    private static void SetGridPlacement(FrameworkElement element, int row, int column, int columnSpan)
    {
        Grid.SetRow(element, row);
        Grid.SetColumn(element, column);
        Grid.SetColumnSpan(element, columnSpan);
    }

    private void OnLanguageChanged(object? sender, string language)
    {
        Localized.RefreshTree(this);
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
        var rawSite = string.IsNullOrWhiteSpace(media.SiteName) ? _metadata.SiteName : media.SiteName;
        SiteBadgeText.Text = _services.Loc.T(rawSite);
        GifBadge.Visibility = media.IsGif ? Visibility.Visible : Visibility.Collapsed;

        var author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author;
        DurationAuthorText.Text = string.Join(" · ", new[] { media.DurationText, author }.Where(s => !string.IsNullOrWhiteSpace(s)));

        if (rawSite == "哔哩哔哩")
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

        UpdateDownloadTypeUi();
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
            if (bmp is not null)
            {
                NativeShellHelper.PlayFadeIn(ThumbImage, durationMs: 140);
            }
            }
        }
        catch
        {
        }
    }

    private void RenderSingleParts()
    {
        SinglePartsPrompt.Text = _services.Loc.T(_metadata.SiteName == "哔哩哔哩"
            ? "点击切换当前预览与下载的分P/分集："
            : "点击切换当前预览与独立下载的视频：");

        SinglePartsChipsPanel.Children.Clear();
        for (var i = 0; i < _metadata.MultiMediaList.Count; i++)
        {
            var index = i;
            var item = _metadata.MultiMediaList[i];
            var tagName = item.Title.Contains(" - P", StringComparison.Ordinal) ? $"P{i + 1}" : _services.Loc.T("视频 %d", i + 1);
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
        VideoFormatGrid.Children.Clear();
        if (ActualWidth > 0)
        {
            (_, _currentTileWidth) = OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(ActualWidth, VideoFormatGrid.HorizontalSpacing);
        }

        if (formats.Count == 0)
        {
            VideoFormatGrid.Children.Add(new Border
            {
                Style = Resources["TileFallbackBorderStyle"] as Style,
                Child = new TextBlock
                {
                    Text = _services.Loc.T("将自动选择最佳可用源"),
                    Style = Resources["TileSecondaryTextStyle"] as Style,
                    TextWrapping = TextWrapping.WrapWholeWords
                }
            });
            return;
        }

        var selectedFmtId = formats.Any(f => f.FormatId == _selectedFormatId)
            ? _selectedFormatId
            : formats[0].FormatId;
        _selectedFormatId = selectedFmtId;

        foreach (var fmt in formats)
        {
            var isSelected = fmt.FormatId == selectedFmtId;
            var tile = BuildFormatTile(fmt, isSelected);
            VideoFormatGrid.Children.Add(tile);
        }
    }

    private Button BuildFormatTile(FormatOption fmt, bool isSelected)
    {
        var secondaryStyle = Resources["TileSecondaryTextStyle"] as Style;
        var tagBorderStyle = Application.Current.Resources["OmniTagBorderStyle"] as Style;
        var goldBadgeBorderStyle = Resources["MetallicGoldBadgeBorderStyle"] as Style;
        var goldBadgeTextStyle = Resources["MetallicGoldBadgeTextStyle"] as Style;

        // 顶行：左侧分辨率主标题 + 框内右上角金属金高亮规格标签 (<4K> / <8K> / <HDR> / <Hi-Res>...)
        var topRow = new Grid { ColumnSpacing = 6 };
        topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var resText = new TextBlock
        {
            Text = _services.Loc.T(fmt.TileResolutionText),
            Style = Resources["TilePrimaryTextStyle"] as Style,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(resText, 0);
        topRow.Children.Add(resText);

        var premiumBadges = fmt.GetPremiumBadges();
        if (premiumBadges.Count > 0)
        {
            var badgeStack = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Spacing = 4,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top
            };
            foreach (var badgeLabel in premiumBadges)
            {
                // 内层顶部高光斜角边框，增强拉丝金属铭牌立体质感
                var bevelBorder = new Border
                {
                    Style = goldBadgeBorderStyle,
                    Child = new TextBlock
                    {
                        Text = badgeLabel,
                        Style = goldBadgeTextStyle
                    }
                };
                badgeStack.Children.Add(bevelBorder);
            }
            Grid.SetColumn(badgeStack, 1);
            topRow.Children.Add(badgeStack);
        }
        else
        {
            var tier = fmt.TileTierBadgeText;
            if (!string.IsNullOrEmpty(tier))
            {
                var tierBadge = new Border
                {
                    Style = tagBorderStyle,
                    Padding = new Thickness(6, 1, 6, 1),
                    HorizontalAlignment = HorizontalAlignment.Right,
                    VerticalAlignment = VerticalAlignment.Top,
                    Child = new TextBlock
                    {
                        Text = tier,
                        FontSize = 11,
                        Style = secondaryStyle
                    }
                };
                Grid.SetColumn(tierBadge, 1);
                topRow.Children.Add(tierBadge);
            }
        }

        // 中行：格式与编码（如 MP4 · H.264 · 60fps）
        var formatText = new TextBlock
        {
            Text = _services.Loc.T(fmt.TileFormatText),
            Style = secondaryStyle
        };

        // 底行：左侧预估体积或码率 + 右侧选中勾标（避免与右上角金属标签争抢空间）
        var bottomRow = new Grid { ColumnSpacing = 6 };
        bottomRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bottomRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var sizeText = new TextBlock
        {
            Text = _services.Loc.T(fmt.TileSizeText),
            Style = secondaryStyle,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(sizeText, 0);
        bottomRow.Children.Add(sizeText);

        var checkIcon = new FontIcon
        {
            Style = Resources["TileCheckIconStyle"] as Style,
            Visibility = isSelected ? Visibility.Visible : Visibility.Collapsed
        };
        Grid.SetColumn(checkIcon, 1);
        bottomRow.Children.Add(checkIcon);

        var stack = new StackPanel { Spacing = 3 };
        stack.Children.Add(topRow);
        stack.Children.Add(formatText);
        stack.Children.Add(bottomRow);

        // 统一使用简洁中性卡片样式，仅通过边框强调选中状态，不再整卡染金
        var styleKey = isSelected ? "FormatTileSelectedStyle" : "FormatTileNormalStyle";
        var button = new Button
        {
            Tag = fmt.FormatId,
            Width = _currentTileWidth,
            Style = Resources[styleKey] as Style,
            Content = stack
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, fmt.DisplaySummary);
        button.Click += FormatTile_Click;

        return button;
    }

    private void FormatTile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button clicked || clicked.Tag is not string fmtId) return;
        _selectedFormatId = fmtId;

        UpdateFormatTileSelectionVisuals();
        UpdateActionButtonsText();
    }

    private void UpdateFormatTileSelectionVisuals()
    {
        var normalStyle = Resources["FormatTileNormalStyle"] as Style;
        var selectedStyle = Resources["FormatTileSelectedStyle"] as Style;

        foreach (var child in VideoFormatGrid.Children)
        {
            if (child is not Button btn || btn.Tag is not string fid) continue;
            var selected = fid == _selectedFormatId;
            btn.Style = selected ? selectedStyle : normalStyle;
            if (btn.Content is StackPanel sp && sp.Children.LastOrDefault() is Grid bottomRow)
            {
                var check = bottomRow.Children.OfType<FontIcon>().FirstOrDefault();
                if (check is not null)
                {
                    check.Visibility = selected ? Visibility.Visible : Visibility.Collapsed;
                }
            }
        }
    }

    private void UpdateDownloadTypeUi()
    {
        var showVideo = _downloadType is "VIDEO_WITH_AUDIO" or "VIDEO_ONLY";
        ContainerCombo.Visibility = showVideo ? Visibility.Visible : Visibility.Collapsed;
        VideoOptionsPanel.Visibility = showVideo ? Visibility.Visible : Visibility.Collapsed;
        AudioOptionsPanel.Visibility = _downloadType == "AUDIO_ONLY" ? Visibility.Visible : Visibility.Collapsed;
        GifHintBar.Visibility = _downloadType == "GIF" ? Visibility.Visible : Visibility.Collapsed;
        var activePanel = showVideo ? (UIElement)VideoOptionsPanel : (_downloadType == "AUDIO_ONLY" ? AudioOptionsPanel : GifHintBar);
        NativeShellHelper.PlayFadeIn(activePanel, durationMs: 140, fromOffsetY: 5);

        ResolutionHintText.Text = _services.Loc.T(_downloadType == "VIDEO_ONLY"
            ? "纯画面模式：自动去除音轨导出静音视频"
            : "视频模式：自动匹配并无损合并最佳音轨");
    }

    private void UpdateActionButtonsText()
    {
        var media = CurrentMedia();
        var hasCover = !string.IsNullOrWhiteSpace(media.ThumbnailUrl) || !string.IsNullOrWhiteSpace(_metadata.ThumbnailUrl);
        var coverSuffix = SaveCoverCheck.IsChecked == true && hasCover ? _services.Loc.T(" + 封面") : string.Empty;

        var selectedFmt = media.AvailableVideoFormats.FirstOrDefault(f => f.FormatId == _selectedFormatId);
        var resText = selectedFmt?.ResolutionLabel ?? _services.Loc.T("最高源画质");

        var hasMulti = _metadata.MultiMediaList.Count > 1;
        if (_isCollectionMode && hasMulti)
        {
            var count = _selectedEpisodes.Count;
            IsPrimaryButtonEnabled = count > 0;
            PrimaryButtonText = count == 0
                ? _services.Loc.T("请至少勾选一个视频")
                : _downloadType switch
                {
                    "AUDIO_ONLY" => _services.Loc.T("下载所选音频 (%d 首 · %s)", count, _audioFormat.ToUpperInvariant()) + coverSuffix,
                    "GIF" => _services.Loc.T("下载所选动图 (%d 个)", count) + coverSuffix,
                    _ => _services.Loc.T("下载所选合集 (%d 集 · %s)", count, resText) + coverSuffix
                };
            SecondaryButtonText = _services.Loc.T("全部下载 (%d 集)", _metadata.MultiMediaList.Count) + coverSuffix;
        }
        else
        {
            IsPrimaryButtonEnabled = true;
            PrimaryButtonText = _downloadType switch
            {
                "VIDEO_WITH_AUDIO" => _services.Loc.T("开始下载") + $" ({resText}){coverSuffix}",
                "VIDEO_ONLY" => _services.Loc.T("纯画面") + $" ({resText}){coverSuffix}",
                "AUDIO_ONLY" => _services.Loc.T("音频") + $" ({_audioFormat.ToUpperInvariant()}){coverSuffix}",
                "GIF" => "GIF" + coverSuffix,
                _ => _services.Loc.T("开始下载")
            };
            SecondaryButtonText = hasMulti ? _services.Loc.T("切换为合集多选下载 (%d 集)", _metadata.MultiMediaList.Count) : string.Empty;
        }
    }

    private QualitySelection BuildPolicy(bool isBatchCollection)
    {
        var containerTag = (ContainerCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "mp4";
        var selectedFmt = CurrentMedia().AvailableVideoFormats.FirstOrDefault(f => f.FormatId == _selectedFormatId);
        var policyTag = selectedFmt is null ? "BEST_AVAILABLE" : "EXACT_FORMAT";
        return OmniDomainLogic.BuildQualityPolicy(policyTag, containerTag, allowLowerQuality: true, isBatchCollection, selectedFmt);
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

        if (SaveCoverCheck.IsChecked == true)
        {
            var coverUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl;
            if (!string.IsNullOrWhiteSpace(coverUrl))
            {
                tasks.Add(new CreateTaskItem
                {
                    Url = coverUrl,
                    Title = _services.Loc.T("%s - 封面", string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title),
                    DownloadType = "COVER",
                    ThumbnailUrl = coverUrl,
                    Author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author
                });
            }
        }

        return tasks;
    }

    private void PrimaryAction_Click(object sender, RoutedEventArgs e) => RequestSubmit(BuildTasks(allEpisodes: false));

    private void SecondaryAction_Click(object sender, RoutedEventArgs e)
    {
        if (_submitting || _metadata.MultiMediaList.Count <= 1) return;
        if (!_isCollectionMode)
        {
            _isCollectionMode = true;
            ModeSelectorBar.SelectedItem = CollectionModeItem;
            UpdateCollectionPanels();
            UpdateActionButtonsText();
            return;
        }
        RequestSubmit(BuildTasks(allEpisodes: true));
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

    private async void ApplyCollectionRule_Click(object sender, RoutedEventArgs e)
    {
        if (_submitting) return;
        var choice = CollectionRuleCombo.SelectedItem as RuleSelectorChoice;
        var ruleMode = choice?.Mode ?? "auto";
        var ruleId = choice?.RuleId ?? string.Empty;
        if (ruleMode == "bypass")
        {
            _services.MainVm.Notify("当前已选择“不使用规则”，请手动勾选分集后下载", InfoBarSeverity.Informational);
            return;
        }

        SetSubmitting(true);
        try
        {
            var resp = await _services.ApiClient.ExecuteRulesAsync(
                _metadata.Url,
                trigger: "collection",
                ruleMode: ruleMode,
                ruleId: ruleId,
                clientSource: "winui_collection");
            if (!resp.Matched || resp.Results.Count == 0)
            {
                _services.MainVm.Notify("当前合集未匹配到启用规则，可手动勾选分集或先在「下载规则」配置规则", InfoBarSeverity.Informational);
                return;
            }
            var item = resp.Results[0];
            if (!string.IsNullOrWhiteSpace(item.Error))
            {
                _services.MainVm.Notify(item.Error, InfoBarSeverity.Error);
                return;
            }
            if (item.Status == "queued")
            {
                _services.MainVm.Notify("已按合集规则「%s」筛选入队 %d 个任务", InfoBarSeverity.Success, item.RuleName, item.PlannedCount);
                await _services.MainVm.RefreshTasksAsync();
                _services.MainVm.NavigateTo("tasks");
                return;
            }
            _services.MainVm.Notify("规则「%s」状态：%s", InfoBarSeverity.Informational, item.RuleName, item.Status);
        }
        catch (Exception ex)
        {
            _services.MainVm.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            SetSubmitting(false);
        }
    }

    private void SaveCoverCheck_Changed(object sender, RoutedEventArgs e) => UpdateActionButtonsText();

    private void SaveCoverDirectBtn_Click(object sender, RoutedEventArgs e)
    {
        var media = CurrentMedia();
        var coverUrl = string.IsNullOrWhiteSpace(media.ThumbnailUrl) ? _metadata.ThumbnailUrl : media.ThumbnailUrl;
        if (string.IsNullOrWhiteSpace(coverUrl)) return;

        RequestSubmit(new[]
        {
            new CreateTaskItem
            {
                Url = coverUrl,
                Title = _services.Loc.T("%s - 封面", string.IsNullOrWhiteSpace(media.Title) ? _metadata.Title : media.Title),
                DownloadType = "COVER",
                ThumbnailUrl = coverUrl,
                Author = string.IsNullOrWhiteSpace(media.Author) ? _metadata.Author : media.Author
            }
        });
    }

    private void DownloadTypeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelectionEvents || Localized.IsRefreshingSelection) return;
        if (DownloadTypeCombo.SelectedItem is ComboBoxItem item && item.Tag is string type)
        {
            _downloadType = type;
            UpdateDownloadTypeUi();
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
