using System.Text.Json;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using Xunit;

namespace OmniDownloader.WinUI.Tests;

public sealed class BackdropAndRulesTests
{
    [Fact]
    public void BackdropStyleLogic_PreservesNativeOriginalDefaultAndDistinctStyles()
    {
        // "auto" / "original" 均映射到保留原参数的 NativeOriginal 默认材质
        Assert.Equal(EffectiveBackdropKind.NativeOriginal,
            BackdropStyleLogic.ResolveEffectiveKind("auto", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.NativeOriginal,
            BackdropStyleLogic.ResolveEffectiveKind("auto", isDark: false, pureBlackDark: false, micaSupported: false, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.NativeOriginal,
            BackdropStyleLogic.ResolveEffectiveKind("original", isDark: false, pureBlackDark: false, micaSupported: false, acrylicSupported: true));

        // 不同背景材质互不冲突（各自独立枚举）
        Assert.Equal(EffectiveBackdropKind.MicaBase,
            BackdropStyleLogic.ResolveEffectiveKind("mica", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.MicaAlt,
            BackdropStyleLogic.ResolveEffectiveKind("mica_alt", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Acrylic,
            BackdropStyleLogic.ResolveEffectiveKind("acrylic", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Aero,
            BackdropStyleLogic.ResolveEffectiveKind("aero", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
        Assert.Equal(EffectiveBackdropKind.Solid,
            BackdropStyleLogic.ResolveEffectiveKind("solid", isDark: true, pureBlackDark: false, micaSupported: true, acrylicSupported: true));
    }

    [Fact]
    public void ThemeAppearanceLogic_HandlesLightDarkAndAutoTransitionsWithoutStaleState()
    {
        Assert.False(ThemeAppearanceLogic.ResolveEffectiveDark("light", osIsDark: true, rootIsLoaded: true, rootActualIsDark: true));
        Assert.True(ThemeAppearanceLogic.ResolveEffectiveDark("dark", osIsDark: false, rootIsLoaded: true, rootActualIsDark: false));
        Assert.False(ThemeAppearanceLogic.ResolveEffectiveDark("auto", osIsDark: false, rootIsLoaded: false, rootActualIsDark: true));
        Assert.True(ThemeAppearanceLogic.ResolveEffectiveDark("auto", osIsDark: false, rootIsLoaded: true, rootActualIsDark: true));

        Assert.False(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("light"));
        Assert.False(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("dark"));
        Assert.True(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged("auto"));
        Assert.True(ThemeAppearanceLogic.ShouldReapplyOnActualThemeChanged(null));
    }

    [Fact]
    public void TrayMenuCatalog_IncludesClipboardToggleOnlyWhenActive()
    {
        var offMenu = TrayMenuCatalog.BuildMenuItems(enableWebService: true);
        Assert.DoesNotContain(offMenu, x => x.Action == TrayMenuAction.ToggleClipboardWatch);

        var activeMenu = TrayMenuCatalog.BuildMenuItems(enableWebService: false, clipboardWatchActive: true, clipboardWatchPaused: false);
        Assert.Contains(activeMenu, x => x.Action == TrayMenuAction.ToggleClipboardWatch && x.Label.Contains("暂停"));

        var pausedMenu = TrayMenuCatalog.BuildMenuItems(enableWebService: false, clipboardWatchActive: true, clipboardWatchPaused: true);
        Assert.Contains(pausedMenu, x => x.Action == TrayMenuAction.ToggleClipboardWatch && x.Label.Contains("恢复"));
    }

    [Fact]
    public void DownloadRuleDto_And_BatchInputRequest_SerializeSchemaCorrectly()
    {
        var rule = new DownloadRuleDto
        {
            Id = "rule_demo",
            Name = "演示规则",
            Priority = 15,
            Match = new RuleMatchConfigDto
            {
                Platforms = new List<string> { "哔哩哔哩" },
                Domains = new List<string> { "bilibili.com" }
            },
            Execution = new RuleExecutionConfigDto
            {
                Action = "auto_download",
                MaxTasks = 20
            }
        };

        var cloned = rule.Clone();
        cloned.Name = "副本";
        Assert.Equal("演示规则", rule.Name);

        var json = rule.ToFormattedJson();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("rule_demo", doc.RootElement.GetProperty("id").GetString());
        Assert.Equal("auto_download", doc.RootElement.GetProperty("execution").GetProperty("action").GetString());

        var batchReq = new BatchInputRequest
        {
            Text = "https://www.bilibili.com/video/BV1xx",
            RuleMode = "specific",
            RuleId = "rule_demo"
        };
        var batchJson = JsonSerializer.Serialize(batchReq);
        Assert.Contains("\"ruleMode\":\"specific\"", batchJson);
        Assert.Contains("\"ruleId\":\"rule_demo\"", batchJson);
    }

    [Fact]
    public void LocalizationService_HasRulesNavTranslationsAcrossLanguages()
    {
        var loc = LocalizationService.Instance;
        loc.SetLanguage("en");
        Assert.False(string.IsNullOrWhiteSpace(loc.NavRules));
        Assert.NotEqual("下载规则", loc.NavRules);
        loc.SetLanguage("zh-CN");
        Assert.Equal("下载规则", loc.NavRules);
    }
}
