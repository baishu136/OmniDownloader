using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.System;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生 Windows MediaPlayerElement 视频/音频播放窗口：
/// 支持硬件解码播放、倍速/音量/进度条/全屏、合集连播、纯音频封面展示，以及删除文件前自动释放文件句柄。
/// </summary>
public sealed partial class NativeVideoPlayerWindow : Window
{
    private static readonly object ActiveLock = new();
    private static readonly List<NativeVideoPlayerWindow> ActiveWindows = new();

    private readonly AppServices _services;
    private readonly IReadOnlyList<DownloadTask> _playlist;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcher;
    private int _index;
    private MediaPlayer? _player;
    private MediaSource? _currentSource;
    private bool _syncingPlaylist;
    private bool _isClosed;
    private bool _isNarrowToolbar;
    private bool _isPinnedOnTop;
    private int _loadGeneration;
    private CancellationTokenSource? _hudCts;

    /// <summary>
    /// 按窗口宽度切换播放栏按钮区至第二行并自动换行，防止挤压或裁切标题与控制键。
    /// </summary>
    private void RootGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var narrowToolbar = e.NewSize.Width < 760;
        if (_isNarrowToolbar != narrowToolbar)
        {
            _isNarrowToolbar = narrowToolbar;
            Grid.SetRow(VideoActionsPanel, narrowToolbar ? 1 : 0);
            Grid.SetColumn(VideoActionsPanel, narrowToolbar ? 0 : 1);
            Grid.SetColumnSpan(VideoActionsPanel, narrowToolbar ? 2 : 1);
            VideoActionsPanel.HorizontalAlignment = narrowToolbar ? HorizontalAlignment.Left : HorizontalAlignment.Right;
        }
    }

    public NativeVideoPlayerWindow(AppServices services, IReadOnlyList<DownloadTask> playlist, int startIndex = 0)
    {
        _services = services;
        _playlist = playlist;
        _index = Math.Clamp(startIndex, 0, Math.Max(0, playlist.Count - 1));

        InitializeComponent();
        _dispatcher = DispatcherQueue;
        _services.RegisterSecondaryWindow(this);
        NativeShellHelper.ConfigureWindowChrome(this, TitleBarDragRegion, baseWidth: 1020, baseHeight: 680);
        ApplyTheme();

        RootGrid.ActualThemeChanged += (_, _) => ApplyTheme();
        _services.MainVm.ThemeAppearanceChanged += OnThemeChanged;
        Closed += NativeVideoPlayerWindow_Closed;

        lock (ActiveLock) ActiveWindows.Add(this);

        SetupPlaylistUi();
        _ = LoadEpisodeAsync(_index);
    }

    private void OnThemeChanged(object? sender, (string Theme, bool PureBlack) e)
    {
        try
        {
            if (_dispatcher.HasThreadAccess) ApplyTheme();
            else _dispatcher.TryEnqueue(ApplyTheme);
        }
        catch
        {
        }
    }

    private void ApplyTheme()
    {
        if (_isClosed || NativeShellHelper.IsApplyingThemeRefresh) return;
        var state = _services.LocalState.Current;
        NativeShellHelper.ApplyThemeAndTitleBarColors(this, state.Theme, state.UsePureBlackDarkMode, state.AccentColor);
    }

    /// <summary>
    /// 在删除本地媒体文件前调用，及时释放可能正被播放窗口占用的文件句柄
    /// </summary>
    public static void ReleaseFileLocks(IEnumerable<string?> filePaths)
    {
        var targets = new HashSet<string>(
            filePaths.Where(p => !string.IsNullOrWhiteSpace(p)).Select(p => Path.GetFullPath(p!)),
            StringComparer.OrdinalIgnoreCase);
        if (targets.Count == 0) return;

        List<NativeVideoPlayerWindow> snapshot;
        lock (ActiveLock) snapshot = ActiveWindows.ToList();

        foreach (var win in snapshot)
        {
            if (win._isClosed) continue;
            var currentPath = win._playlist.ElementAtOrDefault(win._index)?.LocalFilePath;
            if (!string.IsNullOrWhiteSpace(currentPath) && targets.Contains(Path.GetFullPath(currentPath)))
            {
                try
                {
                    win._dispatcher.TryEnqueue(() =>
                    {
                        if (!win._isClosed)
                        {
                            win.DisposeCurrentPlayer();
                            try { win.Close(); } catch { }
                        }
                    });
                }
                catch
                {
                }
            }
        }
    }

    private void SetupPlaylistUi()
    {
        if (_playlist.Count <= 1) return;

        EpisodeBadge.Visibility = Visibility.Visible;
        PrevEpBtn.Visibility = Visibility.Visible;
        NextEpBtn.Visibility = Visibility.Visible;
        PlaylistCombo.Visibility = Visibility.Visible;

        _syncingPlaylist = true;
        try
        {
            PlaylistCombo.ItemsSource = _playlist.Select((t, i) =>
            {
                var p = t.EpisodeIndex > 0 ? $"P{t.EpisodeIndex}" : $"#{i + 1}";
                return $"{p} · {t.Title}";
            }).ToList();
            PlaylistCombo.SelectedIndex = _index;
        }
        finally
        {
            _syncingPlaylist = false;
        }
    }

    private async Task LoadEpisodeAsync(int index)
    {
        if (_isClosed || _playlist.Count == 0) return;
        _index = Math.Clamp(index, 0, _playlist.Count - 1);
        var gen = ++_loadGeneration;
        var task = _playlist[_index];

        _syncingPlaylist = true;
        try
        {
            if (PlaylistCombo.Visibility == Visibility.Visible)
            {
                PlaylistCombo.SelectedIndex = _index;
            }
        }
        finally
        {
            _syncingPlaylist = false;
        }

        var isAudio = OmniDomainLogic.ResolveMediaViewKind(task) == MediaViewKind.Audio;
        var displayTitle = string.IsNullOrWhiteSpace(task.Title) ? _services.Loc.T("媒体播放") : task.Title;
        Title = $"OmniDownloader · {displayTitle}";
        WindowTitleText.Text = _services.Loc.T("媒体播放");
        PlayerKindChipText.Text = _services.Loc.T(isAudio ? "音频播放" : "内置媒体引擎");
        NowPlayingTitleText.Text = displayTitle;
        TitleIcon.Glyph = isAudio ? "\uE8D6" : "\uE768";

        var metaParts = new[] { task.SelectedResolution, task.FileSizeText, task.Author }
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .ToArray();
        NowPlayingMetaText.Text = string.Join(" · ", metaParts);
        NowPlayingMetaText.Visibility = metaParts.Length > 0 ? Visibility.Visible : Visibility.Collapsed;

        if (_playlist.Count > 1)
        {
            EpisodeBadgeText.Text = $"{_index + 1} / {_playlist.Count}";
            PrevEpBtn.IsEnabled = _index > 0;
            NextEpBtn.IsEnabled = _index < _playlist.Count - 1;
        }

        ErrorOverlay.Visibility = Visibility.Collapsed;
        LoadingRing.IsActive = true;
        AudioCoverPanel.Visibility = isAudio ? Visibility.Visible : Visibility.Collapsed;
        if (isAudio)
        {
            NativeShellHelper.PlayFadeIn(AudioCoverPanel, durationMs: 180, fromOffsetY: 6);
        }
        if (isAudio)
        {
            AudioTitleText.Text = displayTitle;
            AudioSubText.Text = string.Join(" · ", new[] { task.Author, task.FileSizeText }.Where(s => !string.IsNullOrWhiteSpace(s)));
            AudioCoverImage.Source = null;
            if (!string.IsNullOrWhiteSpace(task.ThumbnailUrl))
            {
                _ = LoadAudioCoverAsync(task.ThumbnailUrl, gen, task.Id);
            }
        }

        DisposeCurrentMediaSourceOnly();

        MediaSource? mediaSource = null;
        string? openFailure = null;
        try
        {
            if (!string.IsNullOrWhiteSpace(task.LocalFilePath) && File.Exists(task.LocalFilePath))
            {
                var fullPath = Path.GetFullPath(task.LocalFilePath);
                var storageFile = await StorageFile.GetFileFromPathAsync(fullPath);
                mediaSource = MediaSource.CreateFromStorageFile(storageFile);
            }
            else
            {
                var url = _services.ApiClient.BuildAbsoluteUrl($"/api/tasks/{Uri.EscapeDataString(task.Id)}/view");
                mediaSource = MediaSource.CreateFromUri(new Uri(url));
            }
        }
        catch (Exception ex)
        {
            openFailure = ex.Message;
        }

        if (_isClosed || gen != _loadGeneration)
        {
            mediaSource?.Dispose();
            return;
        }

        if (mediaSource is null)
        {
            LoadingRing.IsActive = false;
            ShowPlayerError(_services.Loc.T("无法打开媒体源：%s", openFailure ?? _services.Loc.T("文件不存在或路径无效")));
            return;
        }

        _currentSource = mediaSource;
        if (_player is null)
        {
            var player = new MediaPlayer { AutoPlay = true };
            player.MediaOpened += Player_MediaOpened;
            player.MediaEnded += Player_MediaEnded;
            player.MediaFailed += Player_MediaFailed;
            _player = player;
            PlayerElement.SetMediaPlayer(player);
        }
        _player.Source = mediaSource;
        try { _player.Play(); } catch { }
    }

    private void DisposeCurrentMediaSourceOnly()
    {
        if (_player is not null)
        {
            try
            {
                _player.Pause();
                _player.Source = null;
            }
            catch
            {
            }
        }
        var src = _currentSource;
        _currentSource = null;
        try { src?.Dispose(); } catch { }
    }

    private async Task LoadAudioCoverAsync(string thumbUrl, int gen, string? taskId = null)
    {
        var bmp = await NativeShellHelper.LoadImageAsync(thumbUrl, decodePixelWidth: 420, taskId: taskId);
        if (!_isClosed && gen == _loadGeneration && bmp is not null)
        {
            AudioCoverImage.Source = bmp;
            NativeShellHelper.PlayFadeIn(AudioCoverImage, durationMs: 160);
        }
    }

    private void Player_MediaOpened(MediaPlayer sender, object args)
    {
        if (_isClosed) return;
        _dispatcher.TryEnqueue(() =>
        {
            if (!_isClosed) LoadingRing.IsActive = false;
        });
    }

    private void Player_MediaEnded(MediaPlayer sender, object args)
    {
        if (_isClosed) return;
        _dispatcher.TryEnqueue(() =>
        {
            if (_isClosed) return;
            if (_index + 1 < _playlist.Count)
            {
                _ = LoadEpisodeAsync(_index + 1);
            }
        });
    }

    private void Player_MediaFailed(MediaPlayer sender, MediaPlayerFailedEventArgs args)
    {
        if (_isClosed) return;
        _dispatcher.TryEnqueue(() =>
        {
            if (_isClosed) return;
            LoadingRing.IsActive = false;
            ShowPlayerError(_services.Loc.T("内置播放器暂不支持此编码或读取中断 (%s)，可使用系统默认播放器打开。", args.Error));
        });
    }

    private void ShowPlayerError(string msg)
    {
        ErrorDetailText.Text = msg;
        ErrorOverlay.Visibility = Visibility.Visible;
    }

    private void DisposeCurrentPlayer()
    {
        var player = _player;
        _player = null;
        PlayerElement.SetMediaPlayer(null);
        if (player is not null)
        {
            try
            {
                player.MediaOpened -= Player_MediaOpened;
                player.MediaEnded -= Player_MediaEnded;
                player.MediaFailed -= Player_MediaFailed;
                player.Pause();
                player.Source = null;
                player.Dispose();
            }
            catch
            {
            }
        }

        var src = _currentSource;
        _currentSource = null;
        try { src?.Dispose(); } catch { }
    }

    private void NativeVideoPlayerWindow_Closed(object sender, WindowEventArgs args)
    {
        _isClosed = true;
        _hudCts?.Cancel();
        _hudCts?.Dispose();
        _hudCts = null;
        _services.MainVm.ThemeAppearanceChanged -= OnThemeChanged;
        lock (ActiveLock) ActiveWindows.Remove(this);
        DisposeCurrentPlayer();
    }

    private void PrevEpBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_index > 0) _ = LoadEpisodeAsync(_index - 1);
    }

    private void NextEpBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_index + 1 < _playlist.Count) _ = LoadEpisodeAsync(_index + 1);
    }

    private void PlaylistCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingPlaylist || PlaylistCombo.SelectedIndex < 0) return;
        if (PlaylistCombo.SelectedIndex != _index)
        {
            _ = LoadEpisodeAsync(PlaylistCombo.SelectedIndex);
        }
    }

    private async void LocateBtn_Click(object sender, RoutedEventArgs e)
    {
        var task = _playlist.ElementAtOrDefault(_index);
        if (task is null) return;
        try
        {
            await _services.ApiClient.OpenTaskFileAsync(task.Id);
        }
        catch (Exception ex)
        {
            _services.MainVm.Notify(ex.Message, InfoBarSeverity.Error);
        }
    }

    private void ExternalBtn_Click(object sender, RoutedEventArgs e)
    {
        var task = _playlist.ElementAtOrDefault(_index);
        if (task is null) return;
        if (!string.IsNullOrWhiteSpace(task.LocalFilePath) && File.Exists(task.LocalFilePath))
        {
            NativeShellHelper.OpenWithShell(task.LocalFilePath);
        }
        else
        {
            NativeShellHelper.OpenWithShell(_services.ApiClient.BuildAbsoluteUrl($"/api/tasks/{Uri.EscapeDataString(task.Id)}/view"));
        }
    }

    private void FullscreenBtn_Click(object sender, RoutedEventArgs e) => ToggleFullScreen();

    private void PinTopBtn_Click(object sender, RoutedEventArgs e) => ToggleAlwaysOnTop();

    private void PlayerStageFrame_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e) => ToggleFullScreen();

    private void ToggleAlwaysOnTop()
    {
        _isPinnedOnTop = !_isPinnedOnTop;
        PinTopBtn.IsChecked = _isPinnedOnTop;
        PinTopIcon.Glyph = _isPinnedOnTop ? "\uE840" : "\uE718";
        ApplyAlwaysOnTopState();
        ShowPlaybackHud(_isPinnedOnTop ? "\uE840" : "\uE718", _services.Loc.T(_isPinnedOnTop ? "已置顶窗口" : "已取消置顶"));
    }

    private void ApplyAlwaysOnTopState()
    {
        try
        {
            if (AppWindow.Presenter is Microsoft.UI.Windowing.OverlappedPresenter overlapped)
            {
                overlapped.IsAlwaysOnTop = _isPinnedOnTop && !PlayerElement.IsFullWindow;
            }
        }
        catch
        {
        }
    }

    private void ShowPlaybackHud(string glyph, string message)
    {
        if (_isClosed) return;
        PlaybackHudIcon.Glyph = glyph;
        PlaybackHudText.Text = message;
        NativeShellHelper.FadeSetVisibility(PlaybackHudBorder, true, durationMs: 130);

        _hudCts?.Cancel();
        _hudCts?.Dispose();
        var cts = new CancellationTokenSource();
        _hudCts = cts;
        _ = HideHudLaterAsync(cts.Token);
    }

    private async Task HideHudLaterAsync(CancellationToken ct)
    {
        try
        {
            await Task.Delay(1100, ct);
            if (!_isClosed && !ct.IsCancellationRequested)
            {
                NativeShellHelper.FadeSetVisibility(PlaybackHudBorder, false, durationMs: 160);
            }
        }
        catch (TaskCanceledException)
        {
        }
    }

    private void ToggleFullScreen()
    {
        var enter = !PlayerElement.IsFullWindow;
        PlayerElement.IsFullWindow = enter;
        try
        {
            AppWindow.SetPresenter(enter
                ? Microsoft.UI.Windowing.AppWindowPresenterKind.FullScreen
                : Microsoft.UI.Windowing.AppWindowPresenterKind.Overlapped);
            var chromeVis = enter ? Visibility.Collapsed : Visibility.Visible;
            TitleBarDragRegion.Visibility = chromeVis;
            HeaderToolbarGrid.Visibility = chromeVis;
            PlayerFooterBar.Visibility = chromeVis;
            PlayerStageFrame.Margin = enter ? new Thickness(0) : new Thickness(14, 12, 14, 10);
            PlayerStageFrame.CornerRadius = enter ? new CornerRadius(0) : new CornerRadius(12);
            PlayerStageFrame.BorderThickness = enter ? new Thickness(0) : new Thickness(1);
            FullscreenIcon.Glyph = enter ? "\uE73F" : "\uE740";
            ApplyAlwaysOnTopState();
            ShowPlaybackHud(enter ? "\uE740" : "\uE73F", _services.Loc.T(enter ? "进入全屏" : "退出全屏"));
        }
        catch
        {
        }
    }

    private void RootGrid_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        var ctrlDown = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control)
            .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
        switch (e.Key)
        {
            case VirtualKey.Space:
                if (_player is not null)
                {
                    if (_player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing)
                    {
                        _player.Pause();
                        ShowPlaybackHud("\uE769", _services.Loc.T("已暂停"));
                    }
                    else
                    {
                        _player.Play();
                        ShowPlaybackHud("\uE768", _services.Loc.T("继续播放"));
                    }
                    e.Handled = true;
                }
                break;
            case VirtualKey.Left:
                if (ctrlDown)
                {
                    if (_index > 0) _ = LoadEpisodeAsync(_index - 1);
                    e.Handled = true;
                }
                else if (_player is not null)
                {
                    _player.PlaybackSession.Position = TimeSpan.FromSeconds(Math.Max(0, _player.PlaybackSession.Position.TotalSeconds - 5));
                    ShowPlaybackHud("\uEB9E", "-5s");
                    e.Handled = true;
                }
                break;
            case VirtualKey.Right:
                if (ctrlDown)
                {
                    if (_index + 1 < _playlist.Count) _ = LoadEpisodeAsync(_index + 1);
                    e.Handled = true;
                }
                else if (_player is not null)
                {
                    _player.PlaybackSession.Position = TimeSpan.FromSeconds(_player.PlaybackSession.Position.TotalSeconds + 5);
                    ShowPlaybackHud("\uEB9D", "+5s");
                    e.Handled = true;
                }
                break;
            case VirtualKey.T:
                ToggleAlwaysOnTop();
                e.Handled = true;
                break;
            case VirtualKey.M:
                if (_player is not null)
                {
                    _player.IsMuted = !_player.IsMuted;
                    ShowPlaybackHud(_player.IsMuted ? "\uE74F" : "\uE767", _services.Loc.T(_player.IsMuted ? "已静音" : "取消静音"));
                    e.Handled = true;
                }
                break;
            case VirtualKey.F:
            case VirtualKey.F11:
                ToggleFullScreen();
                e.Handled = true;
                break;
            case VirtualKey.Escape:
                if (PlayerElement.IsFullWindow)
                {
                    ToggleFullScreen();
                }
                else
                {
                    Close();
                }
                e.Handled = true;
                break;
        }
    }
}
