using System.Text.Json;
using System.Text.Json.Serialization;

namespace OmniDownloader.WinUI.Core.Models;

/// <summary>
/// Omni Download Rules (v1) WinUI Core 跨平台规则模型
/// </summary>
public sealed class RuleMatchConfigDto
{
    [JsonPropertyName("platforms")]
    public List<string> Platforms { get; set; } = new();

    [JsonPropertyName("domains")]
    public List<string> Domains { get; set; } = new();

    [JsonPropertyName("urlPatterns")]
    public List<string> UrlPatterns { get; set; } = new();

    [JsonPropertyName("urlMatchMode")]
    public string UrlMatchMode { get; set; } = "contains";

    [JsonPropertyName("mediaTypes")]
    public List<string> MediaTypes { get; set; } = new();

    [JsonPropertyName("titleKeywords")]
    public List<string> TitleKeywords { get; set; } = new();

    [JsonPropertyName("titleExcludeKeywords")]
    public List<string> TitleExcludeKeywords { get; set; } = new();

    [JsonPropertyName("titleRegex")]
    public string TitleRegex { get; set; } = string.Empty;

    [JsonPropertyName("authorKeywords")]
    public List<string> AuthorKeywords { get; set; } = new();

    [JsonPropertyName("authorExcludeKeywords")]
    public List<string> AuthorExcludeKeywords { get; set; } = new();

    [JsonPropertyName("minDurationSec")]
    public int MinDurationSec { get; set; }

    [JsonPropertyName("maxDurationSec")]
    public int MaxDurationSec { get; set; }
}

public sealed class RuleCollectionConfigDto
{
    [JsonPropertyName("mode")]
    public string Mode { get; set; } = "all";

    [JsonPropertyName("episodes")]
    public string Episodes { get; set; } = string.Empty;

    [JsonPropertyName("rangeStart")]
    public int RangeStart { get; set; }

    [JsonPropertyName("rangeEnd")]
    public int RangeEnd { get; set; }

    [JsonPropertyName("includeKeywords")]
    public List<string> IncludeKeywords { get; set; } = new();

    [JsonPropertyName("excludeKeywords")]
    public List<string> ExcludeKeywords { get; set; } = new();

    [JsonPropertyName("skipDownloaded")]
    public bool SkipDownloaded { get; set; } = true;
}

public sealed class RuleDownloadConfigDto
{
    [JsonPropertyName("mode")]
    public string Mode { get; set; } = "VIDEO_WITH_AUDIO";

    [JsonPropertyName("saveCover")]
    public bool SaveCover { get; set; }

    [JsonPropertyName("imageArchiveMode")]
    public string ImageArchiveMode { get; set; } = "zip";
}

public sealed class RuleQualityConfigDto
{
    [JsonPropertyName("strategy")]
    public string Strategy { get; set; } = "best";

    [JsonPropertyName("resolution")]
    public int Resolution { get; set; }

    [JsonPropertyName("codec")]
    public string Codec { get; set; } = "auto";

    [JsonPropertyName("minFps")]
    public int MinFps { get; set; }

    [JsonPropertyName("dynamicRange")]
    public string DynamicRange { get; set; } = "auto";

    [JsonPropertyName("container")]
    public string Container { get; set; } = "mp4";

    [JsonPropertyName("audioFormat")]
    public string AudioFormat { get; set; } = "mp3";

    [JsonPropertyName("audioBitrateKbps")]
    public int AudioBitrateKbps { get; set; }

    [JsonPropertyName("fallback")]
    public string Fallback { get; set; } = "best_available";
}

public sealed class RuleFileConfigDto
{
    [JsonPropertyName("filenameTemplate")]
    public string FilenameTemplate { get; set; } = "{title}";

    [JsonPropertyName("categoryDir")]
    public string CategoryDir { get; set; } = string.Empty;

    [JsonPropertyName("collectionSubfolder")]
    public bool CollectionSubfolder { get; set; } = true;

    [JsonPropertyName("collectionDirTemplate")]
    public string CollectionDirTemplate { get; set; } = "{collectionTitle}";

    [JsonPropertyName("duplicatePolicy")]
    public string DuplicatePolicy { get; set; } = "rename";
}

public sealed class RuleExecutionConfigDto
{
    [JsonPropertyName("action")]
    public string Action { get; set; } = "confirm";

    [JsonPropertyName("maxTasks")]
    public int MaxTasks { get; set; } = 100;
}

