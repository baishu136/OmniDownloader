using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;

namespace OmniDownloader.WinUI.Helpers;

public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var flag = value is bool b && b;
        if (string.Equals(parameter as string, "Invert", StringComparison.OrdinalIgnoreCase))
        {
            flag = !flag;
        }
        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        value is Visibility v && v == Visibility.Visible;
}

public sealed class StringToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var has = !string.IsNullOrWhiteSpace(value as string);
        if (string.Equals(parameter as string, "Invert", StringComparison.OrdinalIgnoreCase))
        {
            has = !has;
        }
        return has ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

public sealed class IntToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var n = value is int i ? i : 0;
        var positive = n > 0;
        if (string.Equals(parameter as string, "Invert", StringComparison.OrdinalIgnoreCase))
        {
            positive = !positive;
        }
        return positive ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}
