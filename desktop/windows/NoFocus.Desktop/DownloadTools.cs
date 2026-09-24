using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace NoFocus.Desktop;

internal sealed record DownloadTools(string YtDlp, string Deno, string Ffmpeg)
{
    private static readonly SemaphoreSlim InstallLock = new(1, 1);
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromMinutes(15) };
    internal static string CacheDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NoFocus Speaker", "download-tools");

    internal static async Task<DownloadTools> PrepareAsync(IProgress<string> progress, CancellationToken cancellation)
    {
        await InstallLock.WaitAsync(cancellation);
        try
        {
            // Keep license texts next to the helper executables as well as embedded in the app.
            string notices = Path.Combine(CacheDirectory, "licenses");
            Directory.CreateDirectory(notices);
            foreach (string name in Assembly.GetExecutingAssembly().GetManifestResourceNames().Where(n => n.StartsWith("third-party/")))
            {
                await using Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)!;
                await using FileStream output = File.Create(Path.Combine(notices, Path.GetFileName(name)));
                await input.CopyToAsync(output, cancellation);
            }
            using Stream manifest = Assembly.GetExecutingAssembly().GetManifestResourceStream("download-tools.json")!;
            using JsonDocument document = await JsonDocument.ParseAsync(manifest, cancellationToken: cancellation);
            List<string> paths = [];
            foreach (JsonElement tool in document.RootElement.GetProperty("windows").EnumerateArray())
            {
                string name = tool.GetProperty("name").GetString()!;
                string version = tool.GetProperty("version").GetString()!;
                string directory = Path.Combine(CacheDirectory, name + "-" + version);
                string executable = Path.Combine(directory, tool.GetProperty("file").GetString()!);
                string ready = Path.Combine(directory, "ready");
                if (!File.Exists(ready) || !File.Exists(executable)
                    || File.ReadAllText(ready) != tool.GetProperty("sha256").GetString()
                    || (name == "ffmpeg" && !File.Exists(Path.Combine(directory, "ffprobe.exe"))))
                {
                    progress.Report($"First-time setup ({paths.Count + 1} of 3)…");
                    await InstallAsync(tool, directory, cancellation);
                }
                paths.Add(executable);
            }
            return new DownloadTools(paths[0], paths[1], paths[2]);
        }
        finally { InstallLock.Release(); }
    }

    private static async Task InstallAsync(JsonElement tool, string destination, CancellationToken cancellation)
    {
        Directory.CreateDirectory(CacheDirectory);
        string staging = Path.Combine(CacheDirectory, "install-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        try
        {
            string url = tool.GetProperty("url").GetString()!;
            string archive = Path.Combine(staging, "download");
            using (HttpResponseMessage response = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellation))
            {
                response.EnsureSuccessStatusCode();
                await using Stream input = await response.Content.ReadAsStreamAsync(cancellation);
                await using FileStream output = File.Create(archive);
                await input.CopyToAsync(output, cancellation);
            }
            await using (FileStream input = File.OpenRead(archive))
            {
                string actual = Convert.ToHexString(await SHA256.HashDataAsync(input, cancellation));
                if (!actual.Equals(tool.GetProperty("sha256").GetString(), StringComparison.OrdinalIgnoreCase))
                    throw new IOException("Download tool verification failed. Please try again.");
            }
            string unpacked = Path.Combine(staging, "files");
            Directory.CreateDirectory(unpacked);
            if (url.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            {
                using ZipArchive zip = ZipFile.OpenRead(archive);
                foreach (ZipArchiveEntry entry in zip.Entries)
                {
                    // These pinned distributions contain standalone executables. Never extract arbitrary paths.
                    if (entry.Name is not ("deno.exe" or "ffmpeg.exe" or "ffprobe.exe")
                        && !entry.Name.Contains("LICENSE", StringComparison.OrdinalIgnoreCase)
                        && !entry.Name.StartsWith("COPYING", StringComparison.OrdinalIgnoreCase)) continue;
                    cancellation.ThrowIfCancellationRequested();
                    entry.ExtractToFile(Path.Combine(unpacked, entry.Name));
                }
            }
            else File.Move(archive, Path.Combine(unpacked, tool.GetProperty("file").GetString()!));
            if (!File.Exists(Path.Combine(unpacked, tool.GetProperty("file").GetString()!)))
                throw new IOException("A required download tool is missing.");
            File.WriteAllText(Path.Combine(unpacked, "ready"), tool.GetProperty("sha256").GetString());
            cancellation.ThrowIfCancellationRequested();
            if (Directory.Exists(destination)) Directory.Delete(destination, true); // Only this version's private cache.
            Directory.Move(unpacked, destination);
        }
        finally { if (Directory.Exists(staging)) Directory.Delete(staging, true); }
    }
}
