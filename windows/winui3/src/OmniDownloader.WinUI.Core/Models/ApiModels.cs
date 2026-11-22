using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace OmniDownloader.WinUI.Core.Models;

/// <summary>
/// 媒体解析通用请求体（用于视频与图文解析）
/// </summary>
public sealed class AnalyzeRequest
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;
}

/// <summary>
/// 统一分析入口 /api/media/analyze 返回包
/// </summary>
public sealed class MediaAnalyzeEnvelope
{
    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "video";

    [JsonPropertyName("data")]
    public JsonElement Data { get; set; }
}

/// <summary>
/// 视频画质策略选择结构，对应后端 QualitySelection
/// </summary>
public sealed class QualitySelection
{
    [JsonPropertyName("mode")]
    public string Mode { get; set; } = "BEST_AVAILABLE";

    [JsonPropertyName("maxHeight")]
    public int MaxHeight { get; set; }

    [JsonPropertyName("targetFps")]
    public int TargetFps { get; set; }

    [JsonPropertyName("codecPreference")]
    public string CodecPreference { get; set; } = "auto";

    [JsonPropertyName("container")]
    public string Container { get; set; } = "mp4";

    [JsonPropertyName("allowLowerQuality")]
    public bool AllowLowerQuality { get; set; }

    [JsonPropertyName("dynamicRangePreference")]
    public string DynamicRangePreference { get; set; } = "auto";
}

/// <summary>
/// 视频/音频流格式条目，对应后端 FormatOption
/// </summary>
public sealed class FormatOption
{
    [JsonPropertyName("formatId")]
    public string FormatId { get; set; } = string.Empty;

    [JsonPropertyName("resolutionLabel")]
    public string ResolutionLabel { get; set; } = string.Empty;

    [JsonPropertyName("width")]
    public int Width { get; set; }

    [JsonPropertyName("height")]
    public int Height { get; set; }

    [JsonPropertyName("fps")]
    public int Fps { get; set; }

    [JsonPropertyName("ext")]
    public string Ext { get; set; } = "mp4";

    [JsonPropertyName("approximateSize")]
    public string ApproximateSize { get; set; } = string.Empty;

    [JsonPropertyName("isVideoOnly")]
    public bool IsVideoOnly { get; set; }

    [JsonPropertyName("isAudioOnly")]
    public bool IsAudioOnly { get; set; }

    [JsonPropertyName("note")]
    public string Note { get; set; } = string.Empty;

    [JsonPropertyName("vcodec")]
    public string VCodec { get; set; } = string.Empty;

    [JsonPropertyName("acodec")]
    public string ACodec { get; set; } = string.Empty;

    [JsonPropertyName("dynamicRange")]
    public string DynamicRange { get; set; } = string.Empty;

    [JsonPropertyName("videoBitrateKbps")]
    public double VideoBitrateKbps { get; set; }

    [JsonPropertyName("dimensionsVerified")]
    public bool DimensionsVerified { get; set; }

    [JsonIgnore]
    public string DisplaySummary
    {
        get
        {
            var dims = Width > 0 && Height > 0
                ? $"{Width}×{Height}"
                : (string.IsNullOrWhiteSpace(ResolutionLabel) ? "自动分辨率" : ResolutionLabel);
            var extText = string.IsNullOrWhiteSpace(Ext) ? "MP4" : Ext.ToUpperInvariant();
            var extra = new List<string>();
            if (Fps >= 50) extra.Add($"{Fps}fps");
            if (!string.IsNullOrWhiteSpace(DynamicRange) && !DynamicRange.Equals("SDR", StringComparison.OrdinalIgnoreCase))
            {
                extra.Add(DynamicRange.ToUpperInvariant());
            }

            var codecTag = FormatCodecTag(VCodec);
            if (!string.IsNullOrEmpty(codecTag)) extra.Add(codecTag);

            var tail = !string.IsNullOrWhiteSpace(ApproximateSize)
                ? ApproximateSize
                : (!string.IsNullOrWhiteSpace(Note)
                    ? Note
                    : (VideoBitrateKbps >= 1000
                        ? $"{VideoBitrateKbps / 1000.0:0.0} Mbps"
                        : (VideoBitrateKbps > 0 ? $"{VideoBitrateKbps:0} kbps" : "自动体积")));

            var extraText = extra.Count > 0 ? $" · {string.Join(" · ", extra)}" : string.Empty;
            return $"{dims} · {extText}{extraText} · {tail}";
        }
    }

