using Microsoft.UI.Xaml;
using OmniDownloader.WinUI.Helpers;
using OmniDownloader.WinUI.Services;

namespace OmniDownloader.WinUI;

/// <summary>
/// 应用程序主入口：初始化主窗口并管理全局生命周期与异常兜底
/// </summary>
public partial class App : Application
{
    private MainWindow? _mainWindow;

    public App()
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            AppServices.WriteCrashLog("AppDomain", e.ExceptionObject as Exception);
        };

        UnhandledException += (_, e) =>
        {
            AppServices.WriteCrashLog("Application.UnhandledException", e.Exception);
            // 仅在非致命 UI 异常且窗口尚未销毁时挽救提示，并避免通知内部再次抛异常
            e.Handled = true;
            try
            {
                if (!AppServices.Current.IsShuttingDown && _mainWindow is not null)
                {
                    AppServices.Current.MainVm.Notify("发生意外异常: %s", Microsoft.UI.Xaml.Controls.InfoBarSeverity.Error, e.Message);
                }
            }
            catch
            {
            }
        };

        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            if (e.Exception.InnerExceptions.Any(ex => ex is not OperationCanceledException))
            {
                AppServices.WriteCrashLog("UnobservedTask", e.Exception);
            }
            e.SetObserved();
        };

        try
        {
            InitializeComponent();
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("App.InitializeComponent", ex);
            throw;
        }
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            // 在创建任何窗口与 XAML 控件前预置可原地更新的 Light / Default 强调色画刷字典
            NativeShellHelper.InitializeThemeResources(AppServices.Current.LocalState.Current.AccentColor);
        }
        catch (Exception ex)
        {
            AppServices.WriteCrashLog("OnLaunched.InitializeThemeResources", ex);
        }

        _mainWindow = new MainWindow();
        _mainWindow.Closed += (_, _) =>
        {
            _mainWindow = null;
            AppServices.Current.Dispose();
        };
        _mainWindow.Activate();
    }
}
