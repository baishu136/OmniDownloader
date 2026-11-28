using System.Net.Http.Json;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using OmniDownloader.WinUI.Core.Models;

namespace OmniDownloader.WinUI.Core.Services;

/// <summary>
/// 服务端业务异常，保留 HTTP 状态码与结构化 errorCode
/// </summary>
public sealed class OmniApiException : Exception
{
    public int StatusCode { get; }
    public string ErrorCode { get; }

    public OmniApiException(int statusCode, string message, string errorCode = "")
        : base(message)
    {
        StatusCode = statusCode;
        ErrorCode = errorCode;
    }
}

/// <summary>
/// 面向 windows/omni_server.py 的完整强类型 HTTP + SSE 客户端
/// </summary>
public sealed class OmniApiClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpClient _http;
    private readonly HttpClient _sseHttp;
    private readonly bool _ownsHttp;
    private volatile string _baseUrl;
    private volatile Uri _baseUri;

    public string BaseUrl => _baseUrl;

    public OmniApiClient(string baseUrl = "http://127.0.0.1:58000", HttpMessageHandler? customHandler = null)
    {
        _baseUrl = NormalizeBaseUrl(baseUrl);
        _baseUri = new Uri(_baseUrl + "/");
        if (customHandler is not null)
        {
            _http = new HttpClient(customHandler, disposeHandler: false) { Timeout = TimeSpan.FromMinutes(3) };
            _sseHttp = new HttpClient(customHandler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
            _ownsHttp = false;
        }
        else
        {
            var apiHandler = new SocketsHttpHandler
            {
                PooledConnectionLifetime = TimeSpan.FromMinutes(2),
                ConnectTimeout = TimeSpan.FromSeconds(2),
                AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate
            };
            var sseHandler = new SocketsHttpHandler
            {
                PooledConnectionLifetime = TimeSpan.FromMinutes(10),
                ConnectTimeout = TimeSpan.FromSeconds(2)
            };
            _http = new HttpClient(apiHandler, disposeHandler: true) { Timeout = TimeSpan.FromMinutes(3) };
            _sseHttp = new HttpClient(sseHandler, disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
            _ownsHttp = true;
        }
    }

    public void UpdateBaseUrl(string baseUrl)
    {
        var normalized = NormalizeBaseUrl(baseUrl);
        if (string.Equals(normalized, _baseUrl, StringComparison.OrdinalIgnoreCase)) return;
        _baseUrl = normalized;
        _baseUri = new Uri(normalized + "/");
    }

    private static string NormalizeBaseUrl(string url)
    {
        var trimmed = string.IsNullOrWhiteSpace(url) ? "http://127.0.0.1:58000" : url.Trim().TrimEnd('/');
        if (!trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            trimmed = "http://" + trimmed;
        }
        // 将 localhost 归一化为 127.0.0.1，避免 Windows 下 ::1 IPv6 TCP 回落等待 1 秒
        if (Uri.TryCreate(trimmed, UriKind.Absolute, out var uri) &&
            uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase))
        {
            var ub = new UriBuilder(uri) { Host = "127.0.0.1" };
            trimmed = ub.Uri.ToString().TrimEnd('/');
        }
        return trimmed;
    }

    private Uri BuildRequestUri(string path) => new(_baseUri, path.TrimStart('/'));

    public string BuildAbsoluteUrl(string relativeOrAbsolute)
    {
        if (string.IsNullOrWhiteSpace(relativeOrAbsolute)) return string.Empty;
        if (relativeOrAbsolute.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
            relativeOrAbsolute.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return relativeOrAbsolute;
        }
        return _baseUrl + (relativeOrAbsolute.StartsWith('/') ? relativeOrAbsolute : "/" + relativeOrAbsolute);
    }

    private static async Task EnsureSuccessAsync(HttpResponseMessage resp, CancellationToken ct)
    {
        if (resp.IsSuccessStatusCode) return;

        var raw = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        var message = LocalizationService.Instance.T("请求失败 (%d)", (int)resp.StatusCode);
        var errorCode = string.Empty;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            if (doc.RootElement.TryGetProperty("detail", out var d))
            {
                message = d.ValueKind == JsonValueKind.String ? d.GetString() ?? message : d.ToString();
            }
            if (doc.RootElement.TryGetProperty("errorCode", out var ec) && ec.ValueKind == JsonValueKind.String)
            {
                errorCode = ec.GetString() ?? string.Empty;
            }
        }
        catch
        {
            if (!string.IsNullOrWhiteSpace(raw)) message = raw.Trim();
        }
        throw new OmniApiException((int)resp.StatusCode, message, errorCode);
    }

    private async Task<T> GetJsonAsync<T>(string path, CancellationToken ct = default)
    {
        using var resp = await _http.GetAsync(BuildRequestUri(path), ct).ConfigureAwait(false);
        await EnsureSuccessAsync(resp, ct).ConfigureAwait(false);
        return (await resp.Content.ReadFromJsonAsync<T>(JsonOptions, ct).ConfigureAwait(false))!;
    }

    private async Task<T> SendJsonAsync<T>(HttpMethod method, string path, object? body = null, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(method, BuildRequestUri(path));
        if (body is not null)
        {
            req.Content = JsonContent.Create(body, options: JsonOptions);
        }
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        await EnsureSuccessAsync(resp, ct).ConfigureAwait(false);
        if (typeof(T) == typeof(JsonElement))
        {
            var text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
            return (T)(object)doc.RootElement.Clone();
        }
        return (await resp.Content.ReadFromJsonAsync<T>(JsonOptions, ct).ConfigureAwait(false))!;
    }

    // === 媒体与图文分析 ===

    public async Task<(string Kind, VideoMetadata? Video, ImagePost? Images)> AnalyzeMediaAsync(string url, CancellationToken ct = default)
    {
        var env = await SendJsonAsync<MediaAnalyzeEnvelope>(HttpMethod.Post, "/api/media/analyze", new AnalyzeRequest { Url = url }, ct).ConfigureAwait(false);
        if (string.Equals(env.Kind, "images", StringComparison.OrdinalIgnoreCase))
        {
            var images = JsonSerializer.Deserialize<ImagePost>(env.Data.GetRawText(), JsonOptions);
            return ("images", null, images);
        }
        var video = JsonSerializer.Deserialize<VideoMetadata>(env.Data.GetRawText(), JsonOptions);
        return ("video", video, null);
    }

    // === 任务提交与下载管理 ===

    public Task<JsonElement> SubmitDownloadTasksAsync(IEnumerable<CreateTaskItem> items, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/download", new BatchCreateTaskRequest { Tasks = items.ToList() }, ct);

    public Task<List<DownloadTask>> GetTasksAsync(CancellationToken ct = default) =>
        GetJsonAsync<List<DownloadTask>>("/api/tasks", ct);

    public Task<JsonElement> RetryTaskAsync(string taskId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/tasks/{Uri.EscapeDataString(taskId)}/retry", null, ct);

    public Task<JsonElement> CancelTaskAsync(string taskId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/tasks/{Uri.EscapeDataString(taskId)}/cancel", null, ct);

    public Task<JsonElement> DeleteTaskAsync(string taskId, bool deleteFile, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Delete, $"/api/tasks/{Uri.EscapeDataString(taskId)}?deleteFile={(deleteFile ? "true" : "false")}", null, ct);

    public Task<JsonElement> ClearTasksAsync(bool clearAll, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/tasks/clear?clearAll={(clearAll ? "true" : "false")}", null, ct);

    public Task<JsonElement> OpenTaskFileAsync(string taskId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/tasks/{Uri.EscapeDataString(taskId)}/open", null, ct);

    public Task<JsonElement> OpenDownloadFolderAsync(CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/open-folder", null, ct);

    // === 批量下载 ===

    public Task<BatchJob> CreateBatchAsync(BatchInputRequest req, CancellationToken ct = default) =>
        SendJsonAsync<BatchJob>(HttpMethod.Post, "/api/batches", req, ct);

    public Task<List<BatchJob>> GetBatchesAsync(CancellationToken ct = default) =>
        GetJsonAsync<List<BatchJob>>("/api/batches", ct);

    public Task<BatchJob> ControlBatchAsync(string batchId, string action, CancellationToken ct = default) =>
        SendJsonAsync<BatchJob>(HttpMethod.Post, $"/api/batches/{Uri.EscapeDataString(batchId)}/{Uri.EscapeDataString(action)}", null, ct);

    public Task<JsonElement> ClearBatchesAsync(bool clearAll = true, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/batches/clear?clearAll={(clearAll ? "true" : "false")}", null, ct);

    // === 设置与中转站 ===

    public Task<AppSettings> GetSettingsAsync(CancellationToken ct = default) =>
        GetJsonAsync<AppSettings>("/api/settings", ct);

    public Task<JsonElement> UpdateSettingsAsync(UpdateSettingsRequest req, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/settings", req, ct);

    public Task<RelaySitesResponse> GetRelaySitesAsync(CancellationToken ct = default) =>
        GetJsonAsync<RelaySitesResponse>("/api/relay-sites", ct);

    public Task<RelaySitesResponse> AddRelaySiteAsync(string name, string url, CancellationToken ct = default) =>
        SendJsonAsync<RelaySitesResponse>(HttpMethod.Post, "/api/relay-sites", new { name, url }, ct);

    public Task<RelaySitesResponse> DeleteRelaySiteAsync(string siteId, CancellationToken ct = default) =>
        SendJsonAsync<RelaySitesResponse>(HttpMethod.Delete, $"/api/relay-sites/{Uri.EscapeDataString(siteId)}", null, ct);

    public Task<JsonElement> ConfirmRelayTipAsync(CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/relay-sites/confirm-tip", null, ct);

    public async Task<string> FetchRelayTitleAsync(string url, CancellationToken ct = default)
    {
        var el = await GetJsonAsync<JsonElement>($"/api/relay-sites/fetch-title?url={Uri.EscapeDataString(url)}", ct).ConfigureAwait(false);
        return el.TryGetProperty("title", out var t) ? t.GetString() ?? string.Empty : string.Empty;
    }

    // === 账号会话管理 ===

    public Task<List<SessionStatus>> GetSessionsAsync(CancellationToken ct = default) =>
        GetJsonAsync<List<SessionStatus>>("/api/sessions", ct);

    public Task<BrowserLoginStatus> GetBrowserLoginStatusAsync(string platform, CancellationToken ct = default) =>
        GetJsonAsync<BrowserLoginStatus>($"/api/sessions/{Uri.EscapeDataString(platform)}/login", ct);

    public Task<BrowserLoginStatus> OpenBrowserLoginAsync(string platform, CancellationToken ct = default) =>
        SendJsonAsync<BrowserLoginStatus>(HttpMethod.Post, $"/api/sessions/{Uri.EscapeDataString(platform)}/login", null, ct);

    public Task<BrowserLoginStatus> SaveBrowserLoginAsync(string platform, CancellationToken ct = default) =>
        SendJsonAsync<BrowserLoginStatus>(HttpMethod.Post, $"/api/sessions/{Uri.EscapeDataString(platform)}/capture", null, ct);

    public Task<TaskZipEntriesResponse> GetTaskZipEntriesAsync(string taskId, CancellationToken ct = default) =>
        GetJsonAsync<TaskZipEntriesResponse>($"/api/tasks/{Uri.EscapeDataString(taskId)}/zip-entries", ct);

    public string BuildZipEntryViewUrl(string taskId, string entry) =>
        BuildAbsoluteUrl($"/api/tasks/{Uri.EscapeDataString(taskId)}/zip-entry?entry={Uri.EscapeDataString(entry)}");

    public Task<SessionStatus> ImportSessionAsync(string platform, string cookiesText, CancellationToken ct = default) =>
        SendJsonAsync<SessionStatus>(HttpMethod.Post, "/api/sessions", new { platform, cookies = cookiesText }, ct);

    public Task<SessionStatus> ClearSessionAsync(string platform, CancellationToken ct = default) =>
        SendJsonAsync<SessionStatus>(HttpMethod.Delete, $"/api/sessions/{Uri.EscapeDataString(platform)}", null, ct);

    public Task<SessionStatus> SetSessionEnabledAsync(string platform, bool enabled, CancellationToken ct = default) =>
        SendJsonAsync<SessionStatus>(new HttpMethod("PATCH"), $"/api/sessions/{Uri.EscapeDataString(platform)}", new { enabled }, ct);

    // === 引擎诊断、平台与更新 ===

    public Task<EngineDiagnostics> GetDiagnosticsAsync(CancellationToken ct = default) =>
        GetJsonAsync<EngineDiagnostics>("/api/engine/diagnostics", ct);

    public Task<GpuStatusInfo> GetGpuStatusAsync(bool refresh = false, CancellationToken ct = default) =>
        GetJsonAsync<GpuStatusInfo>($"/api/gpu/status?refresh={(refresh ? "true" : "false")}", ct);

    public Task<PlatformCatalogResponse> GetPlatformCatalogAsync(CancellationToken ct = default) =>
        GetJsonAsync<PlatformCatalogResponse>("/api/platforms", ct);

    public Task<UpdateCheckResponse> CheckUpdateAsync(CancellationToken ct = default) =>
        GetJsonAsync<UpdateCheckResponse>("/api/updates", ct);

    public Task<JsonElement> ShutdownBackendAsync(CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/system/shutdown", null, ct);

    // === 浏览器辅助捕获 ===

    public Task<JsonElement> OpenBrowserAssistAsync(string url, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/browser-assist/open", new { url }, ct);

    public Task<List<BrowserAssistCandidate>> GetBrowserAssistCandidatesAsync(CancellationToken ct = default) =>
        GetJsonAsync<List<BrowserAssistCandidate>>("/api/browser-assist/candidates", ct);

    public Task<ImagePost> CaptureBrowserAssistImagesAsync(CancellationToken ct = default) =>
        SendJsonAsync<ImagePost>(HttpMethod.Post, "/api/browser-assist/images", null, ct);

    // === Omni Download Rules 自定义规则系统 ===

    public Task<RulesOverviewResponse> GetRulesOverviewAsync(CancellationToken ct = default) =>
        GetJsonAsync<RulesOverviewResponse>("/api/rules/overview", ct);

    public Task<List<DownloadRuleDto>> GetRulesAsync(CancellationToken ct = default) =>
        GetJsonAsync<List<DownloadRuleDto>>("/api/rules", ct);

    public Task<RuleSaveResponse> SaveRuleAsync(DownloadRuleDto rule, CancellationToken ct = default) =>
        string.IsNullOrWhiteSpace(rule.Id)
            ? SendJsonAsync<RuleSaveResponse>(HttpMethod.Post, "/api/rules", rule, ct)
            : SendJsonAsync<RuleSaveResponse>(HttpMethod.Put, $"/api/rules/{Uri.EscapeDataString(rule.Id)}", rule, ct);

    public Task<RuleSaveResponse> DuplicateRuleAsync(string ruleId, CancellationToken ct = default) =>
        SendJsonAsync<RuleSaveResponse>(HttpMethod.Post, $"/api/rules/{Uri.EscapeDataString(ruleId)}/duplicate", null, ct);

    public Task<JsonElement> DeleteRuleAsync(string ruleId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Delete, $"/api/rules/{Uri.EscapeDataString(ruleId)}", null, ct);

    public Task<JsonElement> ReorderRulesAsync(List<string> orderedIds, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/rules/reorder", new { orderedIds }, ct);

    public Task<JsonElement> UpdateRuleSettingsAsync(RuleSettingsDto settings, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/rules/settings", settings, ct);

    public Task<RuleValidationResponse> ValidateRuleJsonAsync(string jsonText, CancellationToken ct = default) =>
        SendJsonAsync<RuleValidationResponse>(HttpMethod.Post, "/api/rules/validate", new { jsonText }, ct);

    public Task<RuleImportResponse> ImportRuleJsonAsync(string jsonText, CancellationToken ct = default) =>
        SendJsonAsync<RuleImportResponse>(HttpMethod.Post, "/api/rules/import", new { jsonText }, ct);

    public Task<string> ExportRulesBundleTextAsync(string ruleId = "", CancellationToken ct = default)
    {
        var path = string.IsNullOrWhiteSpace(ruleId) ? "/api/rules/export" : $"/api/rules/export?ruleId={Uri.EscapeDataString(ruleId)}";
        return _http.GetStringAsync(BuildRequestUri(path), ct);
    }

    public Task<RuleTestPreviewResponse> TestRulePreviewAsync(string url, string trigger = "manual", string ruleId = "", CancellationToken ct = default) =>
        SendJsonAsync<RuleTestPreviewResponse>(HttpMethod.Post, "/api/rules/test", new { url, trigger, ruleId }, ct);

    public Task<RuleExecuteResponse> ExecuteRulesAsync(string text, string trigger = "manual", string ruleMode = "auto", string ruleId = "", string clientSource = "winui", CancellationToken ct = default) =>
        SendJsonAsync<RuleExecuteResponse>(HttpMethod.Post, "/api/rules/execute", new { text, trigger, ruleMode, ruleId, clientSource }, ct);

    public Task<JsonElement> ApprovePendingRuleAsync(string confirmId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/rules/pending/{Uri.EscapeDataString(confirmId)}/approve", null, ct);

    public Task<JsonElement> DismissPendingRuleAsync(string confirmId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, $"/api/rules/pending/{Uri.EscapeDataString(confirmId)}/dismiss", null, ct);

    public Task<RuleAuditListResponse> GetRuleAuditLogsAsync(int limit = 100, CancellationToken ct = default) =>
        GetJsonAsync<RuleAuditListResponse>($"/api/rules/audit?limit={limit}", ct);

    public Task<JsonElement> ClearRuleAuditLogsAsync(CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/rules/audit/clear", null, ct);

    public Task<JsonElement> ClearRuleDedupIndexAsync(CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "/api/rules/dedup/clear", null, ct);

    /// <summary>
    /// 订阅 /api/events SSE 任务状态与进度实时推送流
    /// </summary>
    public async IAsyncEnumerable<JsonDocument> ListenEventsAsync([EnumeratorCancellation] CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, BuildRequestUri("api/events"));
        using var resp = await _sseHttp.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        await EnsureSuccessAsync(resp, ct).ConfigureAwait(false);

        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var reader = new StreamReader(stream, Encoding.UTF8);

        while (!ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            if (line is null) break;
            if (!line.StartsWith("data:", StringComparison.Ordinal)) continue;

            var payload = line[5..].Trim();
            if (string.IsNullOrEmpty(payload)) continue;

            JsonDocument? doc = null;
            try
            {
                doc = JsonDocument.Parse(payload);
            }
            catch
            {
                // 忽略偶发非 JSON 心跳数据
            }

            if (doc is not null)
            {
                yield return doc;
            }
        }
    }

    public void Dispose()
    {
        if (_ownsHttp)
        {
            _http.Dispose();
            _sseHttp.Dispose();
        }
    }
}
