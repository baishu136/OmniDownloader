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
/// 首页图文结果单张项
/// </summary>
public sealed partial class SelectableImageItem : ObservableObject
{
    private bool _isSelected = true;

    public ImageResource Resource { get; }
    public int Index { get; }
    public string AbsolutePreviewUrl { get; }

    public bool IsSelected { get => _isSelected; set => SetProperty(ref _isSelected, value); }

    public string Caption => Resource.Kind == "UGOIRA"
        ? AppServices.Current.Loc.T("动图 %d", Index + 1)
        : AppServices.Current.Loc.T("图 %d · %s×%s", Index + 1,
            Resource.Width > 0 ? Resource.Width : "?",
            Resource.Height > 0 ? Resource.Height : "?");

    public SelectableImageItem(ImageResource resource, int index, string absolutePreviewUrl)
    {
        Resource = resource;
        Index = index;
        AbsolutePreviewUrl = absolutePreviewUrl;
    }
}

/// <summary>
/// 首页单条最近任务展示项（支持增量原地属性刷新，避免 SSE 推送时整卡销毁重建）
/// </summary>
public sealed partial class RecentTaskItem : ObservableObject
{
    private DownloadTask _task;
    private string _title = string.Empty;
    private string _thumbnailUrl = string.Empty;
    private string _typeBadge = string.Empty;
    private string _statusBadge = string.Empty;
    private bool _showProgress;
    private double _progressValue;
    private string _progressSummary = string.Empty;
    private string _etaSummary = string.Empty;

    public DownloadTask Task => _task;
    public string Title { get => _title; private set => SetProperty(ref _title, value); }
    public string ThumbnailUrl { get => _thumbnailUrl; private set => SetProperty(ref _thumbnailUrl, value); }
    public string TypeBadge { get => _typeBadge; private set => SetProperty(ref _typeBadge, value); }
    public string StatusBadge { get => _statusBadge; private set => SetProperty(ref _statusBadge, value); }
    public bool ShowProgress { get => _showProgress; private set => SetProperty(ref _showProgress, value); }
    public double ProgressValue { get => _progressValue; private set => SetProperty(ref _progressValue, value); }
    public string ProgressSummary { get => _progressSummary; private set => SetProperty(ref _progressSummary, value); }
    public string EtaSummary { get => _etaSummary; private set => SetProperty(ref _etaSummary, value); }

    public RecentTaskItem(DownloadTask task)
    {
        _task = task;
        UpdateFrom(task);
    }

    public void UpdateFrom(DownloadTask task)
    {
        _task = task;
        Title = string.IsNullOrWhiteSpace(task.Title) ? AppServices.Current.Loc.T("下载任务") : task.Title;
        ThumbnailUrl = task.ThumbnailUrl ?? string.Empty;
        TypeBadge = AppServices.Current.Loc.T(OmniDomainLogic.TypeLabelKey(task.DownloadType));
        StatusBadge = AppServices.Current.Loc.T(OmniDomainLogic.StatusLabelKey(task.Status));
        ShowProgress = task.Status is "DOWNLOADING" or "PROCESSING";
        var clamped = Math.Clamp(task.Progress, 0, 100);
        ProgressValue = clamped;
        ProgressSummary = task.Status == "PROCESSING"
            ? AppServices.Current.Loc.T("正在合并")
            : $"{clamped:0}%{(string.IsNullOrWhiteSpace(task.SpeedText) ? "" : " · " + task.SpeedText)}";
        EtaSummary = string.IsNullOrWhiteSpace(task.EtaText)
            ? string.Empty
            : AppServices.Current.Loc.T("剩余 %s", task.EtaText);
    }
}

/// <summary>
/// 首页 ViewModel：链接输入、平台芯片展开、音视频解析、图文预览网格、浏览器辅助提取与最近任务
/// </summary>
public sealed partial class HomeViewModel : ObservableObject
{
    private readonly AppServices _services;
    private readonly MainViewModel _main;
    private CancellationTokenSource? _analyzeCts;
    private List<DownloadTask> _recentSourceTasks = new();

