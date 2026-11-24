using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using OmniDownloader.WinUI.Controls;
using OmniDownloader.WinUI.Core.Services;
using System.Runtime.CompilerServices;

namespace OmniDownloader.WinUI.Helpers;

/// <summary>
/// 显式 UI 本地化附加属性与视觉树刷新器：直接从 WinUI 原生依赖属性槽位读取原文并应用翻译，避免 CsWinRT 弱引用回收导致的匿名 TextBlock/ComboBoxItem 漏译。
/// </summary>
public static class Localized
{
    private sealed record BindingSpec(
        string AttachedName,
        DependencyProperty AttachedProperty,
        DependencyProperty DefaultTargetProperty,
        Func<DependencyObject, DependencyProperty>? TargetResolver = null);

    private static readonly object SyncLock = new();
    private static readonly ConditionalWeakTable<DependencyObject, Dictionary<DependencyProperty, string>> Sources = new();
    private static readonly List<WeakReference<DependencyObject>> Targets = new();
    private static readonly List<WeakReference<DependencyObject>> Roots = new();
    private static readonly ConditionalWeakTable<DependencyObject, object> RegisteredRoots = new();
    private static readonly ConditionalWeakTable<FrameworkElement, object> HookedLoaded = new();
    private static readonly List<BindingSpec> Specs = new();
    private static DispatcherQueue? _uiDispatcher;

    /// <summary>
    /// 指示正在刷新 ComboBox 本地化展示文本；页面 SelectionChanged 事件应忽略此期间的内部索引复位
    /// </summary>
    public static bool IsRefreshingSelection { get; private set; }

    static Localized() => LocalizationService.Instance.LanguageChanged += (_, _) => ScheduleRefresh();

    private static DependencyProperty Register(
        string name,
        DependencyProperty destination,
        Func<DependencyObject, DependencyProperty>? resolver = null)
    {
        var attached = DependencyProperty.RegisterAttached(name, typeof(string), typeof(Localized), new PropertyMetadata(null, (target, args) =>
        {
            if (target is null) return;
            var property = resolver?.Invoke(target) ?? destination;

            lock (SyncLock)
            {
                if (_uiDispatcher is null && target is UIElement ui)
                {
                    _uiDispatcher = ui.DispatcherQueue;
                }
                if (!Sources.TryGetValue(target, out var values))
                {
                    values = new();
                    Sources.Add(target, values);
                    Targets.Add(new(target));
                }
                values[property] = (string?)args.NewValue ?? string.Empty;
            }

            if (target is FrameworkElement fe && !HookedLoaded.TryGetValue(fe, out _))
            {
                HookedLoaded.Add(fe, new object());
                fe.Loaded += (s, _) =>
                {
                    if (s is DependencyObject dep)
                    {
                        RegisterRootFor(dep);
                        ApplyTarget(dep);
                    }
                };
            }

            ApplySingleProperty(target, property, (string?)args.NewValue ?? string.Empty);
        }));

        Specs.Add(new BindingSpec(name, attached, destination, resolver));
        return attached;
    }

    private static DependencyProperty ResolveTextProperty(DependencyObject target) =>
        target is SelectorBarItem ? SelectorBarItem.TextProperty : TextBlock.TextProperty;

    private static DependencyProperty ResolveHeaderProperty(DependencyObject target) => target switch
    {
        TextBox => TextBox.HeaderProperty,
        ComboBox => ComboBox.HeaderProperty,
        ToggleSwitch => ToggleSwitch.HeaderProperty,
        _ => Expander.HeaderProperty
    };

    private static DependencyProperty ResolveTitleProperty(DependencyObject target) => target switch
    {
        ContentDialog => ContentDialog.TitleProperty,
        OmniSettingsCard => OmniSettingsCard.TitleProperty,
        _ => InfoBar.TitleProperty
    };

    /// <summary>
    /// 注册需要跟随语言切换整棵刷新的根容器（例如 Page / Window.Content / ContentDialog）
    /// </summary>
    public static void RegisterRoot(DependencyObject? root)
    {
        if (root is null) return;
        lock (SyncLock)
        {
            if (_uiDispatcher is null && root is UIElement ui)
            {
                _uiDispatcher = ui.DispatcherQueue;
            }
            if (RegisteredRoots.TryGetValue(root, out _)) return;
            RegisteredRoots.Add(root, new object());
            Roots.Add(new(root));
        }
    }

    private static void RegisterRootFor(DependencyObject dep)
    {
        try
        {
            DependencyObject current = dep;
            DependencyObject? bestRoot = null;
            for (var depth = 0; depth < 64; depth++)
            {
                if (current is Page or ContentDialog)
                {
                    bestRoot = current;
                }
                var parent = VisualTreeHelper.GetParent(current);
                if (parent is null)
                {
                    bestRoot ??= current;
                    break;
                }
                current = parent;
            }
            if (bestRoot is not null)
            {
                RegisterRoot(bestRoot);
            }
        }
        catch
        {
        }
    }