    private static string FormatCodecTag(string? vcodec)
    {
        if (string.IsNullOrWhiteSpace(vcodec) || vcodec.Equals("none", StringComparison.OrdinalIgnoreCase)) return string.Empty;
        var lower = vcodec.ToLowerInvariant();
        if (lower.StartsWith("avc") || lower.Contains("h264")) return "H.264";
        if (lower.StartsWith("hev") || lower.StartsWith("hvc") || lower.Contains("h265")) return "HEVC";
        if (lower.StartsWith("av01") || lower == "av1") return "AV1";
        if (lower.StartsWith("vp09") || lower.StartsWith("vp9")) return "VP9";
        return vcodec.Split('.')[0].ToUpperInvariant();
    }
}

/// <summary>
/// 解析后的视频元数据，对应后端 VideoMetadata
/// </summary>
public sealed class VideoMetadata
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("author")]
    public string Author { get; set; } = string.Empty;

    [JsonPropertyName("durationText")]
    public string DurationText { get; set; } = string.Empty;

    [JsonPropertyName("thumbnailUrl")]
    public string ThumbnailUrl { get; set; } = string.Empty;

    [JsonPropertyName("siteName")]
    public string SiteName { get; set; } = string.Empty;

    [JsonPropertyName("isGif")]
    public bool IsGif { get; set; }

    [JsonPropertyName("mediaId")]
    public string MediaId { get; set; } = string.Empty;

    [JsonPropertyName("expectedDurationMs")]
    public long ExpectedDurationMs { get; set; }

    [JsonPropertyName("accountId")]
    public string AccountId { get; set; } = string.Empty;

    [JsonPropertyName("accountName")]
    public string AccountName { get; set; } = string.Empty;

    [JsonPropertyName("accountVerifiedAt")]
    public long AccountVerifiedAt { get; set; }

    [JsonPropertyName("exclusiveType")]
    public string ExclusiveType { get; set; } = "UNKNOWN";

    [JsonPropertyName("accessState")]
    public string AccessState { get; set; } = "UNKNOWN";

    [JsonPropertyName("accessEvidence")]
    public List<string> AccessEvidence { get; set; } = new();

    [JsonPropertyName("qualityWarning")]
    public string QualityWarning { get; set; } = string.Empty;

    [JsonPropertyName("qualityEvidence")]
    public string QualityEvidence { get; set; } = string.Empty;

    [JsonPropertyName("availableVideoFormats")]
    public List<FormatOption> AvailableVideoFormats { get; set; } = new();

    [JsonPropertyName("availableAudioFormats")]
    public List<FormatOption> AvailableAudioFormats { get; set; } = new();

    [JsonPropertyName("multiMediaList")]
    public List<VideoMetadata> MultiMediaList { get; set; } = new();
}

/// <summary>
/// 图文资源单张描述
/// </summary>
public sealed class ImageResource
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("previewUrl")]
    public string PreviewUrl { get; set; } = string.Empty;

    [JsonPropertyName("width")]
    public int Width { get; set; }

    [JsonPropertyName("height")]
    public int Height { get; set; }

    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "IMAGE";

    [JsonPropertyName("original")]
    public bool Original { get; set; } = true;
}

/// <summary>
/// 图文解析结果包
/// </summary>
public sealed class ImagePost
{
    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("contentId")]
    public string ContentId { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("author")]
    public string Author { get; set; } = string.Empty;

    [JsonPropertyName("resources")]
    public List<ImageResource> Resources { get; set; } = new();

    [JsonPropertyName("route")]
    public string Route { get; set; } = string.Empty;

    [JsonPropertyName("resolvedAt")]
    public long ResolvedAt { get; set; }
}

