using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.ViewModels;

/// <summary>
/// 单个任务卡片或合集折叠卡片展示对象（支持原地属性差量更新，消除进度刷新时的整卡重建与图片闪烁）
/// </summary>
public sealed partial class TaskGroupViewModel : ObservableObject
{
    private bool _isExpanded;
    private bool _showMultiSelectCheck;
    private bool _isSelectedForCopy;
    private TaskGroupEntry _entry;

    private bool _isCollection;
    private bool _isSingle;
    private DownloadTask _primary;
    private string _title = string.Empty;
    private string _typeBadge = string.Empty;
    private string _statusBadge = string.Empty;
    private bool _isCompleted;
    private bool _isActive;
    private bool _isFailedOrCancelled;
    private bool _showProgressBar;
    private double _progressValue;
    private string _progressSummary = string.Empty;
    private string _etaSummary = string.Empty;
    private string _fileSizeText = string.Empty;
    private string _errorText = string.Empty;
    private bool _hasError;
    private string _thumbnailUrl = string.Empty;
    private string _primaryActionText = string.Empty;
    private string _primaryActionGlyph = string.Empty;
    private string _collectionBadge = string.Empty;
    private string _collectionProgressSummary = string.Empty;
    private double _collectionProgressValue;
    private bool _collectionHasCompleted;
    private bool _collectionHasActive;
    private string _collectionPlayText = string.Empty;

    public TaskGroupEntry Entry => _entry;

    public bool IsExpanded
    {
        get => _isExpanded;
        set { if (SetProperty(ref _isExpanded, value)) OnPropertyChanged(nameof(CollectionExpandText)); }
    }

    public bool ShowMultiSelectCheck
    {
        get => _showMultiSelectCheck;
        set
        {
            if (SetProperty(ref _showMultiSelectCheck, value))
            {
                OnPropertyChanged(nameof(IsMultiSelectCheckVisible));
            }
        }
    }

    public bool IsSelectedForCopy
    {
        get => _isSelectedForCopy;
        set => SetProperty(ref _isSelectedForCopy, value);
    }

    public bool CanCopyOrDrag => (IsSingle && IsCompleted) || (IsCollection && CollectionHasCompleted);

    public bool IsMultiSelectCheckVisible => ShowMultiSelectCheck && CanCopyOrDrag;

    public IReadOnlyList<DownloadTask> GetCompletedLocalTasks() => IsSingle
        ? (Primary.Status == "COMPLETED" ? new[] { Primary } : Array.Empty<DownloadTask>())
        : Entry.Items.Where(t => t.Status == "COMPLETED").ToList();

    public bool IsCollection { get => _isCollection; private set => SetProperty(ref _isCollection, value); }
    public bool IsSingle { get => _isSingle; private set => SetProperty(ref _isSingle, value); }
    public DownloadTask Primary { get => _primary; private set => SetProperty(ref _primary, value); }
    public string Title { get => _title; private set => SetProperty(ref _title, value); }
    public ObservableCollection<TaskEpisodeViewModel> Episodes { get; } = new();

    // 单任务属性
    public string TypeBadge { get => _typeBadge; private set => SetProperty(ref _typeBadge, value); }
    public string StatusBadge { get => _statusBadge; private set => SetProperty(ref _statusBadge, value); }
    public bool IsCompleted { get => _isCompleted; private set => SetProperty(ref _isCompleted, value); }
    public bool IsActive { get => _isActive; private set => SetProperty(ref _isActive, value); }
    public bool IsFailedOrCancelled { get => _isFailedOrCancelled; private set => SetProperty(ref _isFailedOrCancelled, value); }
    public bool ShowProgressBar { get => _showProgressBar; private set => SetProperty(ref _showProgressBar, value); }
    public double ProgressValue { get => _progressValue; private set => SetProperty(ref _progressValue, value); }
    public string ProgressSummary { get => _progressSummary; private set => SetProperty(ref _progressSummary, value); }
    public string EtaSummary { get => _etaSummary; private set => SetProperty(ref _etaSummary, value); }
    public string FileSizeText { get => _fileSizeText; private set => SetProperty(ref _fileSizeText, value); }
    public string ErrorText { get => _errorText; private set => SetProperty(ref _errorText, value); }
    public bool HasError { get => _hasError; private set => SetProperty(ref _hasError, value); }
    public string ThumbnailUrl { get => _thumbnailUrl; private set => SetProperty(ref _thumbnailUrl, value); }
    public string PrimaryActionText { get => _primaryActionText; private set => SetProperty(ref _primaryActionText, value); }
    public string PrimaryActionGlyph { get => _primaryActionGlyph; private set => SetProperty(ref _primaryActionGlyph, value); }

