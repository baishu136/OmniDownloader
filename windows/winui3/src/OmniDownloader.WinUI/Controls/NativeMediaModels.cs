using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生图片查看器统一数据源项：同时支持在线图文解析结果、本地已完成图片、图集 ZIP 内部条目与服务端流预览
/// </summary>
public sealed class NativeImageEntry
{
    public string Title { get; init; } = string.Empty;
    public string Subtitle { get; init; } = string.Empty;
    public string LocalFilePath { get; init; } = string.Empty;
    public string ZipArchivePath { get; init; } = string.Empty;
    public string ZipEntryName { get; init; } = string.Empty;
    public string RemoteUrl { get; init; } = string.Empty;
    public string TaskId { get; init; } = string.Empty;

    // 仅在线图文解析预览时支持单独保存原图
    public bool AllowDownloadSingle { get; init; }
    public string ImageResourceId { get; init; } = string.Empty;
    public string PostUrl { get; init; } = string.Empty;
    public string Platform { get; init; } = string.Empty;
    public string Author { get; init; } = string.Empty;
    public string ContentId { get; init; } = string.Empty;
}

/// <summary>
/// 统一托管应用内原生视频播放窗口与原生图片查看窗口的创建、清单构造与销毁协调
/// </summary>
public static class MediaWindowCoordinator
{
    public static async Task OpenTaskMediaAsync(
        AppServices services,
        DownloadTask task,
        IReadOnlyList<DownloadTask>? siblingTasks = null,
        Action<string, InfoBarSeverity>? notify = null)
    {
        if (task is null || services.IsShuttingDown) return;
        try
        {
            var kind = OmniDomainLogic.ResolveMediaViewKind(task);

            if (kind == MediaViewKind.ImageArchive)
            {
                await OpenImageZipTaskAsync(services, task, notify);
                return;
            }

            if (kind == MediaViewKind.Image)
            {
                var imageTasks = (siblingTasks ?? Array.Empty<DownloadTask>())
                    .Where(t => t.Status == "COMPLETED" && OmniDomainLogic.ResolveMediaViewKind(t) == MediaViewKind.Image)
                    .ToList();
                if (!imageTasks.Any(t => t.Id == task.Id))
                {
                    imageTasks = new List<DownloadTask> { task };
                }

                var startIdx = Math.Max(0, imageTasks.FindIndex(t => t.Id == task.Id));
                var entries = imageTasks.Select(t => new NativeImageEntry
                {
                    Title = string.IsNullOrWhiteSpace(t.Title) ? "图片查看" : t.Title,
                    Subtitle = string.IsNullOrWhiteSpace(t.FileSizeText) ? "本地图片" : t.FileSizeText,
                    LocalFilePath = t.LocalFilePath ?? string.Empty,
                    RemoteUrl = services.ApiClient.BuildAbsoluteUrl($"/api/tasks/{Uri.EscapeDataString(t.Id)}/view"),
                    TaskId = t.Id
                }).ToList();

                OpenImageWindow(services, string.IsNullOrWhiteSpace(task.CollectionTitle) ? task.Title : task.CollectionTitle!, entries, startIdx);
                return;
            }

            var playable = (siblingTasks ?? Array.Empty<DownloadTask>())
                .Where(t => t.Status == "COMPLETED" && OmniDomainLogic.ResolveMediaViewKind(t) is MediaViewKind.Video or MediaViewKind.Audio)
                .ToList();
            if (!playable.Any(t => t.Id == task.Id))
            {
                playable = new List<DownloadTask> { task };
            }

            var mediaIndex = Math.Max(0, playable.FindIndex(t => t.Id == task.Id));
            var window = new NativeVideoPlayerWindow(services, playable, mediaIndex);
            window.Activate();
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("MediaWindowCoordinator", ex);
            notify?.Invoke($"无法打开原生媒体窗口: {ex.Message}", InfoBarSeverity.Error);
        }
    }

    public static void OpenImagePost(AppServices services, ImagePost post, int startIndex)
    {
        if (post is null || post.Resources.Count == 0 || services.IsShuttingDown) return;

        var entries = post.Resources.Select((res, idx) =>
        {
            var dim = res.Width > 0 && res.Height > 0 ? $"{res.Width}×{res.Height}" : string.Empty;
            var tag = res.Kind == "UGOIRA" ? "动图封面" : (res.Original ? "原图" : "高清预览");
            var subtitle = string.IsNullOrWhiteSpace(dim) ? tag : $"{dim} · {tag}";
            return new NativeImageEntry
            {
                Title = string.IsNullOrWhiteSpace(post.Title) ? $"图片 {idx + 1}" : post.Title,
                Subtitle = subtitle,
                RemoteUrl = services.ApiClient.BuildAbsoluteUrl(string.IsNullOrWhiteSpace(res.PreviewUrl) ? res.Url : res.PreviewUrl),
                AllowDownloadSingle = true,
                ImageResourceId = res.Id,
                PostUrl = post.Url,
                Platform = post.Platform,
                Author = post.Author,
                ContentId = post.ContentId
            };
        }).ToList();

        OpenImageWindow(services, string.IsNullOrWhiteSpace(post.Title) ? "图片预览" : post.Title, entries, startIndex);
    }

    private static async Task OpenImageZipTaskAsync(
        AppServices services,
        DownloadTask task,
        Action<string, InfoBarSeverity>? notify)
    {
        var entries = new List<NativeImageEntry>();

        if (!string.IsNullOrWhiteSpace(task.LocalFilePath) && File.Exists(task.LocalFilePath))
        {
            var names = await Task.Run(() => OmniDomainLogic.ListZipImageEntries(task.LocalFilePath));
            foreach (var entryName in names)
            {
                entries.Add(new NativeImageEntry
                {
                    Title = string.IsNullOrWhiteSpace(task.Title) ? Path.GetFileName(entryName) : task.Title,
                    Subtitle = Path.GetFileName(entryName),
                    ZipArchivePath = task.LocalFilePath,
                    ZipEntryName = entryName,
                    TaskId = task.Id
                });
            }
        }
        else
        {
            try
            {
                var remote = await services.ApiClient.GetTaskZipEntriesAsync(task.Id);
                foreach (var entryName in remote.Entries)
                {
                    entries.Add(new NativeImageEntry
                    {
                        Title = string.IsNullOrWhiteSpace(task.Title) ? Path.GetFileName(entryName) : task.Title,
                        Subtitle = Path.GetFileName(entryName),
                        RemoteUrl = services.ApiClient.BuildZipEntryViewUrl(task.Id, entryName),
                        TaskId = task.Id
                    });
                }
            }
            catch (Exception ex)
            {
                notify?.Invoke($"读取图集压缩包失败: {ex.Message}", InfoBarSeverity.Error);
                return;
            }
        }

        if (entries.Count == 0)
        {
            notify?.Invoke("压缩包内未找到可直接预览的图片文件，正在改用系统打开", InfoBarSeverity.Warning);
            if (!string.IsNullOrWhiteSpace(task.LocalFilePath) && File.Exists(task.LocalFilePath))
            {
                NativeShellHelper.OpenWithShell(task.LocalFilePath);
            }
            return;
        }

        OpenImageWindow(services, string.IsNullOrWhiteSpace(task.Title) ? "图集浏览" : task.Title, entries, 0);
    }

    private static void OpenImageWindow(
        AppServices services,
        string windowTitle,
        IReadOnlyList<NativeImageEntry> entries,
        int startIndex)
    {
        var window = new NativeImageViewerWindow(services, windowTitle, entries, startIndex);
        window.Activate();
    }
}