/// <summary>
/// 创建下载任务请求单条，对应后端 CreateTaskItem
/// </summary>
public sealed class CreateTaskItem
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("downloadType")]
    public string DownloadType { get; set; } = "VIDEO_WITH_AUDIO";

    [JsonPropertyName("selectedResolution")]
    public string SelectedResolution { get; set; } = string.Empty;

    [JsonPropertyName("imagePlatform")]
    public string ImagePlatform { get; set; } = string.Empty;

    [JsonPropertyName("selectedImageIds")]
    public List<string> SelectedImageIds { get; set; } = new();

    [JsonPropertyName("qualitySelection")]
    public QualitySelection? QualitySelection { get; set; }

    [JsonPropertyName("selectedVideoFormatId")]
    public string SelectedVideoFormatId { get; set; } = string.Empty;

    [JsonPropertyName("mediaId")]
    public string MediaId { get; set; } = string.Empty;

    [JsonPropertyName("audioFormat")]
    public string AudioFormat { get; set; } = "mp3";

    [JsonPropertyName("thumbnailUrl")]
    public string ThumbnailUrl { get; set; } = string.Empty;

    [JsonPropertyName("author")]
    public string Author { get; set; } = string.Empty;

    [JsonPropertyName("collectionId")]
    public string? CollectionId { get; set; }

    [JsonPropertyName("collectionTitle")]
    public string? CollectionTitle { get; set; }

    [JsonPropertyName("episodeIndex")]
    public int EpisodeIndex { get; set; }

    [JsonPropertyName("episodeTotal")]
    public int EpisodeTotal { get; set; }
}

public sealed class BatchCreateTaskRequest
{
    [JsonPropertyName("tasks")]
    public List<CreateTaskItem> Tasks { get; set; } = new();
}

/// <summary>
/// 服务端下载任务实体，对应后端 DownloadTask
/// </summary>
public sealed class DownloadTask
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("author")]
    public string Author { get; set; } = string.Empty;

    [JsonPropertyName("thumbnailUrl")]
    public string ThumbnailUrl { get; set; } = string.Empty;

    [JsonPropertyName("downloadType")]
    public string DownloadType { get; set; } = "VIDEO_WITH_AUDIO";

    [JsonPropertyName("selectedResolution")]
    public string SelectedResolution { get; set; } = string.Empty;

    [JsonPropertyName("imagePlatform")]
    public string ImagePlatform { get; set; } = string.Empty;

    [JsonPropertyName("selectedImageIds")]
    public List<string> SelectedImageIds { get; set; } = new();

    [JsonPropertyName("schemaVersion")]
    public int SchemaVersion { get; set; } = 3;

    [JsonPropertyName("qualitySelection")]
    public QualitySelection? QualitySelection { get; set; }

    [JsonPropertyName("selectedVideoFormatId")]
    public string SelectedVideoFormatId { get; set; } = string.Empty;

    [JsonPropertyName("mediaId")]
    public string MediaId { get; set; } = string.Empty;

    [JsonPropertyName("errorCode")]
    public string ErrorCode { get; set; } = string.Empty;

    [JsonPropertyName("audioFormat")]
    public string AudioFormat { get; set; } = "mp3";

    [JsonPropertyName("status")]
    public string Status { get; set; } = "PENDING";

    [JsonPropertyName("progress")]
    public double Progress { get; set; }

    [JsonPropertyName("speedText")]
    public string SpeedText { get; set; } = string.Empty;

    [JsonPropertyName("etaText")]
    public string EtaText { get; set; } = string.Empty;

    [JsonPropertyName("fileSizeText")]
    public string FileSizeText { get; set; } = string.Empty;

    [JsonPropertyName("localFilePath")]
    public string LocalFilePath { get; set; } = string.Empty;

    [JsonPropertyName("errorMessage")]
    public string ErrorMessage { get; set; } = string.Empty;

    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; }

    [JsonPropertyName("collectionId")]
    public string? CollectionId { get; set; }

    [JsonPropertyName("collectionTitle")]
    public string? CollectionTitle { get; set; }

    [JsonPropertyName("episodeIndex")]
    public int EpisodeIndex { get; set; }

    [JsonPropertyName("episodeTotal")]
    public int EpisodeTotal { get; set; }

    [JsonIgnore]
    public bool IsActive => Status is "PENDING" or "DOWNLOADING" or "PROCESSING" or "WAITING_FOR_AUTH";

    [JsonIgnore]
    public bool IsFinished => Status is "COMPLETED" or "FAILED" or "CANCELLED";
}

/// <summary>
/// 批量任务提交参数及返回结构
/// </summary>
public sealed class BatchInputRequest
{
    [JsonPropertyName("text")]
    public string Text { get; set; } = string.Empty;

    [JsonPropertyName("type")]
    public string Type { get; set; } = "VIDEO_WITH_AUDIO";