    // 合集汇总属性
    public string CollectionBadge { get => _collectionBadge; private set => SetProperty(ref _collectionBadge, value); }
    public string CollectionProgressSummary { get => _collectionProgressSummary; private set => SetProperty(ref _collectionProgressSummary, value); }
    public double CollectionProgressValue { get => _collectionProgressValue; private set => SetProperty(ref _collectionProgressValue, value); }
    public string CollectionExpandText => IsExpanded
        ? AppServices.Current.Loc.T("收起明细 (共 %d 集)", Entry.Items.Count)
        : AppServices.Current.Loc.T("展开分集明细 (共 %d 集 · 已完成 %d)", Entry.Items.Count, Entry.CompletedCount);
    public bool CollectionHasCompleted { get => _collectionHasCompleted; private set => SetProperty(ref _collectionHasCompleted, value); }
    public bool CollectionHasActive { get => _collectionHasActive; private set => SetProperty(ref _collectionHasActive, value); }
    public string CollectionPlayText { get => _collectionPlayText; private set => SetProperty(ref _collectionPlayText, value); }

    public TaskGroupViewModel(TaskGroupEntry entry, bool expanded)
    {
        _entry = entry;
        _primary = entry.PrimaryItem;
        _isExpanded = expanded;
        UpdateFrom(entry, expanded);
    }

    /// <summary>
    /// 在任务状态与所属分组未发生变化时执行 O(1) 原地进度刷新，避免重建全部任务列表与触发视觉树布局抖动。
    /// </summary>
    public void ApplyActiveTaskProgress(DownloadTask task)
    {
        if (IsSingle)
        {
            _entry = new TaskGroupEntry
            {
                Key = _entry.Key,
                IsCollection = false,
                CollectionId = _entry.CollectionId,
                Title = _entry.Title,
                Items = new[] { task }
            };
            Primary = task;
            var clamped = Math.Clamp(task.Progress, 0, 100);
            ProgressValue = clamped;
            ProgressSummary = task.Status == "PROCESSING"
                ? AppServices.Current.Loc.T("正在合并音画轨...")
                : $"{clamped:0}%{(string.IsNullOrWhiteSpace(task.SpeedText) ? "" : " · " + task.SpeedText)}";
            EtaSummary = string.IsNullOrWhiteSpace(task.EtaText)
                ? string.Empty
                : AppServices.Current.Loc.T("剩余 %s", AppServices.Current.Loc.T(task.EtaText));
            FileSizeText = task.FileSizeText ?? string.Empty;
            ShowProgressBar = task.Status is "DOWNLOADING" or "PROCESSING";
            StatusBadge = AppServices.Current.Loc.T(OmniDomainLogic.StatusLabelKey(task.Status));
            return;
        }

        // 合集任务原地更新对应分集并重算合集汇总进度，不重置 Episodes 集合
        var updatedItems = _entry.Items.ToList();
        var matchedIndex = -1;
        for (var i = 0; i < updatedItems.Count; i++)
        {
            if (string.Equals(updatedItems[i].Id, task.Id, StringComparison.Ordinal))
            {
                updatedItems[i] = task;
                matchedIndex = i;
                break;
            }
        }
        if (matchedIndex < 0) return;

        if (matchedIndex < Episodes.Count)
        {
            Episodes[matchedIndex].UpdateFrom(task, matchedIndex);
        }

        double sum = 0;
        var completed = 0;
        DownloadTask? activeChild = null;
        foreach (var item in updatedItems)
        {
            if (item.Status == "COMPLETED")
            {
                completed++;
                sum += 100;
            }
            else if (item.Status is "DOWNLOADING" or "PROCESSING")
            {
                activeChild ??= item;
                sum += Math.Clamp(item.Progress, 0, 100);
            }
        }
        var overall = updatedItems.Count > 0 ? Math.Clamp(sum / updatedItems.Count, 0, 100) : 0;
        var primary = activeChild ?? updatedItems[0];
        _entry = new TaskGroupEntry
        {
            Key = _entry.Key,
            IsCollection = true,
            CollectionId = _entry.CollectionId,
            Title = _entry.Title,
            Items = updatedItems
        };
        Primary = primary;
        CollectionProgressValue = overall;
        CollectionProgressSummary = AppServices.Current.Loc.T("总进度：已完成 %d/%d 集 (%d%%)", completed, updatedItems.Count, (int)Math.Round(overall));
    }

