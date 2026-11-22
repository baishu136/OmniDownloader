using System.Net;
using System.Text;
using System.Text.Json;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using Xunit;

namespace OmniDownloader.WinUI.Tests;

public sealed class DomainLogicAndApiTests
{
    [Fact]
    public void ExtractLinks_SmartMode_ExtractsAndDeduplicatesUrlsAndBv()
    {
        const string text = """
            【分享视频】https://www.bilibili.com/video/BV1xx411c7mD/，
            还有一条 https://www.youtube.com/watch?v=dQw4w9WgXcQ!
            重复BV号 BV1xx411c7mD 和新BV BV1GJ411x7h7；
            以及重复链接 https://www.youtube.com/watch?v=dQw4w9WgXcQ
            """;

        var extracted = OmniDomainLogic.ExtractLinks(text, manualLineMode: false, new[] { "BV1GJ411x7h7" });

        Assert.Equal(3, extracted.Count);
        Assert.Equal("https://www.bilibili.com/video/BV1xx411c7mD/", extracted[0]);
        Assert.Equal("https://www.youtube.com/watch?v=dQw4w9WgXcQ", extracted[1]);
        Assert.Equal("BV1xx411c7mD", extracted[2]);
    }

    [Fact]
    public void ExtractLinks_ManualMode_SplitsLinesAndTrimsPunctuation()
    {
        const string text = "https://example.com/a\n  https://example.com/b。  \nhttps://example.com/a";
        var extracted = OmniDomainLogic.ExtractLinks(text, manualLineMode: true);

        Assert.Equal(2, extracted.Count);
        Assert.Equal("https://example.com/a", extracted[0]);
        Assert.Equal("https://example.com/b", extracted[1]);
    }

    [Theory]
    [InlineData("BEST_AVAILABLE", "mp4", false, "BEST_AVAILABLE", 0, 0, "auto", "auto")]
    [InlineData("MAX_2160", "mkv", true, "MAX_HEIGHT", 2160, 0, "auto", "auto")]
    [InlineData("TARGET_4320", "mp4", false, "TARGET_TIER", 4320, 0, "auto", "auto")]
    [InlineData("FPS60", "mp4", false, "BEST_AVAILABLE", 0, 60, "auto", "auto")]
    [InlineData("COMPATIBLE", "mp4", false, "BEST_AVAILABLE", 0, 0, "avc", "auto")]
    [InlineData("HDR", "mp4", false, "BEST_AVAILABLE", 0, 0, "auto", "HDR")]
    [InlineData("SDR", "webm", false, "BEST_AVAILABLE", 0, 0, "auto", "SDR")]
    public void BuildQualityPolicy_MapsAllPresetsCorrectly(
        string policy,
        string container,
        bool allowLower,
        string expectedMode,
        int expectedMaxHeight,
        int expectedFps,
        string expectedCodec,
        string expectedDynamicRange)
    {
        var result = OmniDomainLogic.BuildQualityPolicy(policy, container, allowLower);

        Assert.Equal(expectedMode, result.Mode);
        Assert.Equal(expectedMaxHeight, result.MaxHeight);
        Assert.Equal(expectedFps, result.TargetFps);
        Assert.Equal(expectedCodec, result.CodecPreference);
        Assert.Equal(expectedDynamicRange, result.DynamicRangePreference);
        Assert.Equal(container, result.Container);
        Assert.Equal(allowLower, result.AllowLowerQuality);
    }

    [Fact]
    public void BuildQualityPolicy_BatchCollectionExactFormat_ConvertsToMaxHeight()
    {
        var fmt = new FormatOption { FormatId = "137", Width = 1920, Height = 1080 };
        var result = OmniDomainLogic.BuildQualityPolicy("EXACT_FORMAT", "mp4", true, isBatchCollection: true, selectedFormat: fmt);

        Assert.Equal("MAX_HEIGHT", result.Mode);
        Assert.Equal(1080, result.MaxHeight);
    }