    [JsonPropertyName("audio")]
    public string Audio { get; set; } = "mp3";

    [JsonPropertyName("allEpisodes")]
    public bool AllEpisodes { get; set; }
}

public sealed class BatchJobOptions
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "VIDEO_WITH_AUDIO";

    [JsonPropertyName("audio")]
    public string Audio { get; set; } = "mp3";

    [JsonPropertyName("allEpisodes")]
    public bool AllEpisodes { get; set; }
}

public sealed class BatchJobItem
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; set; } = "PENDING";

    [JsonPropertyName("note")]
    public string Note { get; set; } = string.Empty;

    [JsonPropertyName("taskIds")]
    public List<string> TaskIds { get; set; } = new();
}

public sealed class BatchJob
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("options")]
    public BatchJobOptions Options { get; set; } = new();

    [JsonPropertyName("items")]
    public List<BatchJobItem> Items { get; set; } = new();
}

/// <summary>
/// 第三方解析中转站点配置
/// </summary>
public sealed class RelaySite
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("iconUrl")]
    public string IconUrl { get; set; } = string.Empty;
}

public sealed class RelaySitesResponse
{
    [JsonPropertyName("relaySites")]
    public List<RelaySite> RelaySites { get; set; } = new();

    [JsonPropertyName("hasShownRelayCompatTip")]
    public bool HasShownRelayCompatTip { get; set; }
}

/// <summary>
/// 系统配置，对应 /api/settings
/// </summary>
public sealed class AppSettings
{
    [JsonPropertyName("downloadDir")]
    public string DownloadDir { get; set; } = string.Empty;

    [JsonPropertyName("proxyUrl")]
    public string ProxyUrl { get; set; } = string.Empty;

    [JsonPropertyName("bilibiliCookie")]
    public string BilibiliCookie { get; set; } = string.Empty;

    [JsonPropertyName("hasBilibiliSession")]
    public bool HasBilibiliSession { get; set; }

    [JsonPropertyName("maxConcurrentTasks")]
    public int MaxConcurrentTasks { get; set; } = 3;

    [JsonPropertyName("theme")]
    public string Theme { get; set; } = "auto";

    [JsonPropertyName("relaySites")]
    public List<RelaySite> RelaySites { get; set; } = new();

    [JsonPropertyName("hasShownRelayCompatTip")]
    public bool HasShownRelayCompatTip { get; set; }

    [JsonPropertyName("autoStart")]
    public bool AutoStart { get; set; }

    [JsonPropertyName("version")]
    public string Version { get; set; } = string.Empty;
}

