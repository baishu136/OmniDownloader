using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 自动换行流式布局面板：按子元素自然宽高水平排布并自适应折行（用于支持平台芯片与中转站快捷按钮）
/// </summary>
public sealed class WrapPanel : Panel
{
    public static readonly DependencyProperty HorizontalSpacingProperty = DependencyProperty.Register(
        nameof(HorizontalSpacing), typeof(double), typeof(WrapPanel), new PropertyMetadata(6.0, OnSpacingChanged));

    public static readonly DependencyProperty VerticalSpacingProperty = DependencyProperty.Register(
        nameof(VerticalSpacing), typeof(double), typeof(WrapPanel), new PropertyMetadata(6.0, OnSpacingChanged));

    public double HorizontalSpacing
    {
        get => (double)GetValue(HorizontalSpacingProperty);
        set => SetValue(HorizontalSpacingProperty, value);
    }

    public double VerticalSpacing
    {
        get => (double)GetValue(VerticalSpacingProperty);
        set => SetValue(VerticalSpacingProperty, value);
    }

    private static void OnSpacingChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is WrapPanel panel)
        {
            panel.InvalidateMeasure();
            panel.InvalidateArrange();
        }
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var maxWidth = double.IsInfinity(availableSize.Width) || double.IsNaN(availableSize.Width)
            ? double.MaxValue
            : Math.Max(0, availableSize.Width);
        double lineWidth = 0;
        double lineHeight = 0;
        double totalWidth = 0;
        double totalHeight = 0;

        foreach (var child in Children)
        {
            if (child.Visibility == Visibility.Collapsed)
            {
                continue;
            }

            child.Measure(new Size(maxWidth, double.PositiveInfinity));
            var sz = child.DesiredSize;
            var needSpacing = lineWidth > 0 ? HorizontalSpacing : 0;

            if (lineWidth > 0 && lineWidth + needSpacing + sz.Width > maxWidth + 0.05)
            {
                totalWidth = Math.Max(totalWidth, lineWidth);
                totalHeight += lineHeight + VerticalSpacing;
                lineWidth = sz.Width;
                lineHeight = sz.Height;
            }
            else
            {
                lineWidth += needSpacing + sz.Width;
                lineHeight = Math.Max(lineHeight, sz.Height);
            }
        }

        totalWidth = Math.Max(totalWidth, lineWidth);
        totalHeight += lineHeight;
        var finalWidth = maxWidth < double.MaxValue ? maxWidth : totalWidth;
        return new Size(finalWidth, totalHeight);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var maxWidth = Math.Max(0, finalSize.Width);
        double x = 0;
        double y = 0;
        double lineHeight = 0;

        foreach (var child in Children)
        {
            if (child.Visibility == Visibility.Collapsed)
            {
                continue;
            }

            var sz = child.DesiredSize;
            if (x > 0 && x + HorizontalSpacing + sz.Width > maxWidth + 0.05)
            {
                x = 0;
                y += lineHeight + VerticalSpacing;
                lineHeight = 0;
            }
            else if (x > 0)
            {
                x += HorizontalSpacing;
            }

            child.Arrange(new Rect(x, y, sz.Width, sz.Height));
            x += sz.Width;
            lineHeight = Math.Max(lineHeight, sz.Height);
        }

        return finalSize;
    }
}