    private string _inputUrl = string.Empty;
    private bool _isAnalyzing;
    private bool _arePlatformsExpanded;
    private bool _hasAnalyzeError;
    private string _analyzeErrorTitle = string.Empty;
    private string _analyzeErrorHint = string.Empty;
    private string _analyzeErrorAction = "detail";
    private string _lastErrorRaw = string.Empty;
    private VideoMetadata? _currentVideoMetadata;
    private ImagePost? _currentImagePost;

    public string InputUrl
    {
        get => _inputUrl;
        set { if (SetProperty(ref _inputUrl, value)) OnPropertyChanged(nameof(HasInput)); }
    }
    public bool IsAnalyzing { get => _isAnalyzing; set => SetProperty(ref _isAnalyzing, value); }
    public bool ArePlatformsExpanded { get => _arePlatformsExpanded; set => SetProperty(ref _arePlatformsExpanded, value); }
    public bool HasAnalyzeError { get => _hasAnalyzeError; set => SetProperty(ref _hasAnalyzeError, value); }
    public string AnalyzeErrorTitle { get => _analyzeErrorTitle; set => SetProperty(ref _analyzeErrorTitle, value); }
    public string AnalyzeErrorHint { get => _analyzeErrorHint; set => SetProperty(ref _analyzeErrorHint, value); }
    public string AnalyzeErrorAction { get => _analyzeErrorAction; set => SetProperty(ref _analyzeErrorAction, value); }
    public string LastErrorRaw { get => _lastErrorRaw; set => SetProperty(ref _lastErrorRaw, value); }
    public VideoMetadata? CurrentVideoMetadata
    {
        get => _currentVideoMetadata;
        set { if (SetProperty(ref _currentVideoMetadata, value)) OnPropertyChanged(nameof(HasVideoMetadata)); }
    }
    public ImagePost? CurrentImagePost
    {
        get => _currentImagePost;
        set
        {
            if (SetProperty(ref _currentImagePost, value))
            {
                OnPropertyChanged(nameof(HasImagePost));
                OnPropertyChanged(nameof(HasMixedImageSources));
                OnPropertyChanged(nameof(ImageSelectionSummary));
            }
        }
    }

    public ObservableCollection<SelectableImageItem> ImageItems { get; } = new();
    public ObservableCollection<RecentTaskItem> RecentTasks { get; } = new();
    public ObservableCollection<RelaySite> QuickRelaySites { get; } = new();

    public LocalizationService Loc => _services.Loc;
    public bool HasInput => !string.IsNullOrWhiteSpace(InputUrl);
    public bool HasVideoMetadata => CurrentVideoMetadata is not null;
    public bool HasImagePost => CurrentImagePost is not null;
    public bool HasRecentTasks => RecentTasks.Count > 0;
    public bool ShowRecentTasksSection => _services.LocalState.Current.ShowRecentTasksOnHome && RecentTasks.Count > 0;
    public bool HasRelaySites => QuickRelaySites.Count > 0;
    public bool HasMixedImageSources => CurrentImagePost?.Resources.Any(r => !r.Original) == true;
    public int SelectedImagesCount => ImageItems.Count(i => i.IsSelected);
    public string ImageSelectionSummary => CurrentImagePost is null
        ? string.Empty
        : _services.Loc.T("已选 %d / %d 张", SelectedImagesCount, ImageItems.Count);
    public string RecentSectionHeading => RecentTasks.Any(r => r.Task.IsActive)
        ? _services.Loc.T("正在下载的任务 (%d)", RecentTasks.Count(r => r.Task.IsActive))
        : _services.Loc.T("最近任务");