    [Fact]
    public void FilterAndGroupTasks_GroupsMultiEpisodeCollectionsAndFiltersCorrectly()
    {
        var tasks = new List<DownloadTask>
        {
            new() { Id = "t1", Title = "单集已完成", Status = "COMPLETED", Progress = 100 },
            new() { Id = "t2", CollectionId = "col-a", CollectionTitle = "系列教程", EpisodeIndex = 2, Title = "P2", Status = "DOWNLOADING", Progress = 40 },
            new() { Id = "t3", CollectionId = "col-a", CollectionTitle = "系列教程", EpisodeIndex = 1, Title = "P1", Status = "COMPLETED", Progress = 100 },
            new() { Id = "t4", CollectionId = "col-b", CollectionTitle = "单条合集", EpisodeIndex = 1, Title = "唯一集", Status = "FAILED", Progress = 0 },
        };

        var allGrouped = OmniDomainLogic.FilterAndGroupTasks(tasks, "all");
        Assert.Equal(3, allGrouped.Count);
        Assert.False(allGrouped[0].IsCollection);
        Assert.True(allGrouped[1].IsCollection);
        Assert.Equal("系列教程", allGrouped[1].Title);
        Assert.Equal(2, allGrouped[1].Items.Count);
        Assert.Equal("P1", allGrouped[1].Items[0].Title); // 按 EpisodeIndex 排序
        Assert.Equal(70.0, allGrouped[1].OverallProgress, precision: 2);
        Assert.False(allGrouped[2].IsCollection); // 单条不折成合集卡

        var activeGrouped = OmniDomainLogic.FilterAndGroupTasks(tasks, "active");
        Assert.Single(activeGrouped);
        Assert.Equal("t2", activeGrouped[0].PrimaryItem.Id);

        var completedGrouped = OmniDomainLogic.FilterAndGroupTasks(tasks, "completed");
        Assert.Equal(2, completedGrouped.Count);
    }

    [Fact]
    public void LocalizationService_LoadsAllFourLanguagesAndFormatsPlaceholders()
    {
        var loc = new LocalizationService();
        Assert.Equal("首页", loc.T("首页"));

        loc.SetLanguage("en");
        Assert.Equal("Home", loc.T("首页"));
        Assert.Equal("Downloading 3", loc.T("下载中 %d", 3));
        Assert.Equal("Selected 2 / 5", loc.T("已选 %d / %d 张", 2, 5));

        loc.SetLanguage("ja");
        Assert.Equal("ホーム", loc.T("首页"));

        loc.SetLanguage("zh-TW");
        Assert.Equal("首頁", loc.T("首页"));
    }

    [Fact]
    public void LocalStateService_SavesAndReloadsStateCleanly()
    {
        var tempDir = Path.Combine(Path.GetTempPath(), "omni-winui-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var svc = new LocalStateService(tempDir);
            svc.Current.ServiceBaseUrl = "http://127.0.0.1:59001";
            svc.Current.Language = "en";
            svc.Current.BatchDraftLinks = new List<string> { "https://example.com/1", "BV1xx411c7mD" };
            svc.Save();

            var reloaded = new LocalStateService(tempDir);
            Assert.Equal("http://127.0.0.1:59001", reloaded.Current.ServiceBaseUrl);
            Assert.Equal("en", reloaded.Current.Language);
            Assert.Equal(2, reloaded.Current.BatchDraftLinks.Count);
        }
        finally
        {
            if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
        }
    }

    [Fact]
    public void FormatOption_DisplaySummary_ShowsCodecAndBitrateWhenPresent()
    {
        var hevc = new FormatOption
        {
            Width = 1920,
            Height = 1080,
            Ext = "mp4",
            Fps = 60,
            VCodec = "hev1.1.6.L150",
            VideoBitrateKbps = 4200
        };
        Assert.Equal("1920×1080 · MP4 · 60fps · HEVC · 4.2 Mbps", hevc.DisplaySummary);

        var av1 = new FormatOption
        {
            Width = 1920,
            Height = 1080,
            Ext = "mp4",
            VCodec = "av01.0.08M.08",
            ApproximateSize = "52 MB"
        };
        Assert.Equal("1920×1080 · MP4 · AV1 · 52 MB", av1.DisplaySummary);
    }

    [Fact]
    public async Task OmniApiClient_DeserializesAnalyzeVideoAndImagesAndParsesHttpError()
    {
        var handler = new StubHttpMessageHandler(req =>
        {
            var path = req.RequestUri!.PathAndQuery;
            if (path == "/api/media/analyze")
            {
                var body = req.Content!.ReadAsStringAsync().Result;
                if (body.Contains("images", StringComparison.Ordinal))
                {
                    return StubHttpMessageHandler.Json(HttpStatusCode.OK, """
                        {"kind":"images","data":{"platform":"小红书","url":"https://xhs.test/1","title":"图集演示","resources":[{"id":"img1","url":"https://img.test/1.jpg","previewUrl":"/api/images/preview/tok/0","width":1080,"height":1440,"kind":"IMAGE","original":true}]}}
                        """);
                }
                if (body.Contains("fail", StringComparison.Ordinal))
                {
                    return StubHttpMessageHandler.Json(HttpStatusCode.BadRequest, """{"detail":"需要登录Cookie","errorCode":"AUTH_REQUIRED"}""");
                }
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """
                    {"kind":"video","data":{"url":"https://b23.tv/BV1","title":"测试视频","siteName":"哔哩哔哩","durationText":"03:20","availableVideoFormats":[{"formatId":"80","resolutionLabel":"1080p","width":1920,"height":1080,"ext":"mp4","approximateSize":"42 MB"}]}}
                    """);
            }
            return StubHttpMessageHandler.Json(HttpStatusCode.NotFound, """{"detail":"not found"}""");
        });

