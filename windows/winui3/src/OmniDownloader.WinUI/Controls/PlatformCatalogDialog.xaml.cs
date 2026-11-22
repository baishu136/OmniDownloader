using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Services;
using Windows.UI;

namespace OmniDownloader.WinUI.Controls;

public sealed class FeaturedPlatformChip
{
    public string Name { get; }
    public string Keywords { get; }
    public SolidColorBrush DotBrush { get; }

    public FeaturedPlatformChip(string name, string keywords, Color dotColor)
    {
        Name = name;
        Keywords = keywords;
        DotBrush = new SolidColorBrush(dotColor);
    }
}

/// <summary>
/// 统一支持平台目录弹窗：集成 22 大深度适配平台彩色标识 + yt-dlp 全量站点解析器实时检索
/// </summary>
public sealed partial class PlatformCatalogDialog : ContentDialog
{
    private static readonly FeaturedPlatformChip[] FeaturedPlatforms =
    {
        new("哔哩哔哩", "bilibili b23", Color.FromArgb(255, 251, 114, 153)),
        new("抖音", "douyin", Color.FromArgb(255, 110, 116, 128)),
        new("快手", "kuaishou", Color.FromArgb(255, 255, 80, 0)),
        new("小红书", "xiaohongshu xhs", Color.FromArgb(255, 255, 36, 66)),
        new("微博", "weibo", Color.FromArgb(255, 227, 75, 83)),
        new("YouTube", "youtube", Color.FromArgb(255, 255, 0, 0)),
        new("西瓜视频", "ixigua xigua", Color.FromArgb(255, 59, 91, 219)),
        new("TikTok", "tiktok", Color.FromArgb(255, 0, 194, 214)),
        new("X (Twitter)", "twitter x", Color.FromArgb(255, 29, 161, 242)),
        new("Instagram", "instagram", Color.FromArgb(255, 225, 48, 108)),
        new("Facebook", "facebook", Color.FromArgb(255, 24, 119, 242)),
        new("Reddit", "reddit", Color.FromArgb(255, 255, 69, 0)),
        new("Twitch", "twitch", Color.FromArgb(255, 145, 70, 255)),
        new("优酷", "youku", Color.FromArgb(255, 34, 139, 230)),
        new("爱奇艺", "iqiyi", Color.FromArgb(255, 0, 190, 6)),
        new("AcFun", "acfun", Color.FromArgb(255, 253, 76, 91)),
        new("虎牙", "huya", Color.FromArgb(255, 255, 162, 0)),
        new("TED", "ted", Color.FromArgb(255, 230, 43, 30)),
        new("Internet Archive", "archive", Color.FromArgb(255, 100, 116, 139)),
        new("Pinterest", "pinterest", Color.FromArgb(255, 230, 0, 35)),
        new("Pixiv", "pixiv", Color.FromArgb(255, 0, 150, 250)),
        new("小黑盒", "xiaoheihe heybox", Color.FromArgb(255, 100, 116, 139)),
    };

    private readonly AppServices _services;
    private List<string> _allExtractors = new();

    public PlatformCatalogDialog(AppServices services)
    {
        _services = services;
        InitializeComponent();
        FeaturedHintText.Text = _services.Loc.SupportedPlatformsHint + "。部分高画质/会员或风控资源需在「设置 → 账号」完成登录后解析。";
        FeaturedPlatformsControl.ItemsSource = FeaturedPlatforms;
        _ = LoadCatalogAsync();
    }

    private async Task LoadCatalogAsync()
    {
        try
        {
            var data = await _services.ApiClient.GetPlatformCatalogAsync();
            _allExtractors = data.Extractors;
            LoadingRing.IsActive = false;
            ApplyFilter();
        }
        catch (Exception ex)
        {
            LoadingRing.IsActive = false;
            SummaryText.Text = ex.Message;
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void ApplyFilter()
    {
        var query = SearchBox.Text.Trim();
        var matchingFeatured = string.IsNullOrEmpty(query)
            ? FeaturedPlatforms
            : FeaturedPlatforms.Where(p =>
                p.Name.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                p.Keywords.Contains(query, StringComparison.OrdinalIgnoreCase)).ToArray();

        FeaturedSection.Visibility = matchingFeatured.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        FeaturedPlatformsControl.ItemsSource = matchingFeatured;
        FeaturedHeaderText.Text = string.IsNullOrEmpty(query)
            ? $"主流直连与深度适配平台 ({FeaturedPlatforms.Length})"
            : $"匹配主流平台 ({matchingFeatured.Length})";

        var filtered = string.IsNullOrEmpty(query)
            ? _allExtractors
            : _allExtractors.Where(x => x.Contains(query, StringComparison.OrdinalIgnoreCase)).ToList();

        SummaryText.Text = filtered.Count > 300
            ? $"共匹配 {filtered.Count} 个解析器，当前展示前 300 项（输入关键词缩小范围）"
            : $"共 {filtered.Count} 个解析器";

        ExtractorsListView.ItemsSource = filtered.Take(300).ToList();
    }
}
