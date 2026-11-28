using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.ViewModels;

public sealed class BatchJobViewItem
{
    public BatchJob Job { get; }
    public string HeaderText => AppServices.Current.Loc.T("批次 · %d 条链接", Job.Items.Count);
    public bool IsRunning => Job.Items.Any(i => i.Status is "PENDING" or "RESOLVING");
    public bool HasRetryable => Job.Items.Any(i => i.Status is "FAILED" or "CANCELLED");
    public string StatusBadge => AppServices.Current.Loc.T(IsRunning ? "正在处理" : "已完成处理");
    public double ProgressPercent
    {
        get
        {
            if (Job.Items.Count == 0) return 0;
            var done = Job.Items.Count(i => i.Status is not ("PENDING" or "RESOLVING"));
            return done * 100.0 / Job.Items.Count;
        }
    }

    public IReadOnlyList<BatchJobRowViewItem> Rows { get; }

    public BatchJobViewItem(BatchJob job)
    {
        Job = job;
        Rows = job.Items.Select(item => new BatchJobRowViewItem(item)).ToList();
    }
}

public sealed class BatchJobRowViewItem
{
    public BatchJobItem Row { get; }
    public string Url => Row.Url;
    public string Note => string.IsNullOrWhiteSpace(Row.Note) ? StatusLabel : Row.Note;
    public bool CanSendToSingleAnalyze => Row.Status is "FAILED" or "CANCELLED";

    public string StatusLabel => Row.Status switch
    {
        "PENDING" => AppServices.Current.Loc.T("等待解析"),
        "RESOLVING" => AppServices.Current.Loc.T("解析中..."),
        "QUEUED" => AppServices.Current.Loc.T("已加入队列"),
        "FAILED" => AppServices.Current.Loc.T("失败"),
        "CANCELLED" => AppServices.Current.Loc.T("已停止"),
        "IGNORED" => AppServices.Current.Loc.T("已忽略"),
        "PARSE_ONLY" => AppServices.Current.Loc.T("仅解析"),
        "SKIPPED" => AppServices.Current.Loc.T("已去重跳过"),
        "WAIT_CONFIRM" => AppServices.Current.Loc.T("待确认"),
        _ => Row.Status
    };

    public BatchJobRowViewItem(BatchJobItem row) => Row = row;
}