        using var client = new OmniApiClient("http://127.0.0.1:58000", handler);

        var (kindVideo, video, imagesNull) = await client.AnalyzeMediaAsync("https://b23.tv/BV1");
        Assert.Equal("video", kindVideo);
        Assert.Null(imagesNull);
        Assert.NotNull(video);
        Assert.Equal("测试视频", video!.Title);
        Assert.Single(video.AvailableVideoFormats);

        var (kindImg, videoNull, images) = await client.AnalyzeMediaAsync("https://xhs.test/images");
        Assert.Equal("images", kindImg);
        Assert.Null(videoNull);
        Assert.NotNull(images);
        Assert.Single(images!.Resources);
        Assert.Equal("http://127.0.0.1:58000/api/images/preview/tok/0", client.BuildAbsoluteUrl(images.Resources[0].PreviewUrl));

        var ex = await Assert.ThrowsAsync<OmniApiException>(() => client.AnalyzeMediaAsync("https://test/fail"));
        Assert.Equal(400, ex.StatusCode);
        Assert.Equal("AUTH_REQUIRED", ex.ErrorCode);
        Assert.Equal("需要登录Cookie", ex.Message);
    }

    [Fact]
    public void BackendProcessManager_ChecksSemanticBackendVersionCompatibility()
    {
        Assert.False(BackendProcessManager.IsVersionAtLeast("1.7.51", BackendProcessManager.MinimumRequiredBackendVersion));
        Assert.False(BackendProcessManager.IsVersionAtLeast(null, BackendProcessManager.MinimumRequiredBackendVersion));
        Assert.True(BackendProcessManager.IsVersionAtLeast("1.7.59", BackendProcessManager.MinimumRequiredBackendVersion));
        Assert.True(BackendProcessManager.IsVersionAtLeast("v1.8.0", BackendProcessManager.MinimumRequiredBackendVersion));
    }

    [Fact]
    public void OmniDomainLogic_ResolvesMediaViewKindsAndZipImageEntriesCleanly()
    {
        var videoTask = new DownloadTask { DownloadType = "VIDEO_WITH_AUDIO", LocalFilePath = @"C:\v\clip.mp4" };
        var audioTask = new DownloadTask { DownloadType = "AUDIO_ONLY", LocalFilePath = @"C:\a\song.flac" };
        var imageTask = new DownloadTask { DownloadType = "IMAGE", LocalFilePath = @"C:\p\photo.png" };
        var coverTask = new DownloadTask { DownloadType = "COVER", LocalFilePath = @"C:\p\cover.jpg" };
        var gifAsMp4 = new DownloadTask { DownloadType = "GIF", LocalFilePath = @"C:\v\anim.mp4" };
        var zipTask = new DownloadTask { DownloadType = "IMAGE_ZIP", LocalFilePath = @"C:\p\gallery.zip" };

        Assert.Equal(MediaViewKind.Video, OmniDomainLogic.ResolveMediaViewKind(videoTask));
        Assert.Equal("播放", OmniDomainLogic.TaskViewActionText(videoTask));
        Assert.Equal(MediaViewKind.Audio, OmniDomainLogic.ResolveMediaViewKind(audioTask));
        Assert.Equal("播放", OmniDomainLogic.TaskViewActionText(audioTask));
        Assert.Equal(MediaViewKind.Image, OmniDomainLogic.ResolveMediaViewKind(imageTask));
        Assert.Equal("查看", OmniDomainLogic.TaskViewActionText(imageTask));
        Assert.Equal(MediaViewKind.Image, OmniDomainLogic.ResolveMediaViewKind(coverTask));
        Assert.Equal(MediaViewKind.Video, OmniDomainLogic.ResolveMediaViewKind(gifAsMp4));
        Assert.Equal(MediaViewKind.ImageArchive, OmniDomainLogic.ResolveMediaViewKind(zipTask));
        Assert.Equal("查看", OmniDomainLogic.TaskViewActionText(zipTask));

        var tempZip = Path.Combine(Path.GetTempPath(), $"omni-zip-{Guid.NewGuid():N}.zip");
        try
        {
            using (var zip = System.IO.Compression.ZipFile.Open(tempZip, System.IO.Compression.ZipArchiveMode.Create))
            {
                foreach (var name in new[] { "10.jpg", "2.png", "readme.txt", "nested/1.webp" })
                {
                    var entry = zip.CreateEntry(name);
                    using var s = entry.Open();
                    s.WriteByte(1);
                }
            }

            var images = OmniDomainLogic.ListZipImageEntries(tempZip);
            Assert.Equal(new[] { "2.png", "10.jpg", "nested/1.webp" }, images);
        }
        finally
        {
            if (File.Exists(tempZip)) File.Delete(tempZip);
        }
    }

    [Fact]
    public async Task OmniApiClient_HandlesBrowserLoginStatusAndPreserves404Status()
    {
        var handler = new StubHttpMessageHandler(req =>
        {
            var path = req.RequestUri!.AbsolutePath;
            if (path == "/api/sessions/YouTube/login" && req.Method == HttpMethod.Get)
            {
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """
                    {"platform":"YouTube","phase":"WAITING_LOGIN","running":true,"cookieCount":0,"capturedAt":0}
                    """);
            }
            if (path == "/api/sessions/YouTube/login" && req.Method == HttpMethod.Post)
            {
                return StubHttpMessageHandler.Json(HttpStatusCode.NotFound, """{"detail":"Not Found"}""");
            }
            if (path == "/api/tasks/t-zip/zip-entries")
            {
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """
                    {"taskId":"t-zip","entries":["1.jpg","2.png"]}
                    """);
            }
            return StubHttpMessageHandler.Json(HttpStatusCode.NotFound, """{"detail":"Not Found"}""");
        });

        using var client = new OmniApiClient("http://127.0.0.1:58000", handler);

        var st = await client.GetBrowserLoginStatusAsync("YouTube");
        Assert.Equal("WAITING_LOGIN", st.Phase);
        Assert.True(st.Running);

        var zipRes = await client.GetTaskZipEntriesAsync("t-zip");
        Assert.Equal(new[] { "1.jpg", "2.png" }, zipRes.Entries);

        var notFound = await Assert.ThrowsAsync<OmniApiException>(() => client.OpenBrowserLoginAsync("YouTube"));
        Assert.Equal(404, notFound.StatusCode);
    }

    [Fact]
    public async Task BackendProcessManager_UsesSettingsVersionWithoutCallingPlatformsCatalog()
    {
        var platformCalls = 0;
        var handler = new StubHttpMessageHandler(req =>
        {
            var path = req.RequestUri!.AbsolutePath;
            if (path == "/api/settings")
            {
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """
                    {"downloadDir":"C:\\Downloads","theme":"dark","version":"1.7.60"}
                    """);
            }
            if (path == "/api/platforms")
            {
                Interlocked.Increment(ref platformCalls);
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """{"version":"1.7.60","extractors":[]}""");
            }
            return StubHttpMessageHandler.Json(HttpStatusCode.NotFound, "{}");
        });

        using var client = new OmniApiClient("http://localhost:58000", handler);
        Assert.Equal("http://127.0.0.1:58000", client.BaseUrl);

        using var mgr = new BackendProcessManager();
        var ok = await mgr.EnsureConnectedAsync(client, "http://localhost:58000", autoStartIfOffline: false);

        Assert.True(ok);
        Assert.Equal("1.7.60", mgr.ConnectedVersion);
        Assert.False(mgr.IsOutdatedBackend);
        Assert.Equal(0, platformCalls);

        client.UpdateBaseUrl("http://127.0.0.1:58005");
        Assert.Equal("http://127.0.0.1:58005", client.BaseUrl);
    }

    [Fact]
    public void LocalStateService_SaveDeferredAndFlushPersistsCleanly()
    {
        var tempDir = Path.Combine(Path.GetTempPath(), "omni-winui-def-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var svc = new LocalStateService(tempDir);
            svc.Current.BatchRawInputDraft = "https://example.com/deferred";
            svc.SaveDeferred(1000);
            svc.Flush();

            using var reloaded = new LocalStateService(tempDir);
            Assert.Equal("https://example.com/deferred", reloaded.Current.BatchRawInputDraft);
        }
        finally
        {
            if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
        }
    }

    private sealed class StubHttpMessageHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request));

        public static HttpResponseMessage Json(HttpStatusCode code, string json) =>
            new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
    }
}