public sealed class DownloadRuleDto
{
    private static readonly JsonSerializerOptions PrettyOptions = new()
    {
        WriteIndented = true
    };

    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("description")]
    public string Description { get; set; } = string.Empty;

    [JsonPropertyName("enabled")]
    public bool Enabled { get; set; } = true;

    [JsonPropertyName("priority")]
    public int Priority { get; set; } = 100;

    [JsonPropertyName("importedUntrusted")]
    public bool ImportedUntrusted { get; set; }

    [JsonPropertyName("triggers")]
    public List<string> Triggers { get; set; } = new() { "manual", "collection", "batch", "clipboard" };

    [JsonPropertyName("match")]
    public RuleMatchConfigDto Match { get; set; } = new();

    [JsonPropertyName("collection")]
    public RuleCollectionConfigDto Collection { get; set; } = new();

    [JsonPropertyName("download")]
    public RuleDownloadConfigDto Download { get; set; } = new();

    [JsonPropertyName("quality")]
    public RuleQualityConfigDto Quality { get; set; } = new();

    [JsonPropertyName("files")]
    public RuleFileConfigDto Files { get; set; } = new();

    [JsonPropertyName("execution")]
    public RuleExecutionConfigDto Execution { get; set; } = new();

    [JsonPropertyName("platformOverrides")]
    public Dictionary<string, JsonElement> PlatformOverrides { get; set; } = new();

    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; }

    [JsonPropertyName("updatedAt")]
    public long UpdatedAt { get; set; }

    public string ToFormattedJson() => JsonSerializer.Serialize(this, PrettyOptions);

    public DownloadRuleDto Clone()
    {
        var json = JsonSerializer.Serialize(this);
        return JsonSerializer.Deserialize<DownloadRuleDto>(json) ?? new DownloadRuleDto();
    }
}

public sealed class RuleSettingsDto
{
    [JsonPropertyName("rulesSystemEnabled")]
    public bool RulesSystemEnabled { get; set; } = true;

    [JsonPropertyName("clipboardWatchEnabled")]
    public bool ClipboardWatchEnabled { get; set; }

    [JsonPropertyName("clipboardPaused")]
    public bool ClipboardPaused { get; set; }

    [JsonPropertyName("clipboardDebounceMs")]
    public int ClipboardDebounceMs { get; set; } = 550;

    [JsonPropertyName("clipboardDedupWindowSec")]
    public int ClipboardDedupWindowSec { get; set; } = 20;

    [JsonPropertyName("clipboardShowTrayNotice")]
    public bool ClipboardShowTrayNotice { get; set; } = true;
}

public sealed class PendingConfirmationDto
{
    [JsonPropertyName("confirmId")]
    public string ConfirmId { get; set; } = string.Empty;

    [JsonPropertyName("trigger")]
    public string Trigger { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("ruleId")]
    public string RuleId { get; set; } = string.Empty;

    [JsonPropertyName("ruleName")]
    public string RuleName { get; set; } = string.Empty;

    [JsonPropertyName("qualitySummary")]
    public string QualitySummary { get; set; } = string.Empty;

    [JsonPropertyName("plannedCount")]
    public int PlannedCount { get; set; }

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; }
}

public sealed class RuleDedupSummaryDto
{
    [JsonPropertyName("totalCount")]
    public int TotalCount { get; set; }
}

public sealed class RuleSchemaDocItem
{
    [JsonPropertyName("field")]
    public string Field { get; set; } = string.Empty;

    [JsonPropertyName("type")]
    public string Type { get; set; } = string.Empty;

    [JsonPropertyName("desc")]
    public string Desc { get; set; } = string.Empty;
}

public sealed class RulesOverviewResponse
{
    [JsonPropertyName("settings")]
    public RuleSettingsDto Settings { get; set; } = new();

    [JsonPropertyName("rules")]
    public List<DownloadRuleDto> Rules { get; set; } = new();

    [JsonPropertyName("pendingConfirmations")]
    public List<PendingConfirmationDto> PendingConfirmations { get; set; } = new();

    [JsonPropertyName("dedupSummary")]
    public RuleDedupSummaryDto DedupSummary { get; set; } = new();

    [JsonPropertyName("schemaDocs")]
    public List<RuleSchemaDocItem> SchemaDocs { get; set; } = new();
}

public sealed class RuleSaveResponse
{
    [JsonPropertyName("status")]
    public string Status { get; set; } = string.Empty;

    [JsonPropertyName("rule")]
    public DownloadRuleDto? Rule { get; set; }
}

public sealed class RuleValidationResponse
{
    [JsonPropertyName("valid")]
    public bool Valid { get; set; }

    [JsonPropertyName("count")]
    public int Count { get; set; }

    [JsonPropertyName("error")]
    public string Error { get; set; } = string.Empty;

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("rules")]
    public List<DownloadRuleDto> Rules { get; set; } = new();
}

public sealed class RuleImportResponse
{
    [JsonPropertyName("status")]
    public string Status { get; set; } = string.Empty;

    [JsonPropertyName("importedCount")]
    public int ImportedCount { get; set; }

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("rules")]
    public List<DownloadRuleDto> Rules { get; set; } = new();
}

public sealed class RuleEvaluationItemDto
{
    [JsonPropertyName("ruleId")]
    public string RuleId { get; set; } = string.Empty;

    [JsonPropertyName("ruleName")]
    public string RuleName { get; set; } = string.Empty;

    [JsonPropertyName("priority")]
    public int Priority { get; set; }

    [JsonPropertyName("preMatched")]
    public bool PreMatched { get; set; }