/// <summary>
/// 批量下载页 ViewModel：文本识别 / 逐行输入、TXT 导入、草稿持久化与批次历史跟踪
/// </summary>
public sealed partial class BatchViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private bool _refreshing;
    private string _lastJobsSignature = string.Empty;

    private bool _isManualMode;
    private string _rawInputText = string.Empty;
    private string _validationError = string.Empty;
    private bool _isSubmitting;
    private string _selectedDownloadType = "VIDEO_WITH_AUDIO";
    private string _selectedAudioFormat = "mp3";
    private bool _downloadAllEpisodes;
    private string _selectedRuleMode = "auto";
    private string _selectedRuleId = string.Empty;
    private string _summaryText = string.Empty;

    public bool IsManualMode { get => _isManualMode; set => SetProperty(ref _isManualMode, value); }
    public string RawInputText
    {
        get => _rawInputText;
        set
        {
            if (SetProperty(ref _rawInputText, value))
            {
                _services.LocalState.Current.BatchRawInputDraft = value;
                _services.LocalState.SaveDeferred();
                ValidationError = string.Empty;
            }
        }
    }
    public string ValidationError
    {
        get => _validationError;
        set
        {
            if (SetProperty(ref _validationError, value))
            {
                OnPropertyChanged(nameof(HasValidationError));
            }
        }
    }
    public bool HasValidationError => !string.IsNullOrWhiteSpace(ValidationError);
    public bool IsSubmitting { get => _isSubmitting; set => SetProperty(ref _isSubmitting, value); }
    public string SelectedDownloadType
    {
        get => _selectedDownloadType;
        set { if (SetProperty(ref _selectedDownloadType, value)) OnPropertyChanged(nameof(IsAudioFormatVisible)); }
    }
    public string SelectedAudioFormat { get => _selectedAudioFormat; set => SetProperty(ref _selectedAudioFormat, value); }
    public bool DownloadAllEpisodes { get => _downloadAllEpisodes; set => SetProperty(ref _downloadAllEpisodes, value); }
    public string SelectedRuleMode { get => _selectedRuleMode; set => SetProperty(ref _selectedRuleMode, value); }
    public string SelectedRuleId { get => _selectedRuleId; set => SetProperty(ref _selectedRuleId, value); }
    public string SummaryText { get => _summaryText; set => SetProperty(ref _summaryText, value); }

    public ObservableCollection<string> DraftLinks { get; } = new();
    public ObservableCollection<BatchJobViewItem> Jobs { get; } = new();
    public ObservableCollection<RuleSelectorChoice> QuickRuleChoices => _main.RulesVm.QuickRuleChoices;

    public LocalizationService Loc => _services.Loc;
    public bool HasDraftLinks => DraftLinks.Count > 0;
    public bool HasJobs => Jobs.Count > 0;
    public bool IsAudioFormatVisible => SelectedDownloadType == "AUDIO_ONLY";
    public string DraftHeader => _services.Loc.T("待下载链接 (%d)", DraftLinks.Count);

    public BatchViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        _rawInputText = services.LocalState.Current.BatchRawInputDraft;
        foreach (var link in services.LocalState.Current.BatchDraftLinks)
        {
            DraftLinks.Add(link);
        }
        _services.Loc.LanguageChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(DraftHeader));
            if (_main.IsBackendConnected && Jobs.Count > 0)
            {
                _ = RefreshBatchesAsync();
            }
        };
    }

    private void SaveDraftLinks()
    {
        _services.LocalState.Current.BatchDraftLinks = DraftLinks.ToList();
        _services.LocalState.Save();
        OnPropertyChanged(nameof(HasDraftLinks));
        OnPropertyChanged(nameof(DraftHeader));
    }

    [RelayCommand]
    private void SetSmartMode() => IsManualMode = false;

    [RelayCommand]
    private void SetManualMode() => IsManualMode = true;

    [RelayCommand]
    private async Task PasteFromClipboardAsync()
    {
        var clip = await NativeShellHelper.ReadClipboardTextAsync();
        if (string.IsNullOrWhiteSpace(clip))
        {
            _main.Notify("剪贴板为空", InfoBarSeverity.Warning);
            return;
        }
        RawInputText = clip;
        AddFromInputCore();
    }

    [RelayCommand]
    private async Task ImportTxtFileAsync()
    {
        var owner = _services.Dialogs.OwnerWindow;
        if (owner is null) return;
        try
        {
            var text = await NativeShellHelper.PickTextFileContentAsync(owner, 100 * 1024, ".txt");
            if (text is null) return;
            RawInputText = string.IsNullOrWhiteSpace(RawInputText) ? text : $"{RawInputText}\n{text}";
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Warning);
        }
    }

    [RelayCommand]
    private void AddFromInput() => AddFromInputCore();

    public bool AddFromInputCore()
    {
        var added = OmniDomainLogic.ExtractLinks(RawInputText, IsManualMode, DraftLinks);
        if (added.Count == 0 && DraftLinks.Count == 0)
        {
            ValidationError = _services.Loc.T("未识别到有效链接（支持 http/https 链接或 BV 号）");
            return false;
        }
        if (DraftLinks.Count + added.Count > 100)
        {
            ValidationError = _services.Loc.T("单批最多添加 100 条去重链接");
            return false;
        }

        foreach (var link in added) DraftLinks.Add(link);
        if (added.Count > 0) RawInputText = string.Empty;
        ValidationError = string.Empty;
        SaveDraftLinks();
        return true;
    }

    [RelayCommand]
    private void RemoveDraftLink(string? url)
    {
        if (string.IsNullOrEmpty(url)) return;
        DraftLinks.Remove(url);
        SaveDraftLinks();
    }

    [RelayCommand]
    private async Task ClearDraftAsync()
    {
        var ok = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("清空输入"),
            _services.Loc.T("确定清空已识别的待下载链接列表吗？"),
            _services.Loc.T("清空"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!ok) return;

        DraftLinks.Clear();
        RawInputText = string.Empty;
        ValidationError = string.Empty;
        SaveDraftLinks();
    }

    [RelayCommand]
    private async Task StartBatchAsync()
    {
        if (!string.IsNullOrWhiteSpace(RawInputText) && !AddFromInputCore()) return;
        if (DraftLinks.Count == 0)
        {
            ValidationError = _services.Loc.T("请先粘贴链接或导入 TXT 文件");
            return;
        }

        IsSubmitting = true;
        ValidationError = string.Empty;
        try
        {
            await _services.ApiClient.CreateBatchAsync(new BatchInputRequest
            {
                Text = string.Join('\n', DraftLinks),
                Type = SelectedDownloadType,
                Audio = SelectedAudioFormat,
                AllEpisodes = DownloadAllEpisodes,
                RuleMode = SelectedRuleMode,
                RuleId = SelectedRuleId
            });
            DraftLinks.Clear();
            SaveDraftLinks();
            _main.Notify("批量解析任务已开始", InfoBarSeverity.Success);
            await RefreshBatchesAsync();
        }
        catch (Exception ex)
        {
            ValidationError = ex.Message;
        }
        finally
        {
            IsSubmitting = false;
        }
    }

    [RelayCommand]
    public async Task RefreshBatchesAsync()
    {
        if (_refreshing || !_main.IsBackendConnected) return;
        _refreshing = true;
        try
        {
            var jobs = await _services.ApiClient.GetBatchesAsync();
            var items = jobs.SelectMany(j => j.Items).ToList();
            var queued = items.Count(i => i.Status == "QUEUED");
            var tasksTotal = items.Sum(i => i.TaskIds.Count);

            var signature = string.Join(";", jobs.Select(j =>
                $"{j.Id}:{j.Items.Count}:{string.Join(",", j.Items.Select(i => $"{i.Id}={i.Status}/{i.TaskIds.Count}"))}"));

            _main.RunOnUi(() =>
            {
                SummaryText = items.Count > 0
                    ? _services.Loc.T("已提交 %d / %d 条 · 生成 %d 个任务", queued, items.Count, tasksTotal)
                    : string.Empty;
                if (!string.Equals(_lastJobsSignature, signature, StringComparison.Ordinal))
                {
                    _lastJobsSignature = signature;
                    Jobs.Clear();
                    foreach (var j in Enumerable.Reverse(jobs))
                    {
                        Jobs.Add(new BatchJobViewItem(j));
                    }
                    OnPropertyChanged(nameof(HasJobs));
                }
            });

            if (jobs.Any(j => j.Items.Any(r => r.Status is "PENDING" or "RESOLVING")))
            {
                _ = ScheduleFollowUpRefreshAsync();
            }
        }
        catch
        {
        }
        finally
        {
            _refreshing = false;
        }
    }

    private async Task ScheduleFollowUpRefreshAsync()
    {
        await Task.Delay(2500).ConfigureAwait(false);
        if (!_services.IsShuttingDown && _main.SelectedNavKey == "batch")
        {
            await RefreshBatchesAsync().ConfigureAwait(false);
        }
    }

    public Task RefreshBatchesIfVisibleAsync() =>
        _main.SelectedNavKey == "batch" ? RefreshBatchesAsync() : Task.CompletedTask;

    [RelayCommand]
    private async Task CancelBatchJobAsync(string? jobId)
    {
        if (string.IsNullOrEmpty(jobId)) return;
        try
        {
            await _services.ApiClient.ControlBatchAsync(jobId, "cancel");
            _main.Notify("已停止添加下载", InfoBarSeverity.Informational);
            await RefreshBatchesAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task RetryBatchJobAsync(string? jobId)
    {
        if (string.IsNullOrEmpty(jobId)) return;
        try
        {
            await _services.ApiClient.ControlBatchAsync(jobId, "retry");
            _main.Notify("已重试失败项", InfoBarSeverity.Informational);
            await RefreshBatchesAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private void SendRowToSingleAnalyze(string? url)
    {
        if (!string.IsNullOrWhiteSpace(url))
        {
            _main.HomeVm.FillAndAnalyze(url);
        }
    }

    [RelayCommand]
    private void NavigateToTasks() => _main.NavigateTo("tasks");

    [RelayCommand]
    private async Task ClearBatchJobsAsync()
    {
        if (Jobs.Count == 0)
        {
            _main.Notify("暂无可清空的解析记录", InfoBarSeverity.Informational);
            return;
        }

        var hasRunning = Jobs.Any(j => j.IsRunning);
        var confirmMsg = hasRunning
            ? _services.Loc.T("存在正在解析中的批次，确定停止解析并清空全部批次解析记录吗？（已加入下载队列的任务和本地文件不会被删除）")
            : _services.Loc.T("确定清空全部批次解析记录吗？（已加入下载队列的任务和本地文件不会被删除）");

        var ok = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("清空解析记录"),
            confirmMsg,
            _services.Loc.T("立即清除"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!ok) return;

        try
        {
            await _services.ApiClient.ClearBatchesAsync(clearAll: true);
            Jobs.Clear();
            SummaryText = string.Empty;
            OnPropertyChanged(nameof(HasJobs));
            _main.Notify("已清空批次解析记录", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }
}
