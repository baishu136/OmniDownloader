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
        Assert.False(BackendProcessManager.IsVersionAtLeast("1.7.60", BackendProcessManager.MinimumRequiredBackendVersion));
        Assert.False(BackendProcessManager.IsVersionAtLeast(null, BackendProcessManager.MinimumRequiredBackendVersion));
        Assert.True(BackendProcessManager.IsVersionAtLeast("1.7.63", BackendProcessManager.MinimumRequiredBackendVersion));
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
                    {"downloadDir":"C:\\Downloads","theme":"dark","version":"1.7.63"}
                    """);
            }
            if (path == "/api/platforms")
            {
                Interlocked.Increment(ref platformCalls);
                return StubHttpMessageHandler.Json(HttpStatusCode.OK, """{"version":"1.7.63","extractors":[]}""");
            }
            return StubHttpMessageHandler.Json(HttpStatusCode.NotFound, "{}");
        });

        using var client = new OmniApiClient("http://localhost:58000", handler);
        Assert.Equal("http://127.0.0.1:58000", client.BaseUrl);

        using var mgr = new BackendProcessManager();
        var ok = await mgr.EnsureConnectedAsync(client, "http://localhost:58000", autoStartIfOffline: false);

        Assert.True(ok);
        Assert.Equal("1.7.63", mgr.ConnectedVersion);
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

    [Fact]
    public void FormatOption_And_GridSizing_ExposeTileResolutionFormatAndSize()
    {
        var h264 = new FormatOption
        {
            FormatId = "1",
            Width = 480,
            Height = 654,
            Ext = "mp4",
            VCodec = "avc1.64001F",
            VideoBitrateKbps = 0,
            ApproximateSize = ""
        };
        Assert.Equal("480×654", h264.TileResolutionText);
        Assert.Equal("480p", h264.TileTierBadgeText);
        Assert.Equal("MP4 · H.264", h264.TileFormatText);
        Assert.Equal("自动体积", h264.TileSizeText);

        var hdr4k = new FormatOption
        {
            FormatId = "2",
            Width = 3840,
            Height = 2160,
            Fps = 60,
            DynamicRange = "HDR",
            Ext = "webm",
            VCodec = "vp09.02.10",
            ApproximateSize = "128.4 MB"
        };
        Assert.Equal("3840×2160", hdr4k.TileResolutionText);
        Assert.Equal("4K", hdr4k.TileTierBadgeText);
        Assert.Equal("WEBM · VP9 · 60fps · HDR", hdr4k.TileFormatText);
        Assert.Equal("128.4 MB", hdr4k.TileSizeText);

        Assert.Equal(4, OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(720).Columns);
        Assert.Equal(3, OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(560).Columns);
        Assert.Equal(2, OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(380).Columns);
        Assert.Equal(1, OmniDomainLogic.ComputeFormatGridColumnsAndTileWidth(280).Columns);
    }

    [Fact]
    public void ThemeAccentHelper_NormalizesComputesPaletteAndSelectsContrastText()
    {
        Assert.Equal("system", ThemeAccentHelper.NormalizeSetting(null));
        Assert.Equal("system", ThemeAccentHelper.NormalizeSetting("auto"));
        Assert.Equal("system", ThemeAccentHelper.NormalizeSetting("invalid-hex"));
        Assert.Equal("#3B5BDB", ThemeAccentHelper.NormalizeSetting("3b5bdb"));
        Assert.Equal("#FF0088", ThemeAccentHelper.NormalizeSetting("#AAFF0088"));

        Assert.True(ThemeAccentHelper.TryParseHexRgb("#FACC15", out var brightYellow));
        Assert.True(ThemeAccentHelper.ShouldUseDarkForeground(brightYellow));

        Assert.True(ThemeAccentHelper.TryParseHexRgb("#1E293B", out var darkSlate));
        Assert.False(ThemeAccentHelper.ShouldUseDarkForeground(darkSlate));

        var palette = ThemeAccentHelper.ComputePalette(new RgbColor(59, 91, 219));
        Assert.True(palette.Light2.R > palette.Base.R);
        Assert.True(palette.Dark2.B < palette.Base.B);
    }

    [Fact]
    public void ComponentCatalog_LocksRequiredAndSupportsOptionalPlusCustomPersistence()
    {
        var tempDir = Path.Combine(Path.GetTempPath(), "omni-comp-" + Guid.NewGuid().ToString("N"));
        try
        {
            using (var svc = new LocalStateService(tempDir))
            {
                svc.Current.AccentColor = "#8B5CF6";
                svc.Current.DisabledOptionalComponentIds.Add("deno-runtime");
                svc.Current.CustomComponents.Add(new CustomComponentEntry
                {
                    Id = "custom-aria2",
                    Name = "Aria2 下载扩展",
                    Description = "高速多连接分片调度",
                    Kind = "service",
                    EntryPoint = "http://127.0.0.1:6800",
                    Enabled = true
                });
                svc.Save();
            }

            using var reloaded = new LocalStateService(tempDir);
            Assert.Equal("#8B5CF6", reloaded.Current.AccentColor);
            var diag = new EngineDiagnostics
            {
                Python = "3.12.11",
                YtDlp = "2026.10.26",
                Ejs = "0.1.0",
                FfmpegAvailable = true,
                DenoAvailable = false
            };

            var items = ComponentCatalog.BuildItems(
                reloaded.Current,
                diag,
                isBackendConnected: true,
                serviceBaseUrl: "http://127.0.0.1:58000",
                translate: s => s,
                translateFormat: (tpl, args) => tpl.Replace("%s", args.FirstOrDefault()?.ToString() ?? string.Empty));

            var core = items.Where(i => i.IsRequired).ToList();
            Assert.Equal(4, core.Count);
            Assert.All(core, item =>
            {
                Assert.True(item.IsEnabled);
                Assert.False(item.CanToggle);
            });

            var deno = items.First(i => i.Id == "deno-runtime");
            Assert.False(deno.IsRequired);
            Assert.True(deno.CanToggle);
            Assert.False(deno.IsEnabled);

            var custom = items.First(i => i.Id == "custom-aria2");
            Assert.True(custom.IsCustom);
            Assert.True(custom.CanToggle);
            Assert.True(custom.IsEnabled);
            Assert.Contains("Aria2", custom.DisplayName);
        }
        finally
        {
            if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
        }
    }

    [Fact]
    public void OnboardingAndClosePreferencesPersistAcrossRestarts()
    {
        var tempDir = Path.Combine(Path.GetTempPath(), "omni-onboard-" + Guid.NewGuid().ToString("N"));
        try
        {
            using (var svc = new LocalStateService(tempDir))
            {
                Assert.True(svc.Current.ShowRecentTasksOnHome);
                Assert.True(svc.Current.EnableWebService);
                Assert.False(svc.Current.HasCompletedOnboarding);
                Assert.True(svc.Current.FollowDefaultDownloadDir);

                svc.Current.ShowRecentTasksOnHome = false;
                svc.Current.EnableWebService = false;
                svc.Current.HasCompletedOnboarding = true;
                svc.Current.FollowDefaultDownloadDir = false;
                svc.Current.VideoDownloadDir = @"C:\Media\Videos";
                svc.Current.ImageDownloadDir = @"C:\Media\Images";
                svc.Current.AudioDownloadDir = @"C:\Media\Audio";
                svc.Current.AlwaysRememberCloseBehavior = true;
                svc.Current.SavedCloseBehavior = "tray";
                svc.Save();
            }

            using var reloaded = new LocalStateService(tempDir);
            Assert.False(reloaded.Current.ShowRecentTasksOnHome);
            Assert.False(reloaded.Current.EnableWebService);
            Assert.True(reloaded.Current.HasCompletedOnboarding);
            Assert.False(reloaded.Current.FollowDefaultDownloadDir);
            Assert.Equal(@"C:\Media\Videos", reloaded.Current.VideoDownloadDir);
            Assert.Equal(@"C:\Media\Images", reloaded.Current.ImageDownloadDir);
            Assert.Equal(@"C:\Media\Audio", reloaded.Current.AudioDownloadDir);
            Assert.True(reloaded.Current.AlwaysRememberCloseBehavior);
            Assert.Equal("tray", reloaded.Current.SavedCloseBehavior);
        }
        finally
        {
            if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
        }
    }

    [Theory]
    [InlineData("zh-CN", true, "显示窗口", "拉起网页版", "退出程序")]
    [InlineData("en", true, "Show window", "Launch web companion", "Exit")]
    [InlineData("en", false, "Show window", null, "Exit")]
    [InlineData("ja", false, "ウィンドウを表示", null, "終了")]
    public void TrayMenuCatalog_HidesLaunchWebWhenWebServiceDisabled(
        string language,
        bool enableWebService,
        string expectedShow,
        string? expectedWeb,
        string expectedExit)
    {
        var loc = new LocalizationService();
        loc.SetLanguage(language);
        var items = TrayMenuCatalog.BuildMenuItems(enableWebService, key => loc.T(key));

        Assert.Equal(expectedShow, items.First(i => i.Action == TrayMenuAction.ShowWindow).Label);
        Assert.Equal(expectedExit, items.First(i => i.Action == TrayMenuAction.ExitApp).Label);

        var webItem = items.FirstOrDefault(i => i.Action == TrayMenuAction.LaunchWeb);
        if (enableWebService)
        {
            Assert.NotNull(webItem);
            Assert.Equal(expectedWeb, webItem!.Label);
        }
        else
        {
            Assert.Null(webItem);
        }
    }

    [Fact]
    public void LocalizationService_TranslatesAllWinUiLabelsAndPlatformsCleanlyInEnglish()
    {
        var loc = new LocalizationService();
        loc.SetLanguage("en");
        var keys = new[]
        {
            "外观与语言", "功能设置", "支持平台目录", "网络代理设置 (Proxy)",
            "本地引擎连接配置", "应用与引擎更新检查",
            "平台 Cookie 会话隔离仓库 (Windows DPAPI 加密)",
            "登录哔哩哔哩 (Cookie / SESSDATA)",
            "解锁 1080P60 / 4K 超清画质与大会员片源",
            "就绪", "可用", "缺失", "浅色", "深色", "跟随系统",
            "哔哩哔哩", "抖音", "快手", "小红书", "微博", "西瓜视频", "优酷", "爱奇艺", "虎牙", "小黑盒",
            "在首页中展示最近任务", "是否关闭应用？", "始终保存选择", "显示窗口", "拉起网页版", "退出程序"
        };

        foreach (var k in keys)
        {
            var translated = loc.T(k);
            Assert.False(string.IsNullOrWhiteSpace(translated), $"Missing translation for {k}");
            Assert.DoesNotMatch("[\u4e00-\u9fff]", translated);
        }
    }

    [Fact]
    public void ThemeAppearanceLogic_ResolvesPreLoadOsThemeAndGuardsExplicitThemes()
    {
        // 首帧尚未 Loaded 时，auto 应直接使用系统真实深浅色，避免先渲染浅色再二跳变黑造成背景闪烁
        Assert.True(ThemeAppearanceLogic.ResolveEffectiveDark("auto", osIsDark: true, rootIsLoaded: false, rootActualIsDark: false));
        Assert.False(ThemeAppearanceLogic.ResolveEffectiveDark("auto", osIsDark: false, rootIsLoaded: false, rootActualIsDark: true));

        // 已 Loaded 后跟随 ActualTheme；显式指定 light/dark 时不受 ActualTheme 延迟与系统主题干扰
        Assert.True(ThemeAppearanceLogic.ResolveEffectiveDark("auto", osIsDark: false, rootIsLoaded: true, rootActualIsDark: true));
        Assert.True(ThemeAppearanceLogic.ResolveEffectiveDark("dark", osIsDark: false, rootIsLoaded: false, rootActualIsDark: false));
        Assert.False(ThemeAppearanceLogic.ResolveEffectiveDark("light", osIsDark: true, rootIsLoaded: true, rootActualIsDark: true));

        // 仅在 auto 模式响应系统 ActualThemeChanged
        Assert.True(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("auto"));
        Assert.True(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged(null));
        Assert.False(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("light"));
        Assert.False(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("dark"));
    }

    [Fact]
    public void FileTransferPathHelper_ResolvesDeduplicatesAndBuildsValidDropFilesPayload()
    {
        var tempRoot = Path.Combine(Path.GetTempPath(), "omni_ft_" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(tempRoot);
        try
        {
            var cnPath = Path.Combine(tempRoot, "中文测试视频 [4K] #1 & (demo).mp4");
            var spPath = Path.Combine(tempRoot, "audio track % special!.flac");
            var missingPath = Path.Combine(tempRoot, "not_exists.mkv");
            File.WriteAllBytes(cnPath, new byte[] { 1, 2, 3, 4 });
            File.WriteAllBytes(spPath, new byte[] { 5, 6, 7 });

            var tasks = new[]
            {
                new DownloadTask { Id = "1", Status = "COMPLETED", LocalFilePath = cnPath },
                new DownloadTask { Id = "2", Status = "COMPLETED", LocalFilePath = cnPath.ToUpperInvariant() },
                new DownloadTask { Id = "3", Status = "COMPLETED", LocalFilePath = missingPath },
                new DownloadTask { Id = "4", Status = "COMPLETED", LocalFilePath = spPath }
            };

            var resolved = FileTransferPathHelper.ResolveExistingLocalFiles(tasks);
            Assert.Equal(2, resolved.ValidPaths.Count);
            Assert.Equal(1, resolved.MissingCount);
            Assert.Equal(1, resolved.DuplicateSkippedCount);

            var payload = FileTransferPathHelper.BuildDropFilesPayload(resolved.ValidPaths);
            Assert.Equal(20u, BitConverter.ToUInt32(payload, 0)); // pFiles = 20
            Assert.Equal(1u, BitConverter.ToUInt32(payload, 16)); // fWide = 1
            var expectedLen = 20 + (resolved.ValidPaths[0].Length + 1 + resolved.ValidPaths[1].Length + 1 + 1) * 2;
            Assert.Equal(expectedLen, payload.Length);
            Assert.Equal(0, payload[^1]);
            Assert.Equal(0, payload[^2]);

            var textLines = FileTransferPathHelper.FormatPathLinesForClipboard(resolved.ValidPaths);
            Assert.Contains("中文测试视频 [4K] #1 & (demo).mp4", textLines);
            Assert.Contains("audio track % special!.flac", textLines);
        }
        finally
        {
            try { Directory.Delete(tempRoot, recursive: true); } catch { }
        }
    }

    [Fact]
    public void BackdropStyleLogic_NormalizesAndResolvesOsFallbacksCleanly()
    {
        Assert.Equal(BackdropStyleLogic.MicaAltKey, BackdropStyleLogic.NormalizeStyle("MicaAlt"));
        Assert.Equal(BackdropStyleLogic.AeroKey, BackdropStyleLogic.NormalizeStyle("glass"));
        Assert.Equal(BackdropStyleLogic.SolidKey, BackdropStyleLogic.NormalizeStyle("NONE"));
        Assert.Equal(BackdropStyleLogic.AutoKey, BackdropStyleLogic.NormalizeStyle("unknown"));

        Assert.Equal(EffectiveBackdropKind.MicaAlt, BackdropStyleLogic.ResolveEffectiveKind("mica_alt", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.WallpaperMicaAlt, BackdropStyleLogic.ResolveEffectiveKind("mica_alt", isDark: true, pureBlackDark: false, micaSupported: false, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.WallpaperMica, BackdropStyleLogic.ResolveEffectiveKind("mica", isDark: false, pureBlackDark: false, micaSupported: false, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Aero, BackdropStyleLogic.ResolveEffectiveKind("aero", isDark: true, pureBlackDark: false, micaSupported: false, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Solid, BackdropStyleLogic.ResolveEffectiveKind("acrylic", isDark: true, pureBlackDark: true, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Solid, BackdropStyleLogic.ResolveEffectiveKind("solid", isDark: false, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
    }

    [Fact]
    public void FormatOption_GetPremiumBadges_Detects4K8KHdrHiResAndFps()
    {
        var ultra = new FormatOption
        {
            FormatId = "127-1",
            Width = 7680,
            Height = 4320,
            DynamicRange = "HDR10+",
            Fps = 120,
            Note = "Hi-Res Master"
        };
        var ultraBadges = ultra.GetPremiumBadges();
        Assert.Contains("8K", ultraBadges);
        Assert.Contains("HDR10+", ultraBadges);
        Assert.Contains("Hi-Res", ultraBadges);

        var fourK = new FormatOption { FormatId = "120-1", Width = 3840, Height = 2160, DynamicRange = "SDR", Fps = 60 };
        Assert.Equal(new[] { "4K" }, fourK.GetPremiumBadges());

        var std1080 = new FormatOption { FormatId = "80-1", Width = 1920, Height = 1080, DynamicRange = "SDR", Fps = 60 };
        Assert.Empty(std1080.GetPremiumBadges());
    }

    [Fact]
    public async Task OmniApiClient_ClearBatchesAndClientSourceSerializeCorrectly()
    {
        string? capturedMethod = null;
        string? capturedPath = null;
        string? capturedBody = null;
        var handler = new StubHttpMessageHandler(req =>
        {
            capturedMethod = req.Method.Method;
            capturedPath = req.RequestUri?.PathAndQuery;
            capturedBody = req.Content?.ReadAsStringAsync().Result;
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            {
                Content = new StringContent("""{"status":"success","cleared":2}""", System.Text.Encoding.UTF8, "application/json")
            };
        });

        using var client = new OmniApiClient("http://127.0.0.1:58000", handler);
        await client.SubmitDownloadTasksAsync(new[] { new CreateTaskItem { Url = "https://example.com/v", Title = "Demo" } });
        Assert.Equal("/api/download", capturedPath);
        Assert.Contains("\"clientSource\":\"winui\"", capturedBody);

        await client.ClearBatchesAsync();
        Assert.Equal("POST", capturedMethod);
        Assert.Equal("/api/batches/clear?clearAll=true", capturedPath);
    }

    private sealed class StubHttpMessageHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request));

        public static HttpResponseMessage Json(HttpStatusCode code, string json) =>
            new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
    }
}