    [JsonPropertyName("preReason")]
    public string PreReason { get; set; } = string.Empty;

    [JsonPropertyName("postMatched")]
    public bool? PostMatched { get; set; }

    [JsonPropertyName("reasons")]
    public List<string> Reasons { get; set; } = new();

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();
}

public sealed class PlannedTaskPreviewDto
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("downloadType")]
    public string DownloadType { get; set; } = string.Empty;

    [JsonPropertyName("plannedPath")]
    public string PlannedPath { get; set; } = string.Empty;

    [JsonPropertyName("duplicatePolicy")]
    public string DuplicatePolicy { get; set; } = string.Empty;
}

public sealed class SkippedEpisodeDto
{
    [JsonPropertyName("index")]
    public int Index { get; set; }

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;
}

public sealed class RuleTestPreviewResponse
{
    [JsonPropertyName("matched")]
    public bool Matched { get; set; }

    [JsonPropertyName("bypassed")]
    public bool Bypassed { get; set; }

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;

    [JsonPropertyName("error")]
    public string Error { get; set; } = string.Empty;

    [JsonPropertyName("errorCode")]
    public string ErrorCode { get; set; } = string.Empty;

    [JsonPropertyName("action")]
    public string Action { get; set; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; set; } = string.Empty;

    [JsonPropertyName("qualitySummary")]
    public string QualitySummary { get; set; } = string.Empty;

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("matchedRule")]
    public DownloadRuleDto? MatchedRule { get; set; }

    [JsonPropertyName("context")]
    public Dictionary<string, JsonElement>? Context { get; set; }

    [JsonPropertyName("evaluations")]
    public List<RuleEvaluationItemDto> Evaluations { get; set; } = new();

    [JsonPropertyName("plannedTasks")]
    public List<PlannedTaskPreviewDto> PlannedTasks { get; set; } = new();

    [JsonPropertyName("skippedItems")]
    public List<SkippedEpisodeDto> SkippedItems { get; set; } = new();

    [JsonPropertyName("dedupSkipped")]
    public List<SkippedEpisodeDto> DedupSkipped { get; set; } = new();
}

public sealed class RuleExecuteResultItemDto
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("matched")]
    public bool Matched { get; set; }

    [JsonPropertyName("ruleId")]
    public string RuleId { get; set; } = string.Empty;

    [JsonPropertyName("ruleName")]
    public string RuleName { get; set; } = string.Empty;

    [JsonPropertyName("action")]
    public string Action { get; set; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; set; } = string.Empty;

    [JsonPropertyName("confirmId")]
    public string ConfirmId { get; set; } = string.Empty;

    [JsonPropertyName("plannedCount")]
    public int PlannedCount { get; set; }

    [JsonPropertyName("skippedCount")]
    public int SkippedCount { get; set; }

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("qualitySummary")]
    public string QualitySummary { get; set; } = string.Empty;

    [JsonPropertyName("error")]
    public string Error { get; set; } = string.Empty;

    [JsonPropertyName("errorCode")]
    public string ErrorCode { get; set; } = string.Empty;

    [JsonPropertyName("blockedByAuthOrSource")]
    public bool BlockedByAuthOrSource { get; set; }

    [JsonPropertyName("parsedKind")]
    public string ParsedKind { get; set; } = string.Empty;

    [JsonPropertyName("parsedData")]
    public JsonElement? ParsedData { get; set; }

    [JsonPropertyName("taskIds")]
    public List<string> TaskIds { get; set; } = new();

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;
}

public sealed class RuleExecuteResponse
{
    [JsonPropertyName("matched")]
    public bool Matched { get; set; }

    [JsonPropertyName("bypassed")]
    public bool Bypassed { get; set; }

    [JsonPropertyName("authBlocked")]
    public bool AuthBlocked { get; set; }

    [JsonPropertyName("results")]
    public List<RuleExecuteResultItemDto> Results { get; set; } = new();
}

public sealed class RuleAuditItemDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("trigger")]
    public string Trigger { get; set; } = string.Empty;

    [JsonPropertyName("url")]
    public string Url { get; set; } = string.Empty;

    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("ruleId")]
    public string RuleId { get; set; } = string.Empty;

    [JsonPropertyName("ruleName")]
    public string RuleName { get; set; } = string.Empty;

    [JsonPropertyName("status")]
    public string Status { get; set; } = string.Empty;

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;

    [JsonPropertyName("warnings")]
    public List<string> Warnings { get; set; } = new();

    [JsonPropertyName("plannedCount")]
    public int PlannedCount { get; set; }

    [JsonPropertyName("skippedCount")]
    public int SkippedCount { get; set; }
}

public sealed class RuleAuditListResponse
{
    [JsonPropertyName("items")]
    public List<RuleAuditItemDto> Items { get; set; } = new();
}

public sealed class RuleSelectorChoice
{
    public string Mode { get; set; } = "auto"; // auto | specific | bypass
    public string RuleId { get; set; } = string.Empty;
    public string DisplayName { get; set; } = string.Empty;
}
