namespace NoFocus.Desktop;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            Protocol.SelfTest();
            SongDownload.SelfTest();
            Console.WriteLine("Protocol and downloader self-tests passed.");
            return 0;
        }

        if (args.Contains("--download-youtube", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                using CancellationTokenSource cancel = new();
                Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancel.Cancel(); };
                string result = SongDownload.RunAsync(ValueAfter(args, "--url") ?? "",
                    ValueAfter(args, "--format") ?? "mp3", ValueAfter(args, "--output") ?? Environment.GetFolderPath(Environment.SpecialFolder.MyMusic),
                    new Progress<string>(Console.WriteLine), cancel.Token).GetAwaiter().GetResult();
                Console.WriteLine(result);
                return 0;
            }
            catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
        }

        if (args.Contains("--discover", StringComparer.OrdinalIgnoreCase))
        {
            var found = PhoneDiscovery.FindAsync(TimeSpan.FromSeconds(5), CancellationToken.None)
                .GetAwaiter().GetResult();
            Console.WriteLine(found?.ToString() ?? "No phone found");
            return found == null ? 1 : 0;
        }

        if (args.Contains("--headless", StringComparer.OrdinalIgnoreCase))
        {
            return RunHeadless(args);
        }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, eventArgs) =>
            MessageBox.Show(eventArgs.Exception.Message, "NoFocus Speaker", MessageBoxButtons.OK, MessageBoxIcon.Error);
        Application.Run(new MainForm());
        return 0;
    }

    private static int RunHeadless(string[] args)
    {
        string host = ValueAfter(args, "--host") ?? throw new ArgumentException("--host is required");
        string code = ValueAfter(args, "--code") ?? throw new ArgumentException("--code is required");
        int seconds = int.TryParse(ValueAfter(args, "--duration"), out int parsed) ? parsed : 10;
        using AudioEngine engine = new();
        engine.Start(host, code);
        Thread.Sleep(TimeSpan.FromSeconds(seconds));
        bool confirmed = engine.IsConfirmed;
        engine.Stop();
        Console.WriteLine($"Sent {engine.PacketsSent} primary packets in {seconds} seconds; phone confirmed: {confirmed}.");
        return confirmed ? 0 : 2;
    }

    private static string? ValueAfter(string[] args, string name)
    {
        int index = Array.FindIndex(args, item => item.Equals(name, StringComparison.OrdinalIgnoreCase));
        return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
    }
}
