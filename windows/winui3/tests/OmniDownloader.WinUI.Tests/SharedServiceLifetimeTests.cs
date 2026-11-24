using System.Diagnostics;
using System.Reflection;
using OmniDownloader.WinUI.Core.Services;
using Xunit;

namespace OmniDownloader.WinUI.Tests;

public sealed class SharedServiceLifetimeTests
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ClosingClientLeavesSharedServiceRunningButStopsDevelopmentChild(bool shared)
    {
        if (!OperatingSystem.IsWindows()) return;
        using var fixture = Process.Start(new ProcessStartInfo("cmd.exe", "/c ping -n 30 127.0.0.1 >nul")
        { CreateNoWindow = true, UseShellExecute = false })!;
        try
        {
            var manager = new BackendProcessManager();
            const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
            typeof(BackendProcessManager).GetField("_managedProcess", flags)!.SetValue(manager, Process.GetProcessById(fixture.Id));
            typeof(BackendProcessManager).GetField("_sharedWebService", flags)!.SetValue(manager, shared);
            manager.Dispose();
            if (shared)
            {
                Assert.False(fixture.HasExited);
            }
            else
            {
                Assert.True(fixture.WaitForExit(5000));
            }
        }
        finally
        {
            if (!fixture.HasExited) { fixture.Kill(entireProcessTree:true); fixture.WaitForExit(5000); }
        }
    }
}