    /// <summary>
    /// 递归遍历指定根节点的视觉树与常见子集合（ComboBoxItem、SelectorBarItem、Flyout 等）并重新应用当前语言文本
    /// </summary>
    public static void RefreshTree(DependencyObject? root)
    {
        if (root is null) return;
        RegisterRoot(root);

        var visited = new HashSet<DependencyObject>();
        var queue = new Queue<DependencyObject>();
        var combos = new List<ComboBox>();
        queue.Enqueue(root);

        while (queue.Count > 0 && visited.Count < 4096)
        {
            var node = queue.Dequeue();
            if (!visited.Add(node)) continue;

            try
            {
                if (node is ComboBox cb) combos.Add(cb);

                foreach (var spec in Specs)
                {
                    if (node.GetValue(spec.AttachedProperty) is string src && !string.IsNullOrEmpty(src))
                    {
                        var targetProp = spec.TargetResolver?.Invoke(node) ?? spec.DefaultTargetProperty;
                        ApplySingleProperty(node, targetProp, src);
                    }
                }

                var childCount = VisualTreeHelper.GetChildrenCount(node);
                for (var i = 0; i < childCount; i++)
                {
                    var child = VisualTreeHelper.GetChild(node, i);
                    if (child is not null) queue.Enqueue(child);
                }

                if (node is ItemsControl itemsCtrl)
                {
                    foreach (var item in itemsCtrl.Items)
                    {
                        if (item is DependencyObject itemDep) queue.Enqueue(itemDep);
                    }
                }

                if (node is SelectorBar selBar)
                {
                    foreach (var item in selBar.Items)
                    {
                        if (item is DependencyObject itemDep) queue.Enqueue(itemDep);
                    }
                }

                if (node is NavigationView navView)
                {
                    foreach (var item in navView.MenuItems)
                    {
                        if (item is DependencyObject itemDep) queue.Enqueue(itemDep);
                    }
                    foreach (var item in navView.FooterMenuItems)
                    {
                        if (item is DependencyObject itemDep) queue.Enqueue(itemDep);
                    }
                }

                if (node is ContentControl contentCtrl && contentCtrl.Content is DependencyObject contentDep)
                {
                    queue.Enqueue(contentDep);
                }

                if (node is Button button && button.Flyout is DependencyObject flyoutDep)
                {
                    EnqueueFlyout(flyoutDep, queue);
                }

                if (node is FrameworkElement fe && fe.ContextFlyout is DependencyObject ctxFlyout)
                {
                    EnqueueFlyout(ctxFlyout, queue);
                }
            }
            catch
            {
            }
        }

        foreach (var cb in combos)
        {
            RefreshComboSelection(cb);
        }
    }

    private static void EnqueueFlyout(DependencyObject flyoutDep, Queue<DependencyObject> queue)
    {
        queue.Enqueue(flyoutDep);
        if (flyoutDep is Flyout flyout && flyout.Content is DependencyObject fc)
        {
            queue.Enqueue(fc);
        }
        else if (flyoutDep is MenuFlyout mf)
        {
            foreach (var item in mf.Items)
            {
                if (item is DependencyObject mi) queue.Enqueue(mi);
            }
        }
    }

    /// <summary>
    /// 强制刷新 ComboBox 折叠态选中文本展示（解决 ComboBoxItem.Content 切换语言后收起框仍显示旧语言的问题）
    /// </summary>
    public static void RefreshComboSelection(ComboBox? box)
    {
        if (box is null) return;
        try
        {
            var idx = box.SelectedIndex;
            if (idx < 0) return;

            if (box.SelectedItem is ComboBoxItem cbi && cbi.Content is string text)
            {
                cbi.Content = string.Empty;
                cbi.Content = text;
            }

            IsRefreshingSelection = true;
            try
            {
                box.SelectedIndex = -1;
                box.SelectedIndex = idx;
            }
            finally
            {
                IsRefreshingSelection = false;
            }
        }
        catch
        {
            IsRefreshingSelection = false;
        }
    }

