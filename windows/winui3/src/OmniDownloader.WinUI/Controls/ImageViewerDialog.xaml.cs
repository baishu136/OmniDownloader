using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;
using Windows.System;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 原生图文大图查看弹窗，支持键盘左右方向键切换与独立图片加载
/// </summary>
public sealed partial class ImageViewerDialog : ContentDialog
{
    private readonly AppServices _services;
    private readonly ImagePost _post;
    private int _index;

    public ImageViewerDialog(AppServices services, ImagePost post, int startIndex)
    {
        _services = services;
        _post = post;
        _index = Math.Clamp(startIndex, 0, Math.Max(0, post.Resources.Count - 1));
        InitializeComponent();
        Title = string.IsNullOrWhiteSpace(post.Title) ? _services.Loc.T("图片预览") : post.Title;
        _ = LoadCurrentAsync();
    }

    private async Task LoadCurrentAsync()
    {
        if (_post.Resources.Count == 0) return;

        var res = _post.Resources[_index];
        CounterText.Text = $"{_index + 1} / {_post.Resources.Count}"
            + (res.Width > 0 && res.Height > 0 ? $" · {res.Width}×{res.Height}" : string.Empty);

        PrevBtn.IsEnabled = _post.Resources.Count > 1;
        NextBtn.IsEnabled = _post.Resources.Count > 1;

        LoadingRing.IsActive = true;
        FailedText.Visibility = Visibility.Collapsed;
        PreviewImage.Source = null;

        var url = _services.ApiClient.BuildAbsoluteUrl(string.IsNullOrWhiteSpace(res.PreviewUrl) ? res.Url : res.PreviewUrl);
        var bmp = await NativeShellHelper.LoadImageAsync(url, decodePixelWidth: 1200);

        LoadingRing.IsActive = false;
        if (bmp is not null)
        {
            PreviewImage.Source = bmp;
        }
        else
        {
            FailedText.Visibility = Visibility.Visible;
        }
    }

    private void PrevBtn_Click(object sender, RoutedEventArgs e)
    {
        _index = (_index - 1 + _post.Resources.Count) % _post.Resources.Count;
        _ = LoadCurrentAsync();
    }

    private void NextBtn_Click(object sender, RoutedEventArgs e)
    {
        _index = (_index + 1) % _post.Resources.Count;
        _ = LoadCurrentAsync();
    }

    private void ContentDialog_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Left)
        {
            PrevBtn_Click(this, new RoutedEventArgs());
            e.Handled = true;
        }
        else if (e.Key == VirtualKey.Right)
        {
            NextBtn_Click(this, new RoutedEventArgs());
            e.Handled = true;
        }
    }
}