    public void UpdateFrom(TaskGroupEntry entry, bool expanded)
    {
        _entry = entry;
        IsExpanded = expanded;
        IsCollection = entry.IsCollection;
        IsSingle = !entry.IsCollection;
        var p = entry.PrimaryItem;
        Primary = p;
        Title = string.IsNullOrWhiteSpace(entry.Title) ? AppServices.Current.Loc.T("下载任务") : entry.Title;

        TypeBadge = AppServices.Current.Loc.T(OmniDomainLogic.TypeLabelKey(p.DownloadType));
        StatusBadge = AppServices.Current.Loc.T(OmniDomainLogic.StatusLabelKey(p.Status));
        IsCompleted = p.Status == "COMPLETED";
        IsActive = p.IsActive;
        IsFailedOrCancelled = p.Status is "FAILED" or "CANCELLED";
        ShowProgressBar = p.Status is "DOWNLOADING" or "PROCESSING";
        var clamped = Math.Clamp(p.Progress, 0, 100);
        ProgressValue = clamped;
        ProgressSummary = p.Status == "PROCESSING"
            ? AppServices.Current.Loc.T("正在合并音画轨...")
            : $"{clamped:0}%{(string.IsNullOrWhiteSpace(p.SpeedText) ? "" : " · " + p.SpeedText)}";
        EtaSummary = string.IsNullOrWhiteSpace(p.EtaText)
            ? string.Empty
            : AppServices.Current.Loc.T("剩余 %s", AppServices.Current.Loc.T(p.EtaText));
        FileSizeText = p.FileSizeText ?? string.Empty;
        ErrorText = string.IsNullOrWhiteSpace(p.ErrorMessage)
            ? string.Empty
            : AppServices.Current.Loc.T("错误: %s", p.ErrorMessage);
        HasError = !string.IsNullOrWhiteSpace(p.ErrorMessage);
        ThumbnailUrl = p.ThumbnailUrl ?? string.Empty;
        PrimaryActionText = AppServices.Current.Loc.T(OmniDomainLogic.TaskViewActionText(p));
        PrimaryActionGlyph = OmniDomainLogic.TaskViewActionGlyph(p);

        CollectionBadge = AppServices.Current.Loc.T("合集 · %d 集", entry.Items.Count);
        CollectionProgressSummary = AppServices.Current.Loc.T("总进度：已完成 %d/%d 集 (%d%%)", entry.CompletedCount, entry.Items.Count, (int)Math.Round(entry.OverallProgress));
        CollectionProgressValue = entry.OverallProgress;
        CollectionHasCompleted = entry.Items.Any(t => t.Status == "COMPLETED");
        CollectionHasActive = entry.Items.Any(t => t.IsActive);
        OnPropertyChanged(nameof(CanCopyOrDrag));
        OnPropertyChanged(nameof(IsMultiSelectCheckVisible));

        var firstDone = entry.Items.FirstOrDefault(t => t.Status == "COMPLETED");
        if (firstDone is null)
        {
            CollectionPlayText = AppServices.Current.Loc.T("播放首集");
        }
        else
        {
            var kind = OmniDomainLogic.ResolveMediaViewKind(firstDone);
            CollectionPlayText = kind is MediaViewKind.Image or MediaViewKind.ImageArchive
                ? AppServices.Current.Loc.T("查看首图")
                : AppServices.Current.Loc.T("播放首集");
        }
        OnPropertyChanged(nameof(CollectionExpandText));

        var sameEpisodes = Episodes.Count == entry.Items.Count &&
                           Enumerable.Range(0, entry.Items.Count).All(i => string.Equals(Episodes[i].Task.Id, entry.Items[i].Id, StringComparison.Ordinal));
        if (sameEpisodes)
        {
            for (var i = 0; i < entry.Items.Count; i++)
            {
                Episodes[i].UpdateFrom(entry.Items[i], i);
            }
        }
        else
        {
            Episodes.Clear();
            for (var i = 0; i < entry.Items.Count; i++)
            {
                Episodes.Add(new TaskEpisodeViewModel(entry.Items[i], i));
            }
        }
    }
}