    private static void ApplySingleProperty(DependencyObject target, DependencyProperty property, string source)
    {
        try
        {
            var translated = LocalizationService.Instance.T(source);

            if (target is TextBlock tb && Equals(property, TextBlock.TextProperty))
            {
                tb.Text = translated;
                return;
            }
            if (target is SelectorBarItem sbi && Equals(property, SelectorBarItem.TextProperty))
            {
                sbi.Text = translated;
                return;
            }
            if (target is OmniSettingsCard osc)
            {
                if (Equals(property, OmniSettingsCard.TitleProperty))
                {
                    osc.Title = translated;
                    return;
                }
                if (Equals(property, OmniSettingsCard.DescriptionProperty))
                {
                    osc.Description = translated;
                    return;
                }
            }
            if (target is ContentControl cc && Equals(property, ContentControl.ContentProperty))
            {
                cc.Content = translated;
                return;
            }
            if (target is TextBox tbox)
            {
                if (Equals(property, TextBox.HeaderProperty))
                {
                    tbox.Header = translated;
                    return;
                }
                if (Equals(property, TextBox.PlaceholderTextProperty))
                {
                    tbox.PlaceholderText = translated;
                    return;
                }
            }
            if (target is ComboBox cbox && Equals(property, ComboBox.HeaderProperty))
            {
                cbox.Header = translated;
                return;
            }
            if (target is ToggleSwitch tsw)
            {
                if (Equals(property, ToggleSwitch.HeaderProperty))
                {
                    tsw.Header = translated;
                    return;
                }
                if (Equals(property, ToggleSwitch.OnContentProperty))
                {
                    tsw.OnContent = translated;
                    return;
                }
                if (Equals(property, ToggleSwitch.OffContentProperty))
                {
                    tsw.OffContent = translated;
                    return;
                }
            }
            if (target is InfoBar ibar)
            {
                if (Equals(property, InfoBar.TitleProperty))
                {
                    ibar.Title = translated;
                    return;
                }
                if (Equals(property, InfoBar.MessageProperty))
                {
                    ibar.Message = translated;
                    return;
                }
            }
            if (target is ContentDialog cdlg)
            {
                if (Equals(property, ContentDialog.TitleProperty))
                {
                    cdlg.Title = translated;
                    return;
                }
                if (Equals(property, ContentDialog.CloseButtonTextProperty))
                {
                    cdlg.CloseButtonText = translated;
                    return;
                }
                if (Equals(property, ContentDialog.PrimaryButtonTextProperty))
                {
                    cdlg.PrimaryButtonText = translated;
                    return;
                }
                if (Equals(property, ContentDialog.SecondaryButtonTextProperty))
                {
                    cdlg.SecondaryButtonText = translated;
                    return;
                }
            }
            if (Equals(property, ToolTipService.ToolTipProperty))
            {
                ToolTipService.SetToolTip(target, translated);
                return;
            }
            if (Equals(property, AutomationProperties.NameProperty))
            {
                AutomationProperties.SetName(target, translated);
                return;
            }

            target.SetValue(property, translated);
        }
        catch
        {
            // 忽略已卸载、关闭或跨窗口释放的控件，避免阻断后续其他控件的多语言刷新
        }
    }

    private static void ApplyTarget(DependencyObject target)
    {
        KeyValuePair<DependencyProperty, string>[] pairs;
        lock (SyncLock)
        {
            if (!Sources.TryGetValue(target, out var values)) return;
            pairs = values.ToArray();
        }
        foreach (var (property, source) in pairs)
        {
            ApplySingleProperty(target, property, source);
        }
    }

    private static void ScheduleRefresh()
    {
        var dq = _uiDispatcher;
        if (dq is not null && !dq.HasThreadAccess)
        {
            dq.TryEnqueue(Refresh);
            return;
        }
        Refresh();
    }

    private static void Refresh()
    {
        List<DependencyObject> aliveTargets;
        List<DependencyObject> aliveRoots;
        lock (SyncLock)
        {
            aliveTargets = new List<DependencyObject>(Targets.Count);
            for (var i = Targets.Count - 1; i >= 0; i--)
            {
                if (!Targets[i].TryGetTarget(out var target))
                {
                    Targets.RemoveAt(i);
                    continue;
                }
                aliveTargets.Add(target);
            }

            aliveRoots = new List<DependencyObject>(Roots.Count);
            for (var i = Roots.Count - 1; i >= 0; i--)
            {
                if (!Roots[i].TryGetTarget(out var root))
                {
                    Roots.RemoveAt(i);
                    continue;
                }
                aliveRoots.Add(root);
            }
        }

        foreach (var target in aliveTargets)
        {
            if (target is UIElement ui && ui.DispatcherQueue is not null && !ui.DispatcherQueue.HasThreadAccess)
            {
                ui.DispatcherQueue.TryEnqueue(() => ApplyTarget(target));
                continue;
            }
            ApplyTarget(target);
        }

        foreach (var root in aliveRoots)
        {
            if (root is UIElement ui && ui.DispatcherQueue is not null && !ui.DispatcherQueue.HasThreadAccess)
            {
                ui.DispatcherQueue.TryEnqueue(() => RefreshTree(root));
                continue;
            }
            RefreshTree(root);
        }
    }