    public event EventHandler<VideoMetadata>? VideoAnalyzedOptionsRequested;
    public event EventHandler<string>? BuiltInBrowserAssistRequested;
    public event EventHandler<(ImagePost Post, int StartIndex)>? ImageViewerRequested;

    public HomeViewModel(AppServices services, MainViewModel main)
    {
        _services = services;
        _main = main;
        _services.Loc.LanguageChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(ImageSelectionSummary));
            OnPropertyChanged(nameof(RecentSectionHeading));
            foreach (var item in RecentTasks)
            {
                item.UpdateFrom(item.Task);
            }
        };
    }

    public void UpdateRelaySites(IEnumerable<RelaySite> sites)
    {
        QuickRelaySites.Clear();
        foreach (var s in sites) QuickRelaySites.Add(s);
        OnPropertyChanged(nameof(HasRelaySites));
    }

    public void UpdateRecentTasks(IReadOnlyList<DownloadTask> allTasks)
    {
        _recentSourceTasks = allTasks.ToList();
        SyncRecentTasksCore();
    }

    public void ApplySingleRecentTaskUpdate(DownloadTask task)
    {
        var idx = _recentSourceTasks.FindIndex(t => string.Equals(t.Id, task.Id, StringComparison.Ordinal));
        if (idx >= 0) _recentSourceTasks[idx] = task;
        else _recentSourceTasks.Insert(0, task);
        SyncRecentTasksCore();
    }

    private void SyncRecentTasksCore()
    {
        var active = _recentSourceTasks.Where(t => t.Status is "DOWNLOADING" or "PROCESSING" or "PENDING").Take(2).ToList();
        var shown = active.Count > 0 ? active : _recentSourceTasks.Take(2).ToList();

        var sameOrder = RecentTasks.Count == shown.Count &&
                        Enumerable.Range(0, shown.Count).All(i => string.Equals(RecentTasks[i].Task.Id, shown[i].Id, StringComparison.Ordinal));
        if (sameOrder)
        {
            for (var i = 0; i < shown.Count; i++)
            {
                RecentTasks[i].UpdateFrom(shown[i]);
            }
        }
        else
        {
            RecentTasks.Clear();
            foreach (var t in shown) RecentTasks.Add(new RecentTaskItem(t));
            OnPropertyChanged(nameof(HasRecentTasks));
        }
        OnPropertyChanged(nameof(ShowRecentTasksSection));
        OnPropertyChanged(nameof(RecentSectionHeading));
    }

    public void RefreshRecentTasksVisibility()
    {
        OnPropertyChanged(nameof(ShowRecentTasksSection));
    }

    public void RefreshImageSelectionCount()
    {
        OnPropertyChanged(nameof(SelectedImagesCount));
        OnPropertyChanged(nameof(ImageSelectionSummary));
    }

    [RelayCommand]
    private void TogglePlatformsExpanded() => ArePlatformsExpanded = !ArePlatformsExpanded;

    [RelayCommand]
    private async Task InlinePasteOrClearAsync()
    {
        if (HasInput)
        {
            ClearAll();
            return;
        }
        var clip = await NativeShellHelper.ReadClipboardTextAsync();
        if (string.IsNullOrWhiteSpace(clip))
        {
            _main.Notify("剪贴板为空", InfoBarSeverity.Warning);
            return;
        }
        InputUrl = clip;
        _main.Notify("已粘贴剪贴板内容", InfoBarSeverity.Informational);
    }

    [RelayCommand]
    private async Task PasteAndAnalyzeAsync()
    {
        var clip = await NativeShellHelper.ReadClipboardTextAsync();
        if (string.IsNullOrWhiteSpace(clip))
        {
            _main.Notify("剪贴板为空", InfoBarSeverity.Warning);
            return;
        }
        InputUrl = clip;
        _main.Notify("已粘贴，开始解析...", InfoBarSeverity.Informational);
        await AnalyzeOrCancelAsync();
    }

    [RelayCommand]
    public async Task AnalyzeOrCancelAsync()
    {
        if (IsAnalyzing)
        {
            _analyzeCts?.Cancel();
            return;
        }

        var url = InputUrl.Trim();
        if (string.IsNullOrWhiteSpace(url))
        {
            _main.Notify("请输入视频链接或分享文本", InfoBarSeverity.Warning);
            return;
        }

        HasAnalyzeError = false;
        IsAnalyzing = true;
        _analyzeCts = new CancellationTokenSource();

        try
        {
            var (kind, video, images) = await _services.ApiClient.AnalyzeMediaAsync(url, _analyzeCts.Token);
            if (kind == "images" && images is not null)
            {
                CurrentVideoMetadata = null;
                LoadImagePost(images);
            }
            else if (video is not null)
            {
                ClearImagePost();
                CurrentVideoMetadata = video;
                VideoAnalyzedOptionsRequested?.Invoke(this, video);
            }
        }
        catch (OperationCanceledException)
        {
            _main.Notify("已取消解析", InfoBarSeverity.Informational);
        }
        catch (Exception ex)
        {
            ShowAnalyzeError(ex);
        }
        finally
        {
            _analyzeCts?.Dispose();
            _analyzeCts = null;
            IsAnalyzing = false;
        }
    }

    public void FillAndAnalyze(string url)
    {
        InputUrl = url;
        _main.NavigateTo("home");
        _ = AnalyzeOrCancelAsync();
    }

    public void LoadImagePost(ImagePost post)
    {
        CurrentImagePost = post;
        ImageItems.Clear();
        for (var i = 0; i < post.Resources.Count; i++)
        {
            var res = post.Resources[i];
            var previewUrl = _services.ApiClient.BuildAbsoluteUrl(string.IsNullOrWhiteSpace(res.PreviewUrl) ? res.Url : res.PreviewUrl);
            var item = new SelectableImageItem(res, i, previewUrl);
            item.PropertyChanged += (_, args) =>
            {
                if (args.PropertyName == nameof(SelectableImageItem.IsSelected))
                {
                    RefreshImageSelectionCount();
                }
            };
            ImageItems.Add(item);
        }
        RefreshImageSelectionCount();
    }

    public void ClearImagePost()
    {
        CurrentImagePost = null;
        ImageItems.Clear();
        RefreshImageSelectionCount();
    }

    public void ClearAll()
    {
        InputUrl = string.Empty;
        HasAnalyzeError = false;
        CurrentVideoMetadata = null;
        ClearImagePost();
    }

    private void ShowAnalyzeError(Exception ex)
    {
        var raw = ex.Message;
        LastErrorRaw = raw;
        var lower = raw.ToLowerInvariant();

        if (lower.Contains("login") || lower.Contains("登录") || lower.Contains("cookie") ||
            lower.Contains("sessdata") || lower.Contains("auth") || lower.Contains("验证") ||
            lower.Contains("captcha") || lower.Contains("challenge"))
        {
            AnalyzeErrorTitle = _services.Loc.T("需要登录或 Cookie 已失效");
            AnalyzeErrorHint = string.IsNullOrWhiteSpace(raw) ? _services.Loc.T("请在「设置 → 账号」中导入会话或 Cookie 后重试。") : raw;
            AnalyzeErrorAction = "accounts";
        }
        else if (lower.Contains("network") || lower.Contains("timeout") || lower.Contains("connection") ||
                 lower.Contains("网络") || lower.Contains("连接") || lower.Contains("dns"))
        {
            AnalyzeErrorTitle = _services.Loc.T("网络连接失败");
            AnalyzeErrorHint = string.IsNullOrWhiteSpace(raw) ? _services.Loc.T("请检查网络或代理设置后重试。") : raw;
            AnalyzeErrorAction = "network";
        }
        else
        {
            AnalyzeErrorTitle = _services.Loc.T("解析未能成功");
            AnalyzeErrorHint = string.IsNullOrWhiteSpace(raw) ? _services.Loc.T("请确认链接来自受支持的平台，或尝试复制完整的分享文本。") : raw;
            AnalyzeErrorAction = "detail";
        }

        HasAnalyzeError = true;
    }

    [RelayCommand]
    private async Task ShowAnalyzeErrorDetailAsync()
    {
        string? btnText = AnalyzeErrorAction switch
        {
            "accounts" => _services.Loc.T("前往账号设置"),
            "network" => _services.Loc.T("设置代理"),
            _ => null
        };

        await _services.Dialogs.ShowErrorDetailAsync(
            AnalyzeErrorTitle,
            string.IsNullOrWhiteSpace(LastErrorRaw) ? AnalyzeErrorHint : LastErrorRaw,
            btnText,
            () => _main.NavigateTo("settings", AnalyzeErrorAction == "accounts" ? "accounts" : "network"));
    }

    [RelayCommand]
    private void GoErrorFixSettings()
    {
        var cat = AnalyzeErrorAction == "accounts" ? "accounts" : "network";
        _main.NavigateTo("settings", cat);
    }

    [RelayCommand]
    private void SelectAllImages()
    {
        foreach (var item in ImageItems) item.IsSelected = true;
        RefreshImageSelectionCount();
    }

    [RelayCommand]
    private void DeselectAllImages()
    {
        foreach (var item in ImageItems) item.IsSelected = false;
        RefreshImageSelectionCount();
    }

    public void RequestImageViewer(int index)
    {
        if (CurrentImagePost is not null)
        {
            ImageViewerRequested?.Invoke(this, (CurrentImagePost, index));
        }
    }

    [RelayCommand]
    private async Task DownloadSelectedImagesAsync(string mode)
    {
        if (CurrentImagePost is null) return;
        var selectedIds = ImageItems.Where(i => i.IsSelected).Select(i => i.Resource.Id).ToList();
        if (selectedIds.Count == 0)
        {
            _main.Notify("请先选择图片", InfoBarSeverity.Warning);
            return;
        }

        var asZip = string.Equals(mode, "zip", StringComparison.OrdinalIgnoreCase);
        var tasks = new List<CreateTaskItem>();

        if (asZip)
        {
            tasks.Add(new CreateTaskItem
            {
                Url = CurrentImagePost.Url,
                Title = CurrentImagePost.Title,
                DownloadType = "IMAGE_ZIP",
                ImagePlatform = CurrentImagePost.Platform,
                SelectedImageIds = selectedIds,
                Author = CurrentImagePost.Author
            });
        }
        else
        {
            var colId = selectedIds.Count > 1 ? $"images:{CurrentImagePost.Url}:{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}" : null;
            for (var idx = 0; idx < selectedIds.Count; idx++)
            {
                tasks.Add(new CreateTaskItem
                {
                    Url = CurrentImagePost.Url,
                    Title = CurrentImagePost.Title,
                    DownloadType = "IMAGE",
                    ImagePlatform = CurrentImagePost.Platform,
                    SelectedImageIds = new List<string> { selectedIds[idx] },
                    Author = CurrentImagePost.Author,
                    CollectionId = colId,
                    CollectionTitle = selectedIds.Count > 1 ? CurrentImagePost.Title : null,
                    EpisodeIndex = selectedIds.Count > 1 ? idx + 1 : 0,
                    EpisodeTotal = selectedIds.Count > 1 ? selectedIds.Count : 0
                });
            }
        }

        try
        {
            await _services.ApiClient.SubmitDownloadTasksAsync(tasks);
            _main.Notify("已加入 %d 个下载任务", InfoBarSeverity.Success, tasks.Count);
            await _main.RefreshTasksAsync();
            _main.NavigateTo("tasks");
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task OpenBrowserAssistAsync()
    {
        var url = InputUrl.Trim();
        try
        {
            await _services.ApiClient.OpenBrowserAssistAsync(url);
            _main.Notify("请在新窗口完成验证并播放目标内容，再返回读取媒体", InfoBarSeverity.Informational);
        }
        catch (OmniApiException ex) when (ex.ErrorCode == "BROWSER_ASSIST_REQUIRED" || ex.Message.Contains("BROWSER_ASSIST_REQUIRED"))
        {
            // 当连接的后端为无 pywebview 的独立服务模式时，自动降级为 WinUI 3 内置 WebView2 辅助窗口
            BuiltInBrowserAssistRequested?.Invoke(this, url);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    [RelayCommand]
    private async Task CapturePlayedMediaAsync()
    {
        try
        {
            var list = await _services.ApiClient.GetBrowserAssistCandidatesAsync();
            if (list.Count == 0)
            {
                _main.Notify("尚未发现可保存的已播放源；blob 或加密媒体不适用", InfoBarSeverity.Warning);
                return;
            }
            await ConfirmAndDownloadAssistVideoAsync(list[0]);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task ConfirmAndDownloadAssistVideoAsync(BrowserAssistCandidate source)
    {
        var host = Uri.TryCreate(source.Url, UriKind.Absolute, out var uri) ? uri.Host : source.Url;
        var title = string.IsNullOrWhiteSpace(source.Title) ? _services.Loc.T("已播放媒体") : source.Title;
        var ok = await _services.Dialogs.ConfirmAsync(
            _services.Loc.T("保存已播放媒体"),
            $"{title}\n{source.Width}×{source.Height} · {host}",
            _services.Loc.T("开始下载"),
            _services.Loc.T("取消"));
        if (!ok) return;

        await _services.ApiClient.SubmitDownloadTasksAsync(new[]
        {
            new CreateTaskItem
            {
                Url = source.Url,
                Title = title,
                DownloadType = "VIDEO_WITH_AUDIO",
                SelectedResolution = "best",
                QualitySelection = new QualitySelection { Mode = "BEST_AVAILABLE" }
            }
        });
        _main.Notify("已加入下载队列", InfoBarSeverity.Success);
        await _main.RefreshTasksAsync();
        _main.NavigateTo("tasks");
    }

    [RelayCommand]
    private async Task CaptureBrowserImagesAsync()
    {
        try
        {
            var post = await _services.ApiClient.CaptureBrowserAssistImagesAsync();
            HasAnalyzeError = false;
            LoadImagePost(post);
        }
        catch (Exception ex)
        {
            _main.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    public async Task OpenRelaySiteWithConfirmAsync(RelaySite site)
    {
        var settings = await _services.ApiClient.GetRelaySitesAsync();
        if (!settings.HasShownRelayCompatTip)
        {
            var ok = await _services.Dialogs.ConfirmAsync(
                _services.Loc.T("第三方网页使用须知"),
                _services.Loc.T("您即将访问用户自行添加的第三方网站。第三方网站可能包含广告或受限于防盗链机制；点击继续后将自动复制当前待解析链接。"),
                _services.Loc.T("我已知晓并继续"),
                _services.Loc.T("取消"));
            if (!ok) return;
            await _services.ApiClient.ConfirmRelayTipAsync();
        }

        if (HasInput)
        {
            NativeShellHelper.CopyText(InputUrl.Trim());
            _main.Notify("已自动复制当前链接到剪贴板，请在打开的中转网站粘贴使用", InfoBarSeverity.Informational);
        }
        NativeShellHelper.OpenWithShell(site.Url);
    }

    public async Task OpenRecentTaskAsync(RecentTaskItem item)
    {
        var task = item.Task;
        if (task.Status == "COMPLETED")
        {
            var siblings = _main.TasksVm.GetCompletedSiblings(task);
            await MediaWindowCoordinator.OpenTaskMediaAsync(_services, task, siblings, (msg, sev) => _main.Notify(msg, sev));
        }
        else
        {
            _main.NavigateTo("tasks");
        }
    }
}