public sealed partial class TaskEpisodeViewModel : ObservableObject
{
    private DownloadTask _task;
    private string _episodeTag = string.Empty;
    private string _title = string.Empty;
    private string _statusBadge = string.Empty;
    private bool _isCompleted;
    private bool _isActive;
    private bool _isFailedOrCancelled;
    private string _detailLine = string.Empty;
    private string _actionText = string.Empty;

    public DownloadTask Task { get => _task; private set => SetProperty(ref _task, value); }
    public string EpisodeTag { get => _episodeTag; private set => SetProperty(ref _episodeTag, value); }
    public string Title { get => _title; private set => SetProperty(ref _title, value); }
    public string StatusBadge { get => _statusBadge; private set => SetProperty(ref _statusBadge, value); }
    public bool IsCompleted { get => _isCompleted; private set => SetProperty(ref _isCompleted, value); }
    public bool IsActive { get => _isActive; private set => SetProperty(ref _isActive, value); }
    public bool IsFailedOrCancelled { get => _isFailedOrCancelled; private set => SetProperty(ref _isFailedOrCancelled, value); }
    public string DetailLine { get => _detailLine; private set => SetProperty(ref _detailLine, value); }
    public string ActionText { get => _actionText; private set => SetProperty(ref _actionText, value); }

    public TaskEpisodeViewModel(DownloadTask task, int index)
    {
        _task = task;
        UpdateFrom(task, index);
    }

    public void UpdateFrom(DownloadTask task, int index)
    {
        Task = task;
        EpisodeTag = $"P{(task.EpisodeIndex > 0 ? task.EpisodeIndex : index + 1)}";
        Title = task.Title;
        var badge = AppServices.Current.Loc.T(OmniDomainLogic.StatusLabelKey(task.Status));
        StatusBadge = badge;
        IsCompleted = task.Status == "COMPLETED";
        IsActive = task.IsActive;
        IsFailedOrCancelled = task.Status is "FAILED" or "CANCELLED";
        DetailLine = task.Status switch
        {
            "DOWNLOADING" => $"{task.Progress:0}% {(string.IsNullOrWhiteSpace(task.SpeedText) ? "" : "· " + task.SpeedText)}",
            "PROCESSING" => AppServices.Current.Loc.T("正在合并"),
            "COMPLETED" => string.IsNullOrWhiteSpace(task.FileSizeText) ? AppServices.Current.Loc.T("已完成") : task.FileSizeText,
            "FAILED" => string.IsNullOrWhiteSpace(task.ErrorMessage) ? AppServices.Current.Loc.T("失败") : task.ErrorMessage,
            _ => badge
        };
        ActionText = AppServices.Current.Loc.T(OmniDomainLogic.TaskViewActionText(task));
    }
}