public sealed class UpdateSettingsRequest
{
    [JsonPropertyName("downloadDir")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? DownloadDir { get; set; }

    [JsonPropertyName("proxyUrl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ProxyUrl { get; set; }

    [JsonPropertyName("bilibiliCookie")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? BilibiliCookie { get; set; }

    [JsonPropertyName("maxConcurrentTasks")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? MaxConcurrentTasks { get; set; }

    [JsonPropertyName("theme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Theme { get; set; }

    [JsonPropertyName("autoStart")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? AutoStart { get; set; }
}

/// <summary>
/// 平台 Cookie 会话状态
/// </summary>
public sealed class SessionStatus
{
    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("state")]
    public string State { get; set; } = "MISSING";

    [JsonPropertyName("cookieCount")]
    public int CookieCount { get; set; }

    [JsonPropertyName("enabled")]
    public bool Enabled { get; set; } = true;

    [JsonPropertyName("generation")]
    public string Generation { get; set; } = string.Empty;
}

/// <summary>
/// 受控浏览器登录会话实时状态（对应 /api/sessions/{platform}/login 与 /capture）
/// </summary>
public sealed class BrowserLoginStatus
{
    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("phase")]
    public string Phase { get; set; } = "CLOSED";

    [JsonPropertyName("running")]
    public bool Running { get; set; }

    [JsonPropertyName("cookieCount")]
    public int CookieCount { get; set; }

    [JsonPropertyName("capturedAt")]
    public long CapturedAt { get; set; }

    [JsonPropertyName("skippedPartitionedCookies")]
    public int SkippedPartitionedCookies { get; set; }

    [JsonPropertyName("errorCode")]
    public string ErrorCode { get; set; } = string.Empty;

    [JsonPropertyName("alreadySaved")]
    public bool AlreadySaved { get; set; }
}

/// <summary>
/// 图集 ZIP 条目清单响应（对应 /api/tasks/{id}/zip-entries）
/// </summary>
public sealed class TaskZipEntriesResponse
{
    [JsonPropertyName("taskId")]
    public string TaskId { get; set; } = string.Empty;

    [JsonPropertyName("entries")]
    public List<string> Entries { get; set; } = new();
}

public enum MediaViewKind
{
    Video,
    Audio,
    Image,
    ImageArchive,
    Other
}

/// <summary>
/// 引擎组件诊断信息，对应 /api/engine/diagnostics
/// </summary>
public sealed class EngineDiagnostics
{
    [JsonPropertyName("python")]
    public string Python { get; set; } = string.Empty;

    [JsonPropertyName("ytDlp")]
    public string YtDlp { get; set; } = string.Empty;

    [JsonPropertyName("ejs")]
    public string Ejs { get; set; } = string.Empty;

    [JsonPropertyName("ffmpegAvailable")]
    public bool FfmpegAvailable { get; set; }

    [JsonPropertyName("denoAvailable")]
    public bool DenoAvailable { get; set; }
}

/// <summary>
/// yt-dlp 平台列表响应，对应 /api/platforms
/// </summary>
public sealed class PlatformCatalogResponse
{
    [JsonPropertyName("version")]
    public string Version { get; set; } = string.Empty;

    [JsonPropertyName("extractors")]
    public List<string> Extractors { get; set; } = new();
}

/// <summary>
/// 应用更新检测响应，对应 /api/updates
/// </summary>
public sealed class ReleaseUpdateInfo
{
    [JsonPropertyName("version")]
    public string Version { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;
}

public sealed class UpdateCheckResponse
{
    [JsonPropertyName("currentVersion")]
    public string CurrentVersion { get; set; } = string.Empty;

    [JsonPropertyName("update")]
    public ReleaseUpdateInfo? Update { get; set; }
}

/// <summary>
/// 浏览器辅助捕获候选视频项
/// </summary>
public sealed class BrowserAssistCandidate
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("width")]
    public int Width { get; set; }

    [JsonPropertyName("height")]
    public int Height { get; set; }

    [JsonPropertyName("duration")]
    public double Duration { get; set; }

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("sourcePage")]
    public string SourcePage { get; set; } = string.Empty;
}

/// <summary>
/// 任务分组显示条目：支持单独任务或合集聚合折叠卡
/// </summary>
public sealed class TaskGroupEntry
{
    public string Key { get; init; } = string.Empty;
    public bool IsCollection { get; init; }
    public string Title { get; init; } = string.Empty;
    public string CollectionId { get; init; } = string.Empty;
    public IReadOnlyList<DownloadTask> Items { get; init; } = Array.Empty<DownloadTask>();

    public DownloadTask PrimaryItem => Items[0];

    public int CompletedCount => Items.Count(t => t.Status == "COMPLETED");
    public int ActiveCount => Items.Count(t => t.IsActive);
    public int FailedCount => Items.Count(t => t.Status == "FAILED");

    public double OverallProgress
    {
        get
        {
            if (Items.Count == 0) return 0;
            return Items.Average(t => t.Status == "COMPLETED" ? 100.0 : Math.Clamp(t.Progress, 0, 100));
        }
    }
}

/// <summary>
/// 业务纯函数工具集（链接提取、画质策略构建、任务筛选聚合），供 UI 与单元测试复用
/// </summary>
public static class OmniDomainLogic
{
    private static readonly Regex ShareLinkRegex = new(
        @"https?://[^\s""'<>]+|\bBV[0-9A-Za-z]{10}\b",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// 从分享文本或逐行输入中提取并去重媒体链接（每批上限 100 条）
    /// </summary>
    public static IReadOnlyList<string> ExtractLinks(
        string? rawText,
        bool manualLineMode,
        IEnumerable<string>? existingDrafts = null)
    {
        if (string.IsNullOrWhiteSpace(rawText)) return Array.Empty<string>();

        var candidates = manualLineMode
            ? rawText.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(s => s.Trim())
            : ShareLinkRegex.Matches(rawText).Select(m => m.Value);

        var seen = new HashSet<string>(existingDrafts ?? Enumerable.Empty<string>(), StringComparer.Ordinal);
        var result = new List<string>();

        foreach (var raw in candidates)
        {
            var cleaned = raw.TrimEnd('。', '，', ',', '！', '!', '？', '?', '；', ';', ')', ']', '}', '’', '"');
            if (string.IsNullOrWhiteSpace(cleaned) || !seen.Add(cleaned)) continue;
            result.Add(cleaned);
        }

        return result;
    }

    /// <summary>
    /// 根据选择项构建发送给后端的 QualitySelection 意图对象
    /// </summary>
    public static QualitySelection BuildQualityPolicy(
        string policyKey,
        string container,
        bool allowLowerQuality,
        bool isBatchCollection = false,
        FormatOption? selectedFormat = null)
    {
        var selection = new QualitySelection
        {
            Mode = string.IsNullOrWhiteSpace(policyKey) ? "BEST_AVAILABLE" : policyKey,
            Container = string.IsNullOrWhiteSpace(container) ? "mp4" : container,
            AllowLowerQuality = allowLowerQuality,
        };

        if (policyKey.StartsWith("MAX_", StringComparison.Ordinal) || policyKey.StartsWith("TARGET_", StringComparison.Ordinal))
        {
            var parts = policyKey.Split('_');
            if (parts.Length == 2 && int.TryParse(parts[1], out var maxH))
            {
                selection.MaxHeight = maxH;
            }
            selection.Mode = policyKey.StartsWith("MAX_", StringComparison.Ordinal) ? "MAX_HEIGHT" : "TARGET_TIER";
        }
        else if (policyKey is "HDR" or "SDR")
        {
            selection.Mode = "BEST_AVAILABLE";
            selection.DynamicRangePreference = policyKey;
        }
        else if (policyKey == "FPS60")
        {
            selection.Mode = "BEST_AVAILABLE";
            selection.TargetFps = 60;
        }
        else if (policyKey == "COMPATIBLE")
        {
            selection.Mode = "BEST_AVAILABLE";
            selection.CodecPreference = "avc";
        }

        if (isBatchCollection && policyKey == "EXACT_FORMAT")
        {
            selection.Mode = "MAX_HEIGHT";
            if (selectedFormat is not null)
            {
                var w = selectedFormat.Width;
                var h = selectedFormat.Height;
                selection.MaxHeight = w > 0 && h > 0 ? Math.Min(w, h) : Math.Max(w, h);
            }
            else
            {
                selection.MaxHeight = 0;
            }
        }

        return selection;
    }

    /// <summary>
    /// 按筛选Tab（all / active / completed）过滤并将同 CollectionId 多集聚合为合集折叠条目
    /// </summary>
    public static IReadOnlyList<TaskGroupEntry> FilterAndGroupTasks(
        IEnumerable<DownloadTask>? allTasks,
        string filterMode = "all")
    {
        var tasks = allTasks?.ToList() ?? new List<DownloadTask>();

        IEnumerable<DownloadTask> filtered = filterMode switch
        {
            "active" => tasks.Where(t => t.IsActive),
            "completed" => tasks.Where(t => t.Status == "COMPLETED"),
            _ => tasks
        };

        // 按原始出现顺序将同一 collectionId 归组，单条不合并成合集卡
        var grouped = new Dictionary<string, List<DownloadTask>>(StringComparer.Ordinal);
        var order = new List<string>();

        foreach (var task in filtered)
        {
            var key = !string.IsNullOrWhiteSpace(task.CollectionId)
                ? $"col:{task.CollectionId}"
                : $"single:{task.Id}";

            if (!grouped.TryGetValue(key, out var list))
            {
                list = new List<DownloadTask>();
                grouped[key] = list;
                order.Add(key);
            }
            list.Add(task);
        }

        var entries = new List<TaskGroupEntry>(order.Count);
        foreach (var key in order)
        {
            var list = grouped[key];
            if (key.StartsWith("col:", StringComparison.Ordinal) && list.Count > 1)
            {
                var orderedItems = list.OrderBy(t => t.EpisodeIndex).ThenBy(t => t.CreatedAt).ToList();
                var title = orderedItems.FirstOrDefault(t => !string.IsNullOrWhiteSpace(t.CollectionTitle))?.CollectionTitle
                            ?? orderedItems[0].Title;
                entries.Add(new TaskGroupEntry
                {
                    Key = key,
                    IsCollection = true,
                    CollectionId = orderedItems[0].CollectionId ?? string.Empty,
                    Title = title,
                    Items = orderedItems
                });
            }
            else
            {
                foreach (var item in list)
                {
                    entries.Add(new TaskGroupEntry
                    {
                        Key = $"single:{item.Id}",
                        IsCollection = false,
                        Title = item.Title,
                        Items = new[] { item }
                    });
                }
            }
        }

        return entries;
    }

    public static string TypeLabelKey(string? downloadType) => downloadType switch
    {
        "VIDEO_WITH_AUDIO" => "完整",
        "VIDEO_ONLY" => "纯画",
        "AUDIO_ONLY" => "音频",
        "GIF" => "动图",
        "COVER" => "封面",
        "IMAGE" => "原图",
        "IMAGE_ZIP" => "图集ZIP",
        _ => "媒体"
    };

    public static string StatusLabelKey(string? status) => status switch
    {
        "PENDING" => "队列等待",
        "DOWNLOADING" => "正在下载",
        "PROCESSING" => "正在合并",
        "WAITING_FOR_AUTH" => "等待验证",
        "COMPLETED" => "已完成",
        "FAILED" => "失败",
        "CANCELLED" => "已取消",
        _ => status ?? "未知"
    };

    private static readonly HashSet<string> SupportedImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif"
    };

    private static readonly HashSet<string> SupportedAudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".flac", ".opus", ".ogg", ".wav", ".aac"
    };

    private static readonly HashSet<string> SupportedVideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".flv"
    };

    public static bool IsSupportedImageFileName(string? fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName)) return false;
        var ext = Path.GetExtension(fileName.Trim());
        return !string.IsNullOrEmpty(ext) && SupportedImageExtensions.Contains(ext);
    }

