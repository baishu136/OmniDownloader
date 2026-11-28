namespace OmniDownloader.WinUI.Core.Models;

/// <summary>
/// 系统托盘右键菜单操作标识
/// </summary>
public enum TrayMenuAction
{
    Separator = 0,
    ShowWindow = 1001,
    LaunchWeb = 1002,
    ExitApp = 1003,
    ToggleClipboardWatch = 1004
}

/// <summary>
/// 托盘右键菜单渲染条目（纯数据模型，便于跨平台与单元测试验证）
/// </summary>
public sealed record TrayMenuItemSpec(TrayMenuAction Action, string Label)
{
    public bool IsSeparator => Action == TrayMenuAction.Separator;
}

/// <summary>
/// 托盘右键菜单构建器：根据「是否开启网页服务」及剪贴板监听状态构建原生菜单项
/// </summary>
public static class TrayMenuCatalog
{
    public static IReadOnlyList<TrayMenuItemSpec> BuildMenuItems(
        bool enableWebService,
        Func<string, string>? translate = null,
        bool clipboardWatchActive = false,
        bool clipboardWatchPaused = false)
    {
        var t = translate ?? (s => s);
        var items = new List<TrayMenuItemSpec>
        {
            new(TrayMenuAction.ShowWindow, t("显示窗口"))
        };

        if (enableWebService)
        {
            items.Add(new TrayMenuItemSpec(TrayMenuAction.LaunchWeb, t("拉起网页版")));
        }

        if (clipboardWatchActive)
        {
            items.Add(new TrayMenuItemSpec(
                TrayMenuAction.ToggleClipboardWatch,
                clipboardWatchPaused ? t("恢复剪贴板自动下载") : t("暂停剪贴板自动下载")));
        }

        items.Add(new TrayMenuItemSpec(TrayMenuAction.Separator, string.Empty));
        items.Add(new TrayMenuItemSpec(TrayMenuAction.ExitApp, t("退出程序")));
        return items;
    }
}
