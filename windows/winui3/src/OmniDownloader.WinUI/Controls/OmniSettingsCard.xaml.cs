using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;

namespace OmniDownloader.WinUI.Controls;

/// <summary>
/// 对齐 Windows 11 原生 SettingsCard 的可复用列表卡片组件（支持点击跳转与右侧内嵌交互控件）
/// </summary>
[ContentProperty(Name = nameof(ActionContent))]
public sealed partial class OmniSettingsCard : UserControl
{
    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(
        nameof(Title), typeof(string), typeof(OmniSettingsCard), new PropertyMetadata(string.Empty));

    public static readonly DependencyProperty DescriptionProperty = DependencyProperty.Register(
        nameof(Description), typeof(string), typeof(OmniSettingsCard), new PropertyMetadata(string.Empty));

    public static readonly DependencyProperty GlyphProperty = DependencyProperty.Register(
        nameof(Glyph), typeof(string), typeof(OmniSettingsCard), new PropertyMetadata("\uE713"));

    public static readonly DependencyProperty ActionContentProperty = DependencyProperty.Register(
        nameof(ActionContent), typeof(object), typeof(OmniSettingsCard), new PropertyMetadata(null));

    public static readonly DependencyProperty IsClickEnabledProperty = DependencyProperty.Register(
        nameof(IsClickEnabled), typeof(bool), typeof(OmniSettingsCard), new PropertyMetadata(false));

    public event RoutedEventHandler? CardClicked;

    public string Title
    {
        get => (string)GetValue(TitleProperty);
        set => SetValue(TitleProperty, value);
    }

    public string Description
    {
        get => (string)GetValue(DescriptionProperty);
        set => SetValue(DescriptionProperty, value);
    }

    public string Glyph
    {
        get => (string)GetValue(GlyphProperty);
        set => SetValue(GlyphProperty, value);
    }

    public object? ActionContent
    {
        get => GetValue(ActionContentProperty);
        set => SetValue(ActionContentProperty, value);
    }

    public bool IsClickEnabled
    {
        get => (bool)GetValue(IsClickEnabledProperty);
        set => SetValue(IsClickEnabledProperty, value);
    }

    public OmniSettingsCard() => InitializeComponent();

    private void RootBorder_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (ActionContent is null) return;
        // 卡片过窄时将右侧交互控件（如下拉框、开关）移至第二行，防止压扁标题
        var stacked = e.NewSize.Width < 430;
        Grid.SetRow(ActionPresenter, stacked ? 1 : 0);
        Grid.SetColumn(ActionPresenter, stacked ? 1 : 2);
        ActionPresenter.HorizontalAlignment = stacked ? HorizontalAlignment.Left : HorizontalAlignment.Right;
    }

    private void InteractiveCardButton_Click(object sender, RoutedEventArgs e)
    {
        if (IsClickEnabled)
        {
            CardClicked?.Invoke(this, new RoutedEventArgs());
        }
    }
}