    /// <summary>
    /// 结合任务类型与实际输出文件扩展名判断在软件内的原生查看/播放方式
    /// </summary>
    public static MediaViewKind ResolveMediaViewKind(DownloadTask? task)
    {
        if (task is null) return MediaViewKind.Other;
        var ext = Path.GetExtension(task.LocalFilePath ?? string.Empty);

        if (string.Equals(task.DownloadType, "IMAGE_ZIP", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(ext, ".zip", StringComparison.OrdinalIgnoreCase))
        {
            return MediaViewKind.ImageArchive;
        }

        if (!string.IsNullOrEmpty(ext))
        {
            if (SupportedVideoExtensions.Contains(ext)) return MediaViewKind.Video;
            if (SupportedAudioExtensions.Contains(ext)) return MediaViewKind.Audio;
            if (SupportedImageExtensions.Contains(ext)) return MediaViewKind.Image;
        }

        return task.DownloadType switch
        {
            "IMAGE" or "COVER" or "GIF" => MediaViewKind.Image,
            "AUDIO_ONLY" => MediaViewKind.Audio,
            "VIDEO_WITH_AUDIO" or "VIDEO_ONLY" => MediaViewKind.Video,
            _ => MediaViewKind.Video
        };
    }

    public static string TaskViewActionText(DownloadTask? task) => ResolveMediaViewKind(task) switch
    {
        MediaViewKind.Image or MediaViewKind.ImageArchive => "查看",
        _ => "播放"
    };

    public static string TaskViewActionGlyph(DownloadTask? task) => ResolveMediaViewKind(task) switch
    {
        MediaViewKind.Image or MediaViewKind.ImageArchive => "\uEB9F",
        MediaViewKind.Audio => "\uE8D6",
        _ => "\uE768"
    };

    /// <summary>
    /// 安全读取图集 ZIP 中的图片条目清单并按自然数字顺序排列
    /// </summary>
    public static IReadOnlyList<string> ListZipImageEntries(string zipPath)
    {
        if (string.IsNullOrWhiteSpace(zipPath) || !File.Exists(zipPath)) return Array.Empty<string>();
        try
        {
            using var archive = System.IO.Compression.ZipFile.OpenRead(zipPath);
            var entries = archive.Entries
                .Where(e => e.Length > 0 && !string.IsNullOrEmpty(e.Name) && IsSupportedImageFileName(e.FullName))
                .Select(e => e.FullName)
                .OrderBy(name => Regex.Replace(name, @"\d+", m => m.Value.PadLeft(10, '0')), StringComparer.OrdinalIgnoreCase)
                .ToList();
            return entries;
        }
        catch
        {
            return Array.Empty<string>();
        }
    }
}
