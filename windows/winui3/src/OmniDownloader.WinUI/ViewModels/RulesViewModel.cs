using System.Collections.ObjectModel;
using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.ViewModels;

/// <summary>
/// 规则列表展示项（支持实时启用切换、优先级顺序显示与安全标记）
/// </summary>
public sealed partial class RuleListItemVm : ObservableObject
{
    public DownloadRuleDto Rule { get; }
    public string Id => Rule.Id;
    public string Name => Rule.Name;
    public int Priority => Rule.Priority;
    public bool IsEnabled
    {
        get => Rule.Enabled;
        set
        {
            if (Rule.Enabled != value)
            {
                Rule.Enabled = value;
                OnPropertyChanged();
            }
        }
    }
    public bool IsImportedUntrusted => Rule.ImportedUntrusted;
    public string SummaryLine =>
        $"{AppServices.Current.Loc.T("优先级 %d", Rule.Priority)} · " +
        $"{Rule.Download.Mode} · {Rule.Execution.Action}" +
        (Rule.Match.Platforms.Count > 0 ? $" · {string.Join("/", Rule.Match.Platforms)}" : string.Empty);

    public RuleListItemVm(DownloadRuleDto rule)
    {
        Rule = rule;
    }
}

/// <summary>
/// 下载规则管理 ViewModel：涵盖可视化编辑器、高级 JSON 编辑器、测试预览台、剪贴板监听设置、待确认队列与执行审计。
/// </summary>
public sealed partial class RulesViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private bool _suppressPolicySync;

    private string _activeSection = "rules"; // rules | test | logs
    private bool _rulesSystemEnabled = true;
    private bool _clipboardWatchEnabled;
    private bool _clipboardPaused;
    private int _clipboardDebounceMs = 550;
    private int _clipboardDedupWindowSec = 20;
    private bool _clipboardShowTrayNotice = true;
    private int _dedupCount;

    private bool _isLoading;
    private bool _isBusy;
    private bool _isJsonMode;
    private bool _isSchemaDocsOpen;

    // 编辑器字段（可视化表单）
    private string _editRuleId = string.Empty;
    private string _editName = string.Empty;
    private string _editDescription = string.Empty;
    private bool _editEnabled = true;
    private int _editPriority = 100;
    private bool _editTriggerManual = true;
    private bool _editTriggerCollection = true;
    private bool _editTriggerBatch = true;
    private bool _editTriggerClipboard = true;

    private string _editPlatformsText = string.Empty;
    private string _editDomainsText = string.Empty;
    private string _editUrlPatternsText = string.Empty;
    private string _editUrlMatchMode = "contains";
    private string _editMediaTypesText = string.Empty;
    private string _editTitleKeywordsText = string.Empty;
    private string _editTitleExcludeText = string.Empty;
    private string _editTitleRegex = string.Empty;
    private string _editAuthorKeywordsText = string.Empty;
    private int _editMinDurationSec;
    private int _editMaxDurationSec;

    private string _editCollectionMode = "all";
    private string _editCollectionEpisodes = string.Empty;
    private int _editCollectionRangeStart;
    private int _editCollectionRangeEnd;
    private string _editCollectionIncludeKw = string.Empty;
    private string _editCollectionExcludeKw = string.Empty;
    private bool _editSkipDownloaded = true;

    private string _editDownloadMode = "VIDEO_WITH_AUDIO";
    private bool _editSaveCover;
    private string _editImageArchiveMode = "zip";

    private string _editQualityStrategy = "best";
    private int _editQualityResolution;
    private string _editQualityCodec = "auto";
    private int _editQualityMinFps;
    private string _editQualityDynamicRange = "auto";
    private string _editQualityContainer = "mp4";
    private string _editAudioFormat = "mp3";
    private string _editQualityFallback = "best_available";

    private string _editFilenameTemplate = "{title}";
    private string _editCategoryDir = string.Empty;
    private bool _editCollectionSubfolder = true;
    private string _editDuplicatePolicy = "rename";

    private string _editExecutionAction = "confirm";
    private int _editMaxTasks = 100;
    private string _editorJsonText = string.Empty;
    private string _jsonValidationMessage = string.Empty;
    private bool _jsonValidationError;

    // 测试链接预览台
    private string _testUrl = string.Empty;
    private string _testTrigger = "manual";
    private string _testRuleId = string.Empty;
    private bool _isTesting;
    private bool _hasTestResult;
    private string _testHeadline = string.Empty;
    private string _testDetailsText = string.Empty;

    public string ActiveSection { get => _activeSection; set => SetProperty(ref _activeSection, value); }
    public bool RulesSystemEnabled
    {
        get => _rulesSystemEnabled;
        set { if (SetProperty(ref _rulesSystemEnabled, value) && !_suppressPolicySync) _ = PersistRulePolicyAsync(); }
    }
    public bool ClipboardWatchEnabled
    {
        get => _clipboardWatchEnabled;
        set
        {
            if (SetProperty(ref _clipboardWatchEnabled, value))
            {
                OnPropertyChanged(nameof(ClipboardStatusSummary));
                if (!_suppressPolicySync) _ = PersistRulePolicyAsync();
            }
        }
    }
    public bool ClipboardPaused
    {
        get => _clipboardPaused;
        set
        {
            if (SetProperty(ref _clipboardPaused, value))
            {
                OnPropertyChanged(nameof(ClipboardStatusSummary));
                OnPropertyChanged(nameof(ClipboardPauseButtonText));
                if (!_suppressPolicySync) _ = PersistRulePolicyAsync();
            }
        }
    }
    public int ClipboardDebounceMs { get => _clipboardDebounceMs; set => SetProperty(ref _clipboardDebounceMs, value); }
    public int ClipboardDedupWindowSec { get => _clipboardDedupWindowSec; set => SetProperty(ref _clipboardDedupWindowSec, value); }
    public bool ClipboardShowTrayNotice { get => _clipboardShowTrayNotice; set => SetProperty(ref _clipboardShowTrayNotice, value); }
    public int DedupCount { get => _dedupCount; set => SetProperty(ref _dedupCount, value); }

    public bool IsLoading { get => _isLoading; set => SetProperty(ref _isLoading, value); }
    public bool IsBusy { get => _isBusy; set => SetProperty(ref _isBusy, value); }
    public bool IsJsonMode
    {
        get => _isJsonMode;
        set
        {
            if (SetProperty(ref _isJsonMode, value) && value)
            {
                SyncVisualFormToJson();
            }
        }
    }
    public bool IsSchemaDocsOpen { get => _isSchemaDocsOpen; set => SetProperty(ref _isSchemaDocsOpen, value); }

    public string EditRuleId { get => _editRuleId; set => SetProperty(ref _editRuleId, value); }
    public string EditName { get => _editName; set => SetProperty(ref _editName, value); }
    public string EditDescription { get => _editDescription; set => SetProperty(ref _editDescription, value); }
    public bool EditEnabled { get => _editEnabled; set => SetProperty(ref _editEnabled, value); }
    public int EditPriority { get => _editPriority; set => SetProperty(ref _editPriority, value); }
    public bool EditTriggerManual { get => _editTriggerManual; set => SetProperty(ref _editTriggerManual, value); }
    public bool EditTriggerCollection { get => _editTriggerCollection; set => SetProperty(ref _editTriggerCollection, value); }
    public bool EditTriggerBatch { get => _editTriggerBatch; set => SetProperty(ref _editTriggerBatch, value); }
    public bool EditTriggerClipboard { get => _editTriggerClipboard; set => SetProperty(ref _editTriggerClipboard, value); }

    public string EditPlatformsText { get => _editPlatformsText; set => SetProperty(ref _editPlatformsText, value); }
    public string EditDomainsText { get => _editDomainsText; set => SetProperty(ref _editDomainsText, value); }
    public string EditUrlPatternsText { get => _editUrlPatternsText; set => SetProperty(ref _editUrlPatternsText, value); }
    public string EditUrlMatchMode { get => _editUrlMatchMode; set => SetProperty(ref _editUrlMatchMode, value); }
    public string EditMediaTypesText { get => _editMediaTypesText; set => SetProperty(ref _editMediaTypesText, value); }
    public string EditTitleKeywordsText { get => _editTitleKeywordsText; set => SetProperty(ref _editTitleKeywordsText, value); }
    public string EditTitleExcludeText { get => _editTitleExcludeText; set => SetProperty(ref _editTitleExcludeText, value); }
    public string EditTitleRegex { get => _editTitleRegex; set => SetProperty(ref _editTitleRegex, value); }
    public string EditAuthorKeywordsText { get => _editAuthorKeywordsText; set => SetProperty(ref _editAuthorKeywordsText, value); }
    public int EditMinDurationSec { get => _editMinDurationSec; set => SetProperty(ref _editMinDurationSec, value); }
    public int EditMaxDurationSec { get => _editMaxDurationSec; set => SetProperty(ref _editMaxDurationSec, value); }

    public string EditCollectionMode { get => _editCollectionMode; set => SetProperty(ref _editCollectionMode, value); }
    public string EditCollectionEpisodes { get => _editCollectionEpisodes; set => SetProperty(ref _editCollectionEpisodes, value); }
    public int EditCollectionRangeStart { get => _editCollectionRangeStart; set => SetProperty(ref _editCollectionRangeStart, value); }
    public int EditCollectionRangeEnd { get => _editCollectionRangeEnd; set => SetProperty(ref _editCollectionRangeEnd, value); }
    public string EditCollectionIncludeKw { get => _editCollectionIncludeKw; set => SetProperty(ref _editCollectionIncludeKw, value); }
    public string EditCollectionExcludeKw { get => _editCollectionExcludeKw; set => SetProperty(ref _editCollectionExcludeKw, value); }
    public bool EditSkipDownloaded { get => _editSkipDownloaded; set => SetProperty(ref _editSkipDownloaded, value); }

    public string EditDownloadMode { get => _editDownloadMode; set => SetProperty(ref _editDownloadMode, value); }
    public bool EditSaveCover { get => _editSaveCover; set => SetProperty(ref _editSaveCover, value); }
    public string EditImageArchiveMode { get => _editImageArchiveMode; set => SetProperty(ref _editImageArchiveMode, value); }

    public string EditQualityStrategy { get => _editQualityStrategy; set => SetProperty(ref _editQualityStrategy, value); }
    public int EditQualityResolution { get => _editQualityResolution; set => SetProperty(ref _editQualityResolution, value); }
    public string EditQualityCodec { get => _editQualityCodec; set => SetProperty(ref _editQualityCodec, value); }
    public int EditQualityMinFps { get => _editQualityMinFps; set => SetProperty(ref _editQualityMinFps, value); }
    public string EditQualityDynamicRange { get => _editQualityDynamicRange; set => SetProperty(ref _editQualityDynamicRange, value); }
    public string EditQualityContainer { get => _editQualityContainer; set => SetProperty(ref _editQualityContainer, value); }
    public string EditAudioFormat { get => _editAudioFormat; set => SetProperty(ref _editAudioFormat, value); }
    public string EditQualityFallback { get => _editQualityFallback; set => SetProperty(ref _editQualityFallback, value); }

    public string EditFilenameTemplate { get => _editFilenameTemplate; set => SetProperty(ref _editFilenameTemplate, value); }
    public string EditCategoryDir { get => _editCategoryDir; set => SetProperty(ref _editCategoryDir, value); }
    public bool EditCollectionSubfolder { get => _editCollectionSubfolder; set => SetProperty(ref _editCollectionSubfolder, value); }
    public string EditDuplicatePolicy { get => _editDuplicatePolicy; set => SetProperty(ref _editDuplicatePolicy, value); }

    public string EditExecutionAction { get => _editExecutionAction; set => SetProperty(ref _editExecutionAction, value); }
    public int EditMaxTasks { get => _editMaxTasks; set => SetProperty(ref _editMaxTasks, value); }
    public string EditorJsonText { get => _editorJsonText; set => SetProperty(ref _editorJsonText, value); }
    public string JsonValidationMessage
    {
        get => _jsonValidationMessage;
        set { if (SetProperty(ref _jsonValidationMessage, value)) OnPropertyChanged(nameof(HasJsonValidationMessage)); }
    }
    public bool JsonValidationError { get => _jsonValidationError; set => SetProperty(ref _jsonValidationError, value); }
    public bool HasJsonValidationMessage => !string.IsNullOrWhiteSpace(JsonValidationMessage);

    public string TestUrl { get => _testUrl; set => SetProperty(ref _testUrl, value); }
    public string TestTrigger { get => _testTrigger; set => SetProperty(ref _testTrigger, value); }
    public string TestRuleId { get => _testRuleId; set => SetProperty(ref _testRuleId, value); }
    public bool IsTesting { get => _isTesting; set => SetProperty(ref _isTesting, value); }
    public bool HasTestResult { get => _hasTestResult; set => SetProperty(ref _hasTestResult, value); }
    public string TestHeadline { get => _testHeadline; set => SetProperty(ref _testHeadline, value); }
    public string TestDetailsText { get => _testDetailsText; set => SetProperty(ref _testDetailsText, value); }

    public ObservableCollection<RuleListItemVm> Rules { get; } = new();
    public ObservableCollection<PendingConfirmationDto> PendingConfirmations { get; } = new();
    public ObservableCollection<RuleAuditItemDto> AuditLogs { get; } = new();
    public ObservableCollection<RuleSchemaDocItem> SchemaDocs { get; } = new();
    public ObservableCollection<RuleSelectorChoice> QuickRuleChoices { get; } = new();

    public LocalizationService Loc => _services.Loc;
    public int PendingConfirmCount => PendingConfirmations.Count;
    public Visibility PendingConfirmBadgeVisibility => PendingConfirmCount > 0 ? Visibility.Visible : Visibility.Collapsed;
    public bool HasPendingConfirmations => PendingConfirmations.Count > 0;
    public bool HasRules => Rules.Count > 0;
    public bool HasAuditLogs => AuditLogs.Count > 0;

    public string ClipboardStatusSummary => !RulesSystemEnabled
        ? _services.Loc.T("规则系统已总控关闭")
        : (!ClipboardWatchEnabled
            ? _services.Loc.T("未开启剪贴板监听")
            : (ClipboardPaused
                ? _services.Loc.T("剪贴板监听已暂停")
                : _services.Loc.T("剪贴板系统事件监听运行中")));

    public string ClipboardPauseButtonText => ClipboardPaused
        ? _services.Loc.T("恢复监听")
        : _services.Loc.T("一键暂停");

    public event EventHandler<RuleSettingsDto>? ClipboardPolicyChanged;
    public event EventHandler? RulesCatalogUpdated;

    public RulesViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        ResetEditorToNewRule();
        RebuildQuickChoices();
    }

    public async Task LoadOverviewAsync()
    {
        if (!_main.IsBackendConnected) return;
        IsLoading = true;
        try
        {
            var ov = await _services.ApiClient.GetRulesOverviewAsync().ConfigureAwait(false);
            var audits = await _services.ApiClient.GetRuleAuditLogsAsync(100).ConfigureAwait(false);

            _main.RunOnUi(() =>
            {
                _suppressPolicySync = true;
                try
                {
                    RulesSystemEnabled = ov.Settings.RulesSystemEnabled;
                    ClipboardWatchEnabled = ov.Settings.ClipboardWatchEnabled;
                    ClipboardPaused = ov.Settings.ClipboardPaused;
                    ClipboardDebounceMs = ov.Settings.ClipboardDebounceMs;
                    ClipboardDedupWindowSec = ov.Settings.ClipboardDedupWindowSec;
                    ClipboardShowTrayNotice = ov.Settings.ClipboardShowTrayNotice;
                }
                finally
                {
                    _suppressPolicySync = false;
                }

                DedupCount = ov.DedupSummary.TotalCount;

                Rules.Clear();
                foreach (var r in ov.Rules)
                {
                    Rules.Add(new RuleListItemVm(r));
                }
                OnPropertyChanged(nameof(HasRules));

                PendingConfirmations.Clear();
                foreach (var p in ov.PendingConfirmations)
                {
                    PendingConfirmations.Add(p);
                }
                OnPropertyChanged(nameof(PendingConfirmCount));
                OnPropertyChanged(nameof(PendingConfirmBadgeVisibility));
                OnPropertyChanged(nameof(HasPendingConfirmations));

                AuditLogs.Clear();
                foreach (var a in audits.Items)
                {
                    AuditLogs.Add(a);
                }
                OnPropertyChanged(nameof(HasAuditLogs));

                if (SchemaDocs.Count == 0 && ov.SchemaDocs.Count > 0)
                {
                    foreach (var doc in ov.SchemaDocs) SchemaDocs.Add(doc);
                }

                RebuildQuickChoices();
                OnPropertyChanged(nameof(ClipboardStatusSummary));
                RaisePolicyChanged();
            });
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("RulesViewModel.LoadOverview", ex);
        }
        finally
        {
            _main.RunOnUi(() => IsLoading = false);
        }
    }

    public void RebuildQuickChoices()
    {
        QuickRuleChoices.Clear();
        QuickRuleChoices.Add(new RuleSelectorChoice
        {
            Mode = "auto",
            RuleId = string.Empty,
            DisplayName = _services.Loc.T("自动匹配已启用规则")
        });
        QuickRuleChoices.Add(new RuleSelectorChoice
        {
            Mode = "bypass",
            RuleId = string.Empty,
            DisplayName = _services.Loc.T("不使用规则（按手动参数）")
        });
        foreach (var item in Rules)
        {
            var tag = item.IsEnabled ? "" : $" ({_services.Loc.T("已禁用")})";
            QuickRuleChoices.Add(new RuleSelectorChoice
            {
                Mode = "specific",
                RuleId = item.Id,
                DisplayName = $"{item.Name}{tag}"
            });
        }
        RulesCatalogUpdated?.Invoke(this, EventArgs.Empty);
    }

    private void RaisePolicyChanged()
    {
        ClipboardPolicyChanged?.Invoke(this, new RuleSettingsDto
        {
            RulesSystemEnabled = RulesSystemEnabled,
            ClipboardWatchEnabled = ClipboardWatchEnabled,
            ClipboardPaused = ClipboardPaused,
            ClipboardDebounceMs = ClipboardDebounceMs,
            ClipboardDedupWindowSec = ClipboardDedupWindowSec,
            ClipboardShowTrayNotice = ClipboardShowTrayNotice
        });
    }

    [RelayCommand]
    public async Task ToggleClipboardPauseAsync()
    {
        ClipboardPaused = !ClipboardPaused;
        await PersistRulePolicyAsync();
    }

    public async Task PersistRulePolicyAsync()
    {
        var dto = new RuleSettingsDto
        {
            RulesSystemEnabled = RulesSystemEnabled,
            ClipboardWatchEnabled = ClipboardWatchEnabled,
            ClipboardPaused = ClipboardPaused,
            ClipboardDebounceMs = Math.Clamp(ClipboardDebounceMs, 200, 5000),
            ClipboardDedupWindowSec = Math.Clamp(ClipboardDedupWindowSec, 3, 3600),
            ClipboardShowTrayNotice = ClipboardShowTrayNotice
        };
        _main.RunOnUi(() =>
        {
            OnPropertyChanged(nameof(ClipboardStatusSummary));
            RaisePolicyChanged();
        });
        if (!_main.IsBackendConnected) return;
        try
        {
            await _services.ApiClient.UpdateRuleSettingsAsync(dto).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public void ResetEditorToNewRule()
    {
        var blank = new DownloadRuleDto
        {
            Name = _services.Loc.T("新建下载规则"),
            Priority = (Rules.Count + 1) * 10,
            Execution = new RuleExecutionConfigDto { Action = "confirm", MaxTasks = 100 }
        };
        LoadRuleIntoEditor(blank);
        EditRuleId = string.Empty;
        JsonValidationMessage = string.Empty;
    }

    public void LoadRuleIntoEditor(DownloadRuleDto rule)
    {
        EditRuleId = rule.Id;
        EditName = rule.Name;
        EditDescription = rule.Description;
        EditEnabled = rule.Enabled;
        EditPriority = rule.Priority;
        EditTriggerManual = rule.Triggers.Contains("manual");
        EditTriggerCollection = rule.Triggers.Contains("collection");
        EditTriggerBatch = rule.Triggers.Contains("batch");
        EditTriggerClipboard = rule.Triggers.Contains("clipboard");

        EditPlatformsText = string.Join(", ", rule.Match.Platforms);
        EditDomainsText = string.Join(", ", rule.Match.Domains);
        EditUrlPatternsText = string.Join(", ", rule.Match.UrlPatterns);
        EditUrlMatchMode = rule.Match.UrlMatchMode;
        EditMediaTypesText = string.Join(", ", rule.Match.MediaTypes);
        EditTitleKeywordsText = string.Join(", ", rule.Match.TitleKeywords);
        EditTitleExcludeText = string.Join(", ", rule.Match.TitleExcludeKeywords);
        EditTitleRegex = rule.Match.TitleRegex;
        EditAuthorKeywordsText = string.Join(", ", rule.Match.AuthorKeywords);
        EditMinDurationSec = rule.Match.MinDurationSec;
        EditMaxDurationSec = rule.Match.MaxDurationSec;

        EditCollectionMode = rule.Collection.Mode;
        EditCollectionEpisodes = rule.Collection.Episodes;
        EditCollectionRangeStart = rule.Collection.RangeStart;
        EditCollectionRangeEnd = rule.Collection.RangeEnd;
        EditCollectionIncludeKw = string.Join(", ", rule.Collection.IncludeKeywords);
        EditCollectionExcludeKw = string.Join(", ", rule.Collection.ExcludeKeywords);
        EditSkipDownloaded = rule.Collection.SkipDownloaded;

        EditDownloadMode = rule.Download.Mode;
        EditSaveCover = rule.Download.SaveCover;
        EditImageArchiveMode = rule.Download.ImageArchiveMode;

        EditQualityStrategy = rule.Quality.Strategy;
        EditQualityResolution = rule.Quality.Resolution;
        EditQualityCodec = rule.Quality.Codec;
        EditQualityMinFps = rule.Quality.MinFps;
        EditQualityDynamicRange = rule.Quality.DynamicRange;
        EditQualityContainer = rule.Quality.Container;
        EditAudioFormat = rule.Quality.AudioFormat;
        EditQualityFallback = rule.Quality.Fallback;

        EditFilenameTemplate = rule.Files.FilenameTemplate;
        EditCategoryDir = rule.Files.CategoryDir;
        EditCollectionSubfolder = rule.Files.CollectionSubfolder;
        EditDuplicatePolicy = rule.Files.DuplicatePolicy;

        EditExecutionAction = rule.Execution.Action;
        EditMaxTasks = rule.Execution.MaxTasks;

        EditorJsonText = rule.ToFormattedJson();
        JsonValidationMessage = string.Empty;
    }

    private static List<string> SplitTokens(string text) =>
        (text ?? string.Empty)
        .Split(new[] { ',', '，', ';', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Where(s => !string.IsNullOrWhiteSpace(s))
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .ToList();

    public DownloadRuleDto BuildRuleFromVisualForm()
    {
        var triggers = new List<string>();
        if (EditTriggerManual) triggers.Add("manual");
        if (EditTriggerCollection) triggers.Add("collection");
        if (EditTriggerBatch) triggers.Add("batch");
        if (EditTriggerClipboard) triggers.Add("clipboard");
        if (triggers.Count == 0) triggers.Add("manual");

        return new DownloadRuleDto
        {
            Id = EditRuleId,
            Name = string.IsNullOrWhiteSpace(EditName) ? _services.Loc.T("未命名规则") : EditName.Trim(),
            Description = EditDescription?.Trim() ?? string.Empty,
            Enabled = EditEnabled,
            Priority = Math.Clamp(EditPriority, 0, 99999),
            Triggers = triggers,
            Match = new RuleMatchConfigDto
            {
                Platforms = SplitTokens(EditPlatformsText),
                Domains = SplitTokens(EditDomainsText),
                UrlPatterns = SplitTokens(EditUrlPatternsText),
                UrlMatchMode = EditUrlMatchMode,
                MediaTypes = SplitTokens(EditMediaTypesText),
                TitleKeywords = SplitTokens(EditTitleKeywordsText),
                TitleExcludeKeywords = SplitTokens(EditTitleExcludeText),
                TitleRegex = EditTitleRegex?.Trim() ?? string.Empty,
                AuthorKeywords = SplitTokens(EditAuthorKeywordsText),
                MinDurationSec = Math.Max(0, EditMinDurationSec),
                MaxDurationSec = Math.Max(0, EditMaxDurationSec)
            },
            Collection = new RuleCollectionConfigDto
            {
                Mode = EditCollectionMode,
                Episodes = EditCollectionEpisodes?.Trim() ?? string.Empty,
                RangeStart = Math.Max(0, EditCollectionRangeStart),
                RangeEnd = Math.Max(0, EditCollectionRangeEnd),
                IncludeKeywords = SplitTokens(EditCollectionIncludeKw),
                ExcludeKeywords = SplitTokens(EditCollectionExcludeKw),
                SkipDownloaded = EditSkipDownloaded
            },
            Download = new RuleDownloadConfigDto
            {
                Mode = EditDownloadMode,
                SaveCover = EditSaveCover,
                ImageArchiveMode = EditImageArchiveMode
            },
            Quality = new RuleQualityConfigDto
            {
                Strategy = EditQualityStrategy,
                Resolution = Math.Max(0, EditQualityResolution),
                Codec = EditQualityCodec,
                MinFps = Math.Max(0, EditQualityMinFps),
                DynamicRange = EditQualityDynamicRange,
                Container = EditQualityContainer,
                AudioFormat = EditAudioFormat,
                Fallback = EditQualityFallback
            },
            Files = new RuleFileConfigDto
            {
                FilenameTemplate = string.IsNullOrWhiteSpace(EditFilenameTemplate) ? "{title}" : EditFilenameTemplate.Trim(),
                CategoryDir = EditCategoryDir?.Trim() ?? string.Empty,
                CollectionSubfolder = EditCollectionSubfolder,
                DuplicatePolicy = EditDuplicatePolicy
            },
            Execution = new RuleExecutionConfigDto
            {
                Action = EditExecutionAction,
                MaxTasks = Math.Clamp(EditMaxTasks, 1, 200)
            }
        };
    }

    public void SyncVisualFormToJson()
    {
        EditorJsonText = BuildRuleFromVisualForm().ToFormattedJson();
    }

    [RelayCommand]
    public async Task ValidateEditorJsonAsync()
    {
        if (string.IsNullOrWhiteSpace(EditorJsonText))
        {
            JsonValidationError = true;
            JsonValidationMessage = _services.Loc.T("JSON 内容不能为空");
            return;
        }
        try
        {
            var res = await _services.ApiClient.ValidateRuleJsonAsync(EditorJsonText);
            if (res.Valid)
            {
                JsonValidationError = false;
                var warn = res.Warnings.Count > 0 ? $" ({string.Join("; ", res.Warnings)})" : string.Empty;
                JsonValidationMessage = _services.Loc.T("JSON 语法与安全校验通过，共 %d 条规则%s", res.Count, warn);
                if (res.Rules.Count == 1)
                {
                    EditorJsonText = res.Rules[0].ToFormattedJson();
                }
            }
            else
            {
                JsonValidationError = true;
                JsonValidationMessage = res.Error;
            }
        }
        catch (Exception ex)
        {
            JsonValidationError = true;
            JsonValidationMessage = ex.Message;
        }
    }

    [RelayCommand]
    public async Task SaveRuleAsync()
    {
        IsBusy = true;
        try
        {
            DownloadRuleDto toSave;
            if (IsJsonMode)
            {
                var check = await _services.ApiClient.ValidateRuleJsonAsync(EditorJsonText);
                if (!check.Valid || check.Rules.Count == 0)
                {
                    JsonValidationError = true;
                    JsonValidationMessage = check.Error;
                    _main.Notify(check.Error, InfoBarSeverity.Error);
                    return;
                }
                toSave = check.Rules[0];
                if (!string.IsNullOrWhiteSpace(EditRuleId) && string.IsNullOrWhiteSpace(toSave.Id))
                {
                    toSave.Id = EditRuleId;
                }
                LoadRuleIntoEditor(toSave);
            }
            else
            {
                toSave = BuildRuleFromVisualForm();
            }

            var saved = await _services.ApiClient.SaveRuleAsync(toSave);
            if (saved.Rule is not null)
            {
                LoadRuleIntoEditor(saved.Rule);
            }
            await LoadOverviewAsync();
            _main.Notify("规则已保存", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            IsBusy = false;
        }
    }

    public async Task ToggleRuleEnabledAsync(RuleListItemVm item, bool enabled)
    {
        try
        {
            var copy = item.Rule.Clone();
            copy.Enabled = enabled;
            await _services.ApiClient.SaveRuleAsync(copy);
            await LoadOverviewAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task MoveRuleOrderAsync(RuleListItemVm item, int delta)
    {
        var ids = Rules.Select(r => r.Id).ToList();
        var idx = ids.IndexOf(item.Id);
        var next = idx + delta;
        if (idx < 0 || next < 0 || next >= ids.Count) return;
        (ids[idx], ids[next]) = (ids[next], ids[idx]);
        try
        {
            await _services.ApiClient.ReorderRulesAsync(ids);
            await LoadOverviewAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task DuplicateRuleAsync(RuleListItemVm item)
    {
        try
        {
            var res = await _services.ApiClient.DuplicateRuleAsync(item.Id);
            await LoadOverviewAsync();
            if (res.Rule is not null) LoadRuleIntoEditor(res.Rule);
            _main.Notify("已复制规则", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task DeleteRuleAsync(RuleListItemVm item)
    {
        var ok = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("删除下载规则"),
            _services.Loc.T("确定删除规则「%s」吗？不会影响已经创建的历史任务。", item.Name),
            _services.Loc.T("删除"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!ok) return;
        try
        {
            await _services.ApiClient.DeleteRuleAsync(item.Id);
            if (string.Equals(EditRuleId, item.Id, StringComparison.Ordinal))
            {
                ResetEditorToNewRule();
            }
            await LoadOverviewAsync();
            _main.Notify("规则已删除", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public async Task ImportRulesFileAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        try
        {
            var rawJson = await NativeShellHelper.PickTextFileContentAsync(owner, 1024 * 1024, ".omnirule.json", ".json");
            if (rawJson is null) return;
            var res = await _services.ApiClient.ImportRuleJsonAsync(rawJson);
            await LoadOverviewAsync();
            if (res.Rules.Count > 0) LoadRuleIntoEditor(res.Rules[0]);
            var msg = res.Warnings.Count > 0
                ? _services.Loc.T("已导入 %d 条规则；%s", res.ImportedCount, string.Join("；", res.Warnings))
                : _services.Loc.T("已安全导入 %d 条规则", res.ImportedCount);
            _main.Notify(msg, res.Warnings.Count > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public async Task ExportRulesFileAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        try
        {
            var json = await _services.ApiClient.ExportRulesBundleTextAsync();
            var saved = await NativeShellHelper.PickSaveTextFileAsync(owner, "omni-rules.omnirule.json", json);
            if (saved)
            {
                _main.Notify("已导出脱敏规则包 (.omnirule.json)", InfoBarSeverity.Success);
            }
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public async Task RunTestPreviewAsync()
    {
        var url = (TestUrl ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(url))
        {
            _main.Notify("请输入测试媒体链接", InfoBarSeverity.Warning);
            return;
        }
        IsTesting = true;
        try
        {
            var res = await _services.ApiClient.TestRulePreviewAsync(url, TestTrigger, TestRuleId);
            HasTestResult = true;
            if (!string.IsNullOrWhiteSpace(res.Error))
            {
                TestHeadline = _services.Loc.T("预览失败：%s", res.Error);
            }
            else if (!res.Matched)
            {
                TestHeadline = _services.Loc.T("未匹配启用规则（将沿用默认手动流程）：%s", res.Reason);
            }
            else
            {
                var rName = res.MatchedRule?.Name ?? "-";
                TestHeadline = _services.Loc.T("命中规则「%s」 · 动作：%s · 计划 %d 项", rName, res.Action, res.PlannedTasks.Count);
            }

            var sb = new System.Text.StringBuilder();
            if (!string.IsNullOrWhiteSpace(res.QualitySummary))
            {
                sb.AppendLine($"• 质量决策: {res.QualitySummary}");
            }
            foreach (var w in res.Warnings)
            {
                sb.AppendLine($"• 提示/回退: {w}");
            }
            if (res.PlannedTasks.Count > 0)
            {
                sb.AppendLine("• 预计输出队列:");
                foreach (var pt in res.PlannedTasks.Take(10))
                {
                    sb.AppendLine($"  - [{pt.DownloadType}] {pt.Title} -> {pt.PlannedPath}");
                }
                if (res.PlannedTasks.Count > 10) sb.AppendLine($"  - ... 其余 {res.PlannedTasks.Count - 10} 项");
            }
            if (res.SkippedItems.Count > 0)
            {
                sb.AppendLine($"• 合集筛选跳过 ({res.SkippedItems.Count}):");
                foreach (var sk in res.SkippedItems.Take(6))
                {
                    sb.AppendLine($"  - P{sk.Index} {sk.Title}: {sk.Reason}");
                }
            }
            if (res.DedupSkipped.Count > 0)
            {
                sb.AppendLine($"• 去重跳过 ({res.DedupSkipped.Count}):");
                foreach (var dp in res.DedupSkipped.Take(6))
                {
                    sb.AppendLine($"  - {dp.Title}: {dp.Reason}");
                }
            }
            if (res.Evaluations.Count > 0)
            {
                sb.AppendLine("• 规则优先级评估详情:");
                foreach (var ev in res.Evaluations)
                {
                    var state = !ev.PreMatched ? $"预筛跳过 ({ev.PreReason})" : (ev.PostMatched == true ? "完整匹配" : $"淘汰 ({string.Join("; ", ev.Reasons)})");
                    sb.AppendLine($"  - [{ev.Priority}] {ev.RuleName}: {state}");
                }
            }
            TestDetailsText = sb.ToString().Trim();
        }
        catch (Exception ex)
        {
            HasTestResult = true;
            TestHeadline = ex.Message;
            TestDetailsText = string.Empty;
        }
        finally
        {
            IsTesting = false;
        }
    }

    public async Task ApproveConfirmationAsync(string confirmId)
    {
        try
        {
            await _services.ApiClient.ApprovePendingRuleAsync(confirmId);
            await LoadOverviewAsync();
            await _main.RefreshTasksAsync();
            _main.Notify("已确认加入下载队列", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task DismissConfirmationAsync(string confirmId)
    {
        try
        {
            await _services.ApiClient.DismissPendingRuleAsync(confirmId);
            await LoadOverviewAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public async Task ClearAuditLogsAsync()
    {
        try
        {
            await _services.ApiClient.ClearRuleAuditLogsAsync();
            await LoadOverviewAsync();
            _main.Notify("已清空规则执行记录", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    public async Task ClearDedupIndexAsync()
    {
        try
        {
            await _services.ApiClient.ClearRuleDedupIndexAsync();
            await LoadOverviewAsync();
            _main.Notify("已清理去重记录索引", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }
}
