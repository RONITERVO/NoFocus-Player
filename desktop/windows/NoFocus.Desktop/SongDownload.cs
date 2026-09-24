using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NoFocus.Desktop;

internal static class SongDownload
{
    internal static readonly string[] Formats = ["mp3", "m4a", "wav"];
    internal static readonly string[] FormatLabels = ["MP3 - most apps", "M4A - smaller file", "WAV - editing / larger file"];

    internal static string VideoUrl(string input)
    {
        string text = input.Trim();
        if (Regex.IsMatch(text, "^[A-Za-z0-9_-]{11}$")) return "https://www.youtube.com/watch?v=" + text;
        if (!text.Contains("://")) text = "https://" + text;
        if (!Uri.TryCreate(text, UriKind.Absolute, out Uri? uri) || uri.Scheme is not ("https" or "http")
            || !string.IsNullOrEmpty(uri.UserInfo) || !uri.IsDefaultPort)
            throw new ArgumentException("Paste a YouTube song link.");
        string host = uri.Host.ToLowerInvariant();
        string id = "";
        string[] parts = uri.AbsolutePath.Trim('/').Split('/');
        if (host == "youtu.be" && parts.Length == 1) id = parts[0];
        else if (host is "youtube.com" or "www.youtube.com" or "m.youtube.com" or "music.youtube.com")
        {
            if (parts.Length == 2 && parts[0] is "shorts" or "embed" or "live") id = parts[1];
            else if (uri.AbsolutePath == "/watch")
            {
                foreach (string pair in uri.Query.TrimStart('?').Split('&'))
                    if (pair.StartsWith("v=")) id = Uri.UnescapeDataString(pair[2..]);
            }
        }
        if (!Regex.IsMatch(id, "^[A-Za-z0-9_-]{11}$")) throw new ArgumentException("Paste a YouTube song link, not a playlist.");
        return "https://www.youtube.com/watch?v=" + id;
    }

    internal static List<string> Arguments(string url, string format, string directory)
    {
        if (!Formats.Contains(format)) throw new ArgumentException("Choose MP3, M4A, or WAV.");
        return ["--ignore-config", "--no-playlist", "--no-cache-dir", "--no-mtime", "--newline", "--no-colors",
            "--socket-timeout", "30", "--retries", "3", "--max-filesize", "500M", "--match-filters", "!is_live & duration <= 7200",
            "-f", "bestaudio/best", "--extract-audio", "--audio-format", format, "--audio-quality", "192K",
            "--write-info-json", "--no-embed-info-json", "-o", Path.Combine(directory, "track.%(ext)s"), "--", VideoUrl(url)];
    }