/// <summary>
/// 下载任务管理 ViewModel：支持 3 段筛选、合集折叠状态记忆、播放/定位/重试/取消/删除与清空确认
/// </summary>
public sealed partial class TasksViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private readonly HashSet<string> _expandedCollections = new(StringComparer.Ordinal);
    private readonly HashSet<string> _selectedGroupKeys = new(StringComparer.Ordinal);
    private List<DownloadTask> _rawTasks = new();

    private string _filterMode = "all";
    private int _allCount;
    private int _activeCount;
    private int _completedCount;
    private bool _isMultiSelectMode;

    public string FilterMode
    {
        get => _filterMode;
        set { if (SetProperty(ref _filterMode, value)) RebuildVisible(); }
    }
    public int AllCount { get => _allCount; set => SetProperty(ref _allCount, value); }
    public int ActiveCount { get => _activeCount; set => SetProperty(ref _activeCount, value); }
    public int CompletedCount
    {
        get => _completedCount;
        set
        {
            if (SetProperty(ref _completedCount, value))
            {
                OnPropertyChanged(nameof(HasCompletedTasks));
            }
        }
    }

    public bool IsMultiSelectMode
    {
        get => _isMultiSelectMode;
        set
        {
            if (SetProperty(ref _isMultiSelectMode, value))
            {
                if (!value)
                {
                    _selectedGroupKeys.Clear();
                }
                foreach (var item in VisibleEntries)
                {
                    item.ShowMultiSelectCheck = value;
                    if (!value) item.IsSelectedForCopy = false;
                }
                NotifyMultiSelectProperties();
            }
        }
    }

    public bool HasCompletedTasks => CompletedCount > 0;
    public int SelectedGroupCount => VisibleEntries.Count(v => v.IsSelectedForCopy && v.CanCopyOrDrag);
    public bool HasSelectedFiles => SelectedGroupCount > 0;
    public string SelectedCountText => _services.Loc.T("已选 %d 项", SelectedGroupCount);

    public ObservableCollection<TaskGroupViewModel> VisibleEntries { get; } = new();

    public LocalizationService Loc => _services.Loc;
    public bool IsEmpty => VisibleEntries.Count == 0;
    public string EmptyTitle => FilterMode switch
    {
        "active" => _services.Loc.T("暂无正在进行的下载任务"),
        "completed" => _services.Loc.T("暂无已完成的下载记录"),
        _ => _services.Loc.T("暂无任何下载任务")
    };
    public string ActiveSubtitle => _services.Loc.T("下载中 %d", ActiveCount);
    public string AllTabText => _services.Loc.T("全部 (%d)", AllCount);
    public string ActiveTabText => _services.Loc.T("下载中 (%d)", ActiveCount);
    public string CompletedTabText => _services.Loc.T("已完成 (%d)", CompletedCount);

    public TasksViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        _services.Loc.LanguageChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(AllTabText));
            OnPropertyChanged(nameof(ActiveTabText));
            OnPropertyChanged(nameof(CompletedTabText));
            OnPropertyChanged(nameof(ActiveSubtitle));
            OnPropertyChanged(nameof(EmptyTitle));
            OnPropertyChanged(nameof(SelectedCountText));
            RebuildVisible();
        };
    }

    public void UpdateFromTasks(IReadOnlyList<DownloadTask> tasks)
    {
        _rawTasks = tasks.ToList();
        SyncCountsAndRebuild();
    }

    public void ApplySingleTaskUpdate(DownloadTask task)
    {
        var idx = _rawTasks.FindIndex(t => string.Equals(t.Id, task.Id, StringComparison.Ordinal));
        if (idx >= 0)
        {
            var prev = _rawTasks[idx];
            _rawTasks[idx] = task;

            var sameActiveState = string.Equals(prev.Status, task.Status, StringComparison.Ordinal)
                && string.Equals(prev.CollectionId ?? string.Empty, task.CollectionId ?? string.Empty, StringComparison.Ordinal)
                && string.Equals(prev.Title ?? string.Empty, task.Title ?? string.Empty, StringComparison.Ordinal)
                && task.Status is "DOWNLOADING" or "PROCESSING";
            if (sameActiveState)
            {
                var targetKey = string.IsNullOrWhiteSpace(task.CollectionId) ? $"single:{task.Id}" : $"col:{task.CollectionId}";
                foreach (var card in VisibleEntries)
                {
                    if (string.Equals(card.Entry.Key, targetKey, StringComparison.Ordinal))
                    {
                        card.ApplyActiveTaskProgress(task);
                        break;
                    }
                }
                return;
            }
        }
        else
        {
            _rawTasks.Insert(0, task);
        }
        SyncCountsAndRebuild();
    }

    public void RefreshAfterWindowRestore() => SyncCountsAndRebuild();

    private void SyncCountsAndRebuild()
    {
        var all = _rawTasks.Count;
        var active = _rawTasks.Count(t => t.IsActive);
        var completed = _rawTasks.Count(t => t.Status == "COMPLETED");

        var countsChanged = all != AllCount || active != ActiveCount || completed != CompletedCount;
        AllCount = all;
        ActiveCount = active;
        CompletedCount = completed;
        if (countsChanged)
        {
            OnPropertyChanged(nameof(ActiveSubtitle));
            OnPropertyChanged(nameof(AllTabText));
            OnPropertyChanged(nameof(ActiveTabText));
            OnPropertyChanged(nameof(CompletedTabText));
        }
        RebuildVisible();
    }

    private void RebuildVisible()
    {
        var grouped = OmniDomainLogic.FilterAndGroupTasks(_rawTasks, FilterMode);
        var existingByKey = VisibleEntries.ToDictionary(v => v.Entry.Key, StringComparer.Ordinal);
        var newKeys = grouped.Select(g => g.Key).ToHashSet(StringComparer.Ordinal);

        // 先清除已经不匹配的条目
        for (var i = VisibleEntries.Count - 1; i >= 0; i--)
        {
            if (!newKeys.Contains(VisibleEntries[i].Entry.Key))
            {
                VisibleEntries.RemoveAt(i);
            }
        }

        // 原地顺序对齐并差量刷新，保留现有 XAML 容器与封面图避免闪烁
        for (var i = 0; i < grouped.Count; i++)
        {
            var entry = grouped[i];
            var expanded = entry.IsCollection && _expandedCollections.Contains(entry.CollectionId);
            if (existingByKey.TryGetValue(entry.Key, out var vm))
            {
                vm.UpdateFrom(entry, expanded);
                vm.ShowMultiSelectCheck = IsMultiSelectMode;
                vm.IsSelectedForCopy = IsMultiSelectMode && vm.CanCopyOrDrag && _selectedGroupKeys.Contains(entry.Key);
                var currentIdx = VisibleEntries.IndexOf(vm);
                if (currentIdx != i && currentIdx >= 0)
                {
                    VisibleEntries.Move(currentIdx, i);
                }
            }
            else
            {
                var created = new TaskGroupViewModel(entry, expanded)
                {
                    ShowMultiSelectCheck = IsMultiSelectMode,
                    IsSelectedForCopy = IsMultiSelectMode && _selectedGroupKeys.Contains(entry.Key)
                };
                if (!created.CanCopyOrDrag) created.IsSelectedForCopy = false;
                VisibleEntries.Insert(i, created);
            }
        }

        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(EmptyTitle));
        if (IsMultiSelectMode || _selectedGroupKeys.Count > 0)
        {
            NotifyMultiSelectProperties();
        }
    }

    private void NotifyMultiSelectProperties()
    {
        OnPropertyChanged(nameof(SelectedGroupCount));
        OnPropertyChanged(nameof(HasSelectedFiles));
        OnPropertyChanged(nameof(SelectedCountText));
    }

    public void UpdateGroupSelection(TaskGroupViewModel? group, bool isSelected)
    {
        if (group is null) return;
        group.IsSelectedForCopy = isSelected && group.CanCopyOrDrag;
        if (group.IsSelectedForCopy) _selectedGroupKeys.Add(group.Entry.Key);
        else _selectedGroupKeys.Remove(group.Entry.Key);
        NotifyMultiSelectProperties();
    }

    [RelayCommand]
    private void SelectAllCompleted()
    {
        foreach (var item in VisibleEntries)
        {
            if (item.CanCopyOrDrag)
            {
                item.IsSelectedForCopy = true;
                _selectedGroupKeys.Add(item.Entry.Key);
            }
        }
        NotifyMultiSelectProperties();
    }

    [RelayCommand]
    private void ClearMultiSelection()
    {
        _selectedGroupKeys.Clear();
        foreach (var item in VisibleEntries)
        {
            item.IsSelectedForCopy = false;
        }
        NotifyMultiSelectProperties();
    }

    public IReadOnlyList<DownloadTask> ResolveDragTasks(TaskGroupViewModel? group)
    {
        if (group is null || !group.CanCopyOrDrag) return Array.Empty<DownloadTask>();
        if (IsMultiSelectMode && group.IsSelectedForCopy)
        {
            var batch = VisibleEntries
                .Where(v => v.IsSelectedForCopy && v.CanCopyOrDrag)
                .SelectMany(v => v.GetCompletedLocalTasks())
                .ToList();
            if (batch.Count > 0) return batch;
        }
        return group.GetCompletedLocalTasks();
    }

    public void CopyTaskFile(DownloadTask? task)
    {
        if (task is null) return;
        CopyTasksFilesCore(new[] { task });
    }

    public void CopyTaskFilePath(DownloadTask? task)
    {
        if (task is null) return;
        CopyTasksPathsCore(new[] { task });
    }

    public void CopyCollectionFiles(TaskGroupViewModel? group)
    {
        if (group is null) return;
        CopyTasksFilesCore(group.GetCompletedLocalTasks());
    }

    public void CopyCollectionFilePaths(TaskGroupViewModel? group)
    {
        if (group is null) return;
        CopyTasksPathsCore(group.GetCompletedLocalTasks());
    }

    [RelayCommand]
    private void CopySelectedFiles()
    {
        var selectedTasks = VisibleEntries
            .Where(v => v.IsSelectedForCopy && v.CanCopyOrDrag)
            .SelectMany(v => v.GetCompletedLocalTasks())
            .ToList();
        CopyTasksFilesCore(selectedTasks);
    }

    [RelayCommand]
    private void CopySelectedFilePaths()
    {
        var selectedTasks = VisibleEntries
            .Where(v => v.IsSelectedForCopy && v.CanCopyOrDrag)
            .SelectMany(v => v.GetCompletedLocalTasks())
            .ToList();
        CopyTasksPathsCore(selectedTasks);
    }

    private void CopyTasksFilesCore(IReadOnlyList<DownloadTask> tasks) =>
        FileTransferUiHelper.CopyTasksFilesWithNotice(_services, tasks);

    private void CopyTasksPathsCore(IReadOnlyList<DownloadTask> tasks) =>
        FileTransferUiHelper.CopyTasksPathsWithNotice(_services, tasks);

    [RelayCommand]
    private void ToggleCollectionExpanded(TaskGroupViewModel? group)
    {
        if (group is null || !group.IsCollection) return;
        group.IsExpanded = !group.IsExpanded;
        if (group.IsExpanded) _expandedCollections.Add(group.Entry.CollectionId);
        else _expandedCollections.Remove(group.Entry.CollectionId);
    }

    [RelayCommand]
    private async Task OpenDownloadFolderAsync()
    {
        try
        {
            await _services.ApiClient.OpenDownloadFolderAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public IReadOnlyList<DownloadTask> GetCompletedSiblings(DownloadTask task)
    {
        if (string.IsNullOrWhiteSpace(task.CollectionId))
        {
            return new[] { task };
        }
        var siblings = _rawTasks
            .Where(t => t.Status == "COMPLETED" && string.Equals(t.CollectionId, task.CollectionId, StringComparison.Ordinal))
            .OrderBy(t => t.EpisodeIndex)
            .ThenBy(t => t.CreatedAt)
            .ToList();
        return siblings.Count > 0 ? siblings : new[] { task };
    }

    [RelayCommand]
    private async Task PlayTaskAsync(DownloadTask? task)
    {
        if (task is null) return;
        var siblings = GetCompletedSiblings(task);
        await MediaWindowCoordinator.OpenTaskMediaAsync(_services, task, siblings, (msg, sev) => _main.Notify(msg, sev));
    }

    [RelayCommand]
    private void PlayFirstInCollection(TaskGroupViewModel? group)
    {
        var firstDone = group?.Entry.Items.FirstOrDefault(t => t.Status == "COMPLETED");
        if (firstDone is not null)
        {
            var siblings = group!.Entry.Items.Where(t => t.Status == "COMPLETED").ToList();
            _ = MediaWindowCoordinator.OpenTaskMediaAsync(_services, firstDone, siblings, (msg, sev) => _main.Notify(msg, sev));
        }
    }

    [RelayCommand]
    private async Task LocateFileAsync(DownloadTask? task)
    {
        if (task is null) return;
        try
        {
            await _services.ApiClient.OpenTaskFileAsync(task.Id);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task RetryTaskAsync(DownloadTask? task)
    {
        if (task is null) return;
        try
        {
            await _services.ApiClient.RetryTaskAsync(task.Id);
            _main.Notify("已重新提交任务", InfoBarSeverity.Success);
            await _main.RefreshTasksAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task CancelTaskAsync(DownloadTask? task)
    {
        if (task is null) return;
        try
        {
            await _services.ApiClient.CancelTaskAsync(task.Id);
            _main.Notify("已请求取消任务", InfoBarSeverity.Informational);
            await _main.RefreshTasksAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task CancelCollectionActiveAsync(TaskGroupViewModel? group)
    {
        if (group is null) return;
        var active = group.Entry.Items.Where(t => t.IsActive).ToList();
        foreach (var item in active)
        {
            try { await _services.ApiClient.CancelTaskAsync(item.Id); } catch { }
        }
        _main.Notify("已取消合集任务", InfoBarSeverity.Informational);
        await _main.RefreshTasksAsync();
    }

    [RelayCommand]
    private async Task DeleteSingleTaskAsync(DownloadTask? task)
    {
        if (task is null) return;
        var checkBox = new CheckBox { Content = _services.Loc.T("同时删除本地文件") };
        var stack = new StackPanel { Spacing = 10 };
        stack.Children.Add(new TextBlock
        {
            Text = _services.Loc.T("确定删除任务「%s」？", task.Title),
            TextWrapping = Microsoft.UI.Xaml.TextWrapping.WrapWholeWords
        });
        stack.Children.Add(checkBox);

        var dialog = new ContentDialog
        {
            Title = _services.Loc.T("删除"),
            Content = stack,
            PrimaryButtonText = _services.Loc.T("删除"),
            CloseButtonText = _services.Loc.T("取消"),
            DefaultButton = ContentDialogButton.Close
        };

        var result = await _services.Dialogs.ShowCustomDialogAsync(dialog);
        if (result != ContentDialogResult.Primary) return;

        try
        {
            if (checkBox.IsChecked == true)
            {
                NativeVideoPlayerWindow.ReleaseFileLocks(new[] { task.LocalFilePath });
            }
            await _services.ApiClient.DeleteTaskAsync(task.Id, checkBox.IsChecked == true);
            await _main.RefreshTasksAsync();
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task DeleteCollectionAsync(TaskGroupViewModel? group)
    {
        if (group is null) return;
        var checkBox = new CheckBox { Content = _services.Loc.T("同时删除本地文件") };
        var stack = new StackPanel { Spacing = 10 };
        stack.Children.Add(new TextBlock
        {
            Text = _services.Loc.T("确定删除合集「%s」的全部 %d 条任务记录？", group.Title, group.Entry.Items.Count),
            TextWrapping = Microsoft.UI.Xaml.TextWrapping.WrapWholeWords
        });
        stack.Children.Add(checkBox);

        var dialog = new ContentDialog
        {
            Title = _services.Loc.T("删除合集记录"),
            Content = stack,
            PrimaryButtonText = _services.Loc.T("删除"),
            CloseButtonText = _services.Loc.T("取消"),
            DefaultButton = ContentDialogButton.Close
        };

        var result = await _services.Dialogs.ShowCustomDialogAsync(dialog);
        if (result != ContentDialogResult.Primary) return;

        var deleteFile = checkBox.IsChecked == true;
        if (deleteFile)
        {
            NativeVideoPlayerWindow.ReleaseFileLocks(group.Entry.Items.Select(t => t.LocalFilePath));
        }
        foreach (var t in group.Entry.Items)
        {
            try { await _services.ApiClient.DeleteTaskAsync(t.Id, deleteFile); } catch { }
        }
        await _main.RefreshTasksAsync();
    }

    [RelayCommand]
    private async Task ShowTaskErrorDetailAsync(DownloadTask? task)
    {
        if (task is null) return;
        await _services.Dialogs.ShowErrorDetailAsync(
            _services.Loc.T("失败日志详情"),
            task.ErrorMessage);
    }

    [RelayCommand]
    private async Task ClearRecordsAsync()
    {
        if (_rawTasks.Count == 0)
        {
            _main.Notify("暂无可清空的历史记录", InfoBarSeverity.Informational);
            return;
        }

        // 若有未完成任务，支持先确认终止或只清已结束任务（对齐 Web / Android 逻辑）
        var onlyActive = _rawTasks.All(t => t.IsActive);
        if (onlyActive)
        {
            var killOk = await _services.Dialogs.ConfirmAsync(
                _services.Loc.T("终止未完成任务"),
                _services.Loc.T("确定要终止所有未完成的下载任务并删除残留文件吗？此操作将立即停止下载并清理未完成的临时文件。"),
                _services.Loc.T("终止并清理"),
                _services.Loc.T("取消"),
                destructive: true);
            if (!killOk) return;
            await _services.ApiClient.ClearTasksAsync(clearAll: true);
            _main.Notify("已终止所有未完成任务", InfoBarSeverity.Informational);
            await _main.RefreshTasksAsync();
            return;
        }

        var clearOk = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("清除下载记录"),
            _services.Loc.T("确定要清除所有已结束的任务记录吗？\n（包含已完成、下载失败与已取消任务，本地已保存的视频文件不会被删除）"),
            _services.Loc.T("立即清除"),
            _services.Loc.T("取消"),
            destructive: true);
        if (!clearOk) return;

        await _services.ApiClient.ClearTasksAsync(clearAll: false);
        await _main.RefreshTasksAsync();
    }
}
