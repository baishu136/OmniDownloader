using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using Xunit;

namespace OmniDownloader.WinUI.Tests;

public sealed class DownloadLabelLocalizationTests
{
    [Theory]
    [InlineData("zh-CN", "视频", "纯画面", "音频", "GIF")]
    [InlineData("zh-TW", "視頻", "純畫面", "音訊", "GIF")]
    [InlineData("en", "Video", "Video only", "Audio", "GIF")]
    [InlineData("ja", "動画", "映像のみ", "音声", "GIF")]
    public void TaskLabelsUseTheSelectedLanguage(string language, string video, string mute, string audio, string gif)
    {
        var localization = new LocalizationService();
        localization.SetLanguage(language);
        Assert.Equal(video, localization.T(OmniDomainLogic.TypeLabelKey("VIDEO_WITH_AUDIO")));
        Assert.Equal(mute, localization.T(OmniDomainLogic.TypeLabelKey("VIDEO_ONLY")));
        Assert.Equal(audio, localization.T(OmniDomainLogic.TypeLabelKey("AUDIO_ONLY")));
        Assert.Equal(gif, localization.T(OmniDomainLogic.TypeLabelKey("GIF")));
    }
}
