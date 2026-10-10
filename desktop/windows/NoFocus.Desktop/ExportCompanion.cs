using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace NoFocus.Desktop;

internal sealed class ExportCompanion : IDisposable
{
    internal static string StateDirectory => Environment.GetEnvironmentVariable("NOFOCUS_EXPORT_STATE") ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NoFocusExport");
    private Process? process;
    private HttpClient? client;
    private string lastError = "";
    internal bool Ready { get; private set; }
    internal string Name { get; private set; } = "Your PC";
    internal bool OwnsProcess => process != null;
    internal void Disconnected() => Ready = false;
    internal void SignalStop() { try { if (process != null && !process.HasExited) process.StandardInput.Close(); } catch (IOException) { } }

    internal async Task StartAsync(CancellationToken cancel)
    {
        if (Ready) return;
        // A separately started current companion is reused. Never terminate an unrelated listener.
        try { await ConnectAsync(cancel); return; }
        catch (HttpRequestException) { }
        catch (IOException) { }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested) { }
        if (process != null && !process.HasExited) throw new IOException("Exports are still starting. Try again in a moment.");
        process?.Dispose(); process = null;
        string root = await PrepareAsync(cancel);
        string node = Path.Combine(root, "node.exe");
        if (!File.Exists(node)) node = "node.exe"; // Explicit development checkout only.
        ProcessStartInfo start = new(node) { WorkingDirectory = root, UseShellExecute = false,
            CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardInput = true, RedirectStandardError = true, RedirectStandardOutput = true };
        start.ArgumentList.Add(Path.Combine(root, "desktop_export", "server.cjs"));
        start.ArgumentList.Add("--managed");
        process = new Process { StartInfo = start };
        process.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) lastError = e.Data; };
        process.OutputDataReceived += (_, _) => { };
        process.Start(); process.BeginErrorReadLine(); process.BeginOutputReadLine();
        for (int i = 0; i < 60; i++)
        {
            cancel.ThrowIfCancellationRequested();
            if (process.HasExited) throw new IOException("Exports could not start. " + lastError);
            try { await ConnectAsync(cancel); return; }
            catch (HttpRequestException) { }
            catch (IOException) { }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested) { }
            await Task.Delay(500, cancel);
        }
        throw new IOException("Exports did not start in time. Check that another app is not using port 49632.");
    }
    private async Task ConnectAsync(CancellationToken cancel)
    {
        string file = Path.Combine(StateDirectory, "identity.json");
        if (!File.Exists(file)) throw new IOException("No companion identity yet.");
        using JsonDocument identity = JsonDocument.Parse(await File.ReadAllTextAsync(file, cancel));
        string token = identity.RootElement.GetProperty("token").GetString()!;
        using X509Certificate2 certificate = X509Certificate2.CreateFromPem(identity.RootElement.GetProperty("cert").GetString()!);
        byte[] pin = SHA256.HashData(certificate.RawData);
        HttpClientHandler handler = new() { UseProxy = false, AllowAutoRedirect = false };
        handler.ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert != null
            && DateTime.UtcNow >= cert.NotBefore.ToUniversalTime() && DateTime.UtcNow <= cert.NotAfter.ToUniversalTime()
            && CryptographicOperations.FixedTimeEquals(pin, SHA256.HashData(cert.RawData));
        HttpClient candidate = new(handler) { BaseAddress = new Uri("https://127.0.0.1:" +
            (Environment.GetEnvironmentVariable("NOFOCUS_EXPORT_PORT") ?? "49632")), Timeout = TimeSpan.FromSeconds(5) };
        candidate.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        try
        {
            using HttpResponseMessage response = await candidate.GetAsync("/v1/info", cancel);
            response.EnsureSuccessStatusCode();
            using JsonDocument info = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
            if (!info.RootElement.TryGetProperty("desktopProtocol", out JsonElement version) || version.GetInt32() != 1)
                throw new InvalidOperationException("An older export companion is running. Close its window or old sign-in launcher, then press Retry.");
            Name = info.RootElement.GetProperty("name").GetString() ?? "Your PC";
            client?.Dispose(); client = candidate; Ready = true;
        }
        catch { candidate.Dispose(); throw; }
    }
    internal async Task<JsonElement> RequestAsync(string method, string route, CancellationToken cancel)
    {
        if (client == null) throw new IOException("Exports are not ready yet.");
        using HttpRequestMessage request = new(new HttpMethod(method), route);
        using HttpResponseMessage response = await client.SendAsync(request, cancel);
        using JsonDocument json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancel));
        if (!response.IsSuccessStatusCode) throw new IOException(json.RootElement.TryGetProperty("error", out var error)
            ? error.GetString() : "The PC could not complete this action.");
        return json.RootElement.Clone();
    }
    internal async Task SaveAsync(string id, string destination, CancellationToken cancel)
    {
        if (!Guid.TryParse(id, out _)) throw new IOException("Invalid export.");
        using HttpResponseMessage response = await client!.GetAsync($"/v1/jobs/{id}/file", HttpCompletionOption.ResponseHeadersRead, cancel);
        response.EnsureSuccessStatusCode();
        string expected = response.Headers.GetValues("X-Content-SHA256").Single();
        string temp = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (Stream input = await response.Content.ReadAsStreamAsync(cancel))
            await using (FileStream output = File.Create(temp))
            {
                byte[] buffer = new byte[128 * 1024]; int count;
                while ((count = await input.ReadAsync(buffer, cancel)) > 0) { await output.WriteAsync(buffer.AsMemory(0, count), cancel); hash.AppendData(buffer, 0, count); }
            }
            if (!Convert.ToHexString(hash.GetHashAndReset()).Equals(expected, StringComparison.OrdinalIgnoreCase)) throw new IOException("Export checksum did not match. Try saving again.");
            File.Move(temp, destination, true);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    private static async Task<string> PrepareAsync(CancellationToken cancel)
    {
        string? development = Environment.GetEnvironmentVariable("NOFOCUS_COMPANION_ROOT");
        if (!string.IsNullOrEmpty(development) && File.Exists(Path.Combine(development, "desktop_export", "server.cjs"))) return development;
        using Stream? payload = Assembly.GetExecutingAssembly().GetManifestResourceStream("companion.zip");
        if (payload == null) throw new IOException("This development build has no export runtime. Build with desktop/windows/build-release.ps1.");
        string digest = Convert.ToHexString(await SHA256.HashDataAsync(payload, cancel)); payload.Position = 0;
        string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NoFocus Speaker", "companion", digest[..20]);
        if (File.Exists(Path.Combine(root, ".ready"))) return root;
        string staging = root + "." + Guid.NewGuid().ToString("N"); Directory.CreateDirectory(staging);
        await Task.Run(() => { using ZipArchive archive = new(payload, ZipArchiveMode.Read, true); archive.ExtractToDirectory(staging); }, cancel);
        await File.WriteAllTextAsync(Path.Combine(staging, ".ready"), digest, cancel);
        Directory.Move(staging, root); return root;
    }
    internal async Task StopAsync()
    {
        Ready = false;
        if (process != null && !process.HasExited)
        {
            try { await process.StandardInput.WriteLineAsync("stop"); await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15)); }
            catch { if (!process.HasExited) process.Kill(true); }
        }
        process?.Dispose(); process = null;
    }
    public void Dispose() { SignalStop(); client?.Dispose(); process?.Dispose(); }
}
