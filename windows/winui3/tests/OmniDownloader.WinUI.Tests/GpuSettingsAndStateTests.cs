using System.Net;
using System.Text;
using System.Text.Json;
using OmniDownloader.WinUI.Core.Models;
using OmniDownloader.WinUI.Core.Services;
using Xunit;

namespace OmniDownloader.WinUI.Tests;

public sealed class GpuSettingsAndStateTests
{
    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly string _json;

        public StubHttpHandler(string json) => _json = json;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(_json, Encoding.UTF8, "application/json")
            });
        }
    }

    [Fact]
    public void LocalState_GpuAccelerationEnabledByDefault_AndPersists()
    {
        var dir = Path.Combine(Path.GetTempPath(), "omni_gpu_state_" + Guid.NewGuid().ToString("N"));
        try
        {
            using (var svc = new LocalStateService(dir))
            {
                Assert.True(svc.Current.GpuAccelerationEnabled);
                Assert.Equal("auto", svc.Current.GpuPreferredVendor);
                svc.Current.GpuAccelerationEnabled = false;
                svc.Current.GpuPreferredVendor = "intel";
                svc.Save();
            }

            using var reloaded = new LocalStateService(dir);
            Assert.False(reloaded.Current.GpuAccelerationEnabled);
            Assert.Equal("intel", reloaded.Current.GpuPreferredVendor);
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch { }
        }
    }

    [Fact]
    public async Task ApiClient_DeserializesGpuStatusAndSettings()
    {
        const string payload = """
        {
          "enabled": true,
          "preferredVendor": "auto",
          "activeBackend": "nvenc",
          "activeVendor": "nvidia",
          "activeEncoder": "h264_nvenc",
          "activeDecodeHwaccel": "cuda",
          "hardwareAvailable": true,
          "fallbackActive": false,
          "statusCode": "ACTIVE_NVENC",
          "activeAdapterName": "NVIDIA GeForce RTX 4060 Ti",
          "supportedVendors": ["nvidia", "intel"],
          "ffmpegThreads": 6
        }
        """;

        using var client = new OmniApiClient("http://127.0.0.1:58000", new StubHttpHandler(payload));
        var gpu = await client.GetGpuStatusAsync();

        Assert.True(gpu.Enabled);
        Assert.Equal("ACTIVE_NVENC", gpu.StatusCode);
        Assert.Equal("h264_nvenc", gpu.ActiveEncoder);
        Assert.Equal("cuda", gpu.ActiveDecodeHwaccel);
        Assert.Contains("intel", gpu.SupportedVendors);

        var reqJson = JsonSerializer.Serialize(new UpdateSettingsRequest
        {
            GpuAccelerationEnabled = true,
            GpuPreferredVendor = "nvidia"
        });
        Assert.Contains("\"gpuAccelerationEnabled\":true", reqJson);
        Assert.Contains("\"gpuPreferredVendor\":\"nvidia\"", reqJson);
    }
}