    public static readonly DependencyProperty CloseButtonTextProperty = Register("CloseButtonText", ContentDialog.CloseButtonTextProperty);
    public static string GetCloseButtonText(DependencyObject target) => (string)target.GetValue(CloseButtonTextProperty);
    public static void SetCloseButtonText(DependencyObject target, string value) => target.SetValue(CloseButtonTextProperty, value);
    public static readonly DependencyProperty PrimaryButtonTextProperty = Register("PrimaryButtonText", ContentDialog.PrimaryButtonTextProperty);
    public static string GetPrimaryButtonText(DependencyObject target) => (string)target.GetValue(PrimaryButtonTextProperty);
    public static void SetPrimaryButtonText(DependencyObject target, string value) => target.SetValue(PrimaryButtonTextProperty, value);
    public static readonly DependencyProperty SecondaryButtonTextProperty = Register("SecondaryButtonText", ContentDialog.SecondaryButtonTextProperty);
    public static string GetSecondaryButtonText(DependencyObject target) => (string)target.GetValue(SecondaryButtonTextProperty);
    public static void SetSecondaryButtonText(DependencyObject target, string value) => target.SetValue(SecondaryButtonTextProperty, value);
    public static readonly DependencyProperty DescriptionProperty = Register("Description", OmniSettingsCard.DescriptionProperty);
    public static string GetDescription(DependencyObject target) => (string)target.GetValue(DescriptionProperty);
    public static void SetDescription(DependencyObject target, string value) => target.SetValue(DescriptionProperty, value);
    public static readonly DependencyProperty TextProperty = Register("Text", TextBlock.TextProperty, ResolveTextProperty);
    public static string GetText(DependencyObject target) => (string)target.GetValue(TextProperty);
    public static void SetText(DependencyObject target, string value) => target.SetValue(TextProperty, value);
    public static readonly DependencyProperty ContentProperty = Register("Content", ContentControl.ContentProperty);
    public static string GetContent(DependencyObject target) => (string)target.GetValue(ContentProperty);
    public static void SetContent(DependencyObject target, string value) => target.SetValue(ContentProperty, value);
    public static readonly DependencyProperty HeaderProperty = Register("Header", Expander.HeaderProperty, ResolveHeaderProperty);
    public static string GetHeader(DependencyObject target) => (string)target.GetValue(HeaderProperty);
    public static void SetHeader(DependencyObject target, string value) => target.SetValue(HeaderProperty, value);
    public static readonly DependencyProperty PlaceholderTextProperty = Register("PlaceholderText", TextBox.PlaceholderTextProperty);
    public static string GetPlaceholderText(DependencyObject target) => (string)target.GetValue(PlaceholderTextProperty);
    public static void SetPlaceholderText(DependencyObject target, string value) => target.SetValue(PlaceholderTextProperty, value);
    public static readonly DependencyProperty TitleProperty = Register("Title", InfoBar.TitleProperty, ResolveTitleProperty);
    public static string GetTitle(DependencyObject target) => (string)target.GetValue(TitleProperty);
    public static void SetTitle(DependencyObject target, string value) => target.SetValue(TitleProperty, value);
    public static readonly DependencyProperty MessageProperty = Register("Message", InfoBar.MessageProperty);
    public static string GetMessage(DependencyObject target) => (string)target.GetValue(MessageProperty);
    public static void SetMessage(DependencyObject target, string value) => target.SetValue(MessageProperty, value);
    public static readonly DependencyProperty OffContentProperty = Register("OffContent", ToggleSwitch.OffContentProperty);
    public static string GetOffContent(DependencyObject target) => (string)target.GetValue(OffContentProperty);
    public static void SetOffContent(DependencyObject target, string value) => target.SetValue(OffContentProperty, value);
    public static readonly DependencyProperty OnContentProperty = Register("OnContent", ToggleSwitch.OnContentProperty);
    public static string GetOnContent(DependencyObject target) => (string)target.GetValue(OnContentProperty);
    public static void SetOnContent(DependencyObject target, string value) => target.SetValue(OnContentProperty, value);
    public static readonly DependencyProperty ToolTipProperty = Register("ToolTip", ToolTipService.ToolTipProperty);
    public static string GetToolTip(DependencyObject target) => (string)target.GetValue(ToolTipProperty);
    public static void SetToolTip(DependencyObject target, string value) => target.SetValue(ToolTipProperty, value);
    public static readonly DependencyProperty NameProperty = Register("Name", AutomationProperties.NameProperty);
    public static string GetName(DependencyObject target) => (string)target.GetValue(NameProperty);
    public static void SetName(DependencyObject target, string value) => target.SetValue(NameProperty, value);
}