    internal static async Task<string> RunAsync(string input, string format, string destination,
        IProgress<string> progress, CancellationToken cancellation)
    {
        string url = VideoUrl(input);
        if (!Formats.Contains(format)) throw new ArgumentException("Choose MP3, M4A, or WAV.");
        Directory.CreateDirectory(destination);
        DownloadTools tools = await DownloadTools.PrepareAsync(progress, cancellation);
        string job = Path.Combine(DownloadTools.CacheDirectory, "job-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(job);
        try
        {
            ProcessStartInfo start = new(tools.YtDlp) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8 };
            start.ArgumentList.Add("--ffmpeg-location"); start.ArgumentList.Add(Path.GetDirectoryName(tools.Ffmpeg)!);
            start.ArgumentList.Add("--js-runtimes"); start.ArgumentList.Add("deno:" + tools.Deno);
            foreach (string arg in Arguments(url, format, job)) start.ArgumentList.Add(arg);
            progress.Report("Finding audio…");
            using Process process = Process.Start(start) ?? throw new IOException("Could not start the downloader.");
            using CancellationTokenRegistration stop = cancellation.Register(() =>
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            });
            Task<string> stderr = process.StandardError.ReadToEndAsync();
            while (await process.StandardOutput.ReadLineAsync() is { } line)
            {
                if (line.StartsWith("[download]"))
                {
                    Match percent = Regex.Match(line, @"(\d+(?:\.\d+)?)%");
                    progress.Report(percent.Success ? "Downloading " + percent.Value : "Downloading audio…");
                }
                else if (line.StartsWith("[ExtractAudio]")) progress.Report("Preparing " + format.ToUpperInvariant() + "…");
            }
            await process.WaitForExitAsync(CancellationToken.None);
            string errors = await stderr;
            cancellation.ThrowIfCancellationRequested();
            if (process.ExitCode != 0) throw new IOException(FriendlyError(errors));
            string audio = Path.Combine(job, "track." + format);
            if (!File.Exists(audio) || new FileInfo(audio).Length == 0)
                throw new IOException("No audio was saved. Try a single song under two hours.");
            string title = "YouTube audio";
            string info = Path.Combine(job, "track.info.json");
            if (File.Exists(info))
            {
                using JsonDocument metadata = JsonDocument.Parse(await File.ReadAllTextAsync(info, cancellation));
                if (metadata.RootElement.TryGetProperty("title", out JsonElement value)) title = value.GetString() ?? title;
            }
            cancellation.ThrowIfCancellationRequested();
            string name = SafeName(title) + " [" + url[^11..] + "]";
            for (int suffix = 0; suffix < 10000; suffix++)
            {
                string target = Path.Combine(destination, name + (suffix == 0 ? "" : $" ({suffix})") + "." + format);
                try { File.Move(audio, target, overwrite: false); return target; }
                catch (IOException) when (File.Exists(target)) { }
            }
            throw new IOException("Too many copies already exist in this folder.");
        }
        finally { if (Directory.Exists(job)) Directory.Delete(job, true); }
    }

    internal static string SafeName(string title)
    {
        string name = new(title.Select(c => Path.GetInvalidFileNameChars().Contains(c) || char.IsControl(c) ? '_' : c).ToArray());
        name = name.Trim().TrimEnd('.');
        if (name.Length > 100) name = name[..100];
        if (name.Length > 0 && char.IsHighSurrogate(name[^1])) name = name[..^1];
        return name.Length == 0 ? "YouTube audio" : name;
    }

    internal static string FriendlyError(string error)
    {
        if (error.Contains("not a bot", StringComparison.OrdinalIgnoreCase) || error.Contains("Sign in", StringComparison.OrdinalIgnoreCase))
            return "YouTube requires sign-in for this link. Try another publicly available song.";
        if (error.Contains("unavailable", StringComparison.OrdinalIgnoreCase) || error.Contains("Private video", StringComparison.OrdinalIgnoreCase))
            return "This video is unavailable. Try another song link.";
        if (error.Contains("timed out", StringComparison.OrdinalIgnoreCase)) return "The connection timed out. Check your internet and try again.";
        string last = error.Split('\n').LastOrDefault(line => line.StartsWith("ERROR:"))?.Trim() ?? "The download failed. Check the link and try again.";
        return last.Length > 400 ? last[..400] : last;
    }

    internal static void SelfTest()
    {
        const string id = "BaW_jenozKc";
        foreach (string url in new[] { id, "https://youtu.be/" + id + "?si=x", "https://music.youtube.com/watch?v=" + id + "&list=ignored", "https://www.youtube.com/shorts/" + id })
            if (VideoUrl(url) != "https://www.youtube.com/watch?v=" + id) throw new Exception("Video URL normalization failed.");
        foreach (string bad in new[] { "https://youtube.com.evil.test/watch?v=" + id, "file:///etc/passwd", "https://youtube.com/playlist?list=x", "--exec calc.exe", "https://name@youtube.com/watch?v=" + id })
        {
            try { VideoUrl(bad); throw new Exception("Invalid URL accepted."); } catch (ArgumentException) { }
        }
        foreach (string format in Formats)
        {
            List<string> args = Arguments(id, format, @"C:\Music folder");
            if (args[^2] != "--" || !args.Contains("--no-playlist") || !args.Contains(format)) throw new Exception("Unsafe download arguments.");
        }
        if (SafeName("a/b:c") != "a_b_c") throw new Exception("Filename sanitization failed.");
    }
}
