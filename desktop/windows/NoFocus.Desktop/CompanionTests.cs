using System.Net;
using System.Net.Sockets;

namespace NoFocus.Desktop;

internal static class CompanionTests
{
    internal static async Task ServeDeviceAsync(string directory)
    {
        Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_STATE", Path.GetFullPath(directory));
        Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_PORT", "49633");
        using ExportCompanion service = new();
        try {
            await service.StartAsync(CancellationToken.None);
            var pairs = await service.RequestAsync("GET", "/v1/desktop/pairings", CancellationToken.None);
            string host = Environment.GetEnvironmentVariable("NOFOCUS_TEST_HOST") ?? throw new Exception("NOFOCUS_TEST_HOST is required.");
            var pair = pairs.GetProperty("pairs").EnumerateArray().Single(p => p.GetProperty("address").GetString() == host);
            await File.WriteAllTextAsync(Path.Combine(directory,"pair.txt"),pair.GetProperty("code").GetString());
            Console.WriteLine("Bundled companion is ready for the phone test.");
            for(int i=0;i<600 && !File.Exists(Path.Combine(directory,"stop"));i++) await Task.Delay(1000);
        } finally { await service.StopAsync(); }
    }
    internal static async Task RunAsync()
    {
        string state = Path.Combine(Path.GetTempPath(), "nofocus-managed-test-" + Guid.NewGuid());
        string? oldState = Environment.GetEnvironmentVariable("NOFOCUS_EXPORT_STATE"), oldPort = Environment.GetEnvironmentVariable("NOFOCUS_EXPORT_PORT");
        TcpListener listener = new(IPAddress.Loopback, 0);listener.Start();int port = ((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
        Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_STATE", state);Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_PORT", port.ToString());
        using ExportCompanion first = new(), second = new();
        try
        {
            using CancellationTokenSource limit = new(TimeSpan.FromSeconds(90));
            await first.StartAsync(limit.Token);
            if (!first.Ready || !first.OwnsProcess) throw new Exception("Bundled service did not start.");
            var initial = await first.RequestAsync("GET", "/v1/desktop", limit.Token);
            if (initial.GetProperty("audio").GetProperty("toPhone").GetString()!.Length != 16) throw new Exception("Missing unified audio credentials.");
            await second.StartAsync(limit.Token);
            if (!second.Ready || second.OwnsProcess) throw new Exception("Second client did not reuse the listener.");
            await second.StopAsync();
            await first.RequestAsync("GET", "/v1/jobs", limit.Token); // Detaching must not stop another owner's service.
            await first.StopAsync();await first.StartAsync(limit.Token);
            var restarted = await first.RequestAsync("GET", "/v1/desktop", limit.Token);
            if (initial.GetProperty("audio").ToString() != restarted.GetProperty("audio").ToString()) throw new Exception("Pairing changed on restart.");
            var pairs = await first.RequestAsync("GET", "/v1/desktop/pairings", limit.Token);
            foreach (var pair in pairs.GetProperty("pairs").EnumerateArray())
                if (!pair.GetProperty("qr").GetString()!.StartsWith("data:image/png;base64,")) throw new Exception("Missing native QR image.");
            Console.WriteLine("Bundled runtime, authenticated control, QR generation, reuse, graceful shutdown and stable pairing passed.");
        }
        finally
        {
            await first.StopAsync();await second.StopAsync();
            Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_STATE", oldState);Environment.SetEnvironmentVariable("NOFOCUS_EXPORT_PORT", oldPort);
            if (Directory.Exists(state)) Directory.Delete(state, true);
        }
    }
}
