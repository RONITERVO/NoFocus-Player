namespace NoFocus.Desktop;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--companion-device-test"))
        {
            try { CompanionTests.ServeDeviceAsync(ValueAfter(args,"--companion-device-test") ?? throw new ArgumentException("Test directory required")).GetAwaiter().GetResult(); return 0; }
            catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        }
        if (args.Contains("--companion-test"))
        {
            try { CompanionTests.RunAsync().GetAwaiter().GetResult(); return 0; }
            catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        }
        // Development/device verification. A code file avoids credentials in process arguments.
        if (args.Contains("--receive-headless", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                string code = File.ReadAllText(ValueAfter(args, "--code-file") ?? throw new ArgumentException("--code-file is required")).Trim();
                int duration = int.TryParse(ValueAfter(args, "--duration"), out int seconds) ? Math.Clamp(seconds, 1, 300) : 30;
                using PhoneAudioReceiver receiver = new();
                Exception? failure = null;
                receiver.Failed += error => failure = error;
                receiver.Start(code);
                Console.WriteLine("Listening on UDP 39822 through the default Windows output.");
                for (int i = 0; i < duration && failure == null; i++) Thread.Sleep(1000);
                long received = receiver.Buffer!.Received, sounding = receiver.Buffer.NonSilent;
                long gaps = receiver.Buffer.Missing, trimmed = receiver.Buffer.Trimmed;
                receiver.Stop();
                if (failure != null) throw failure;
                Console.WriteLine($"Received {received} unique packets; {sounding} non-silent; {gaps} gaps; {trimmed} trimmed.");
                return received > 200 && sounding > 100 ? 0 : 2;
            }
            catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
        }
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            Protocol.SelfTest();
            PhoneAudioTests.Run();
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
        if (args.Contains("--ui-check"))
        {
            using MainForm check = new(true); check.CheckLayout(ValueAfter(args, "--ui-check") ?? "ui-check");
            Console.WriteLine("Windows home, pairing and settings layouts passed."); return 0;
        }
        using Mutex instance = new(true, @"Local\NoFocus.Windows.App", out bool first);
        using EventWaitHandle activate = new(false, EventResetMode.AutoReset, @"Local\NoFocus.Windows.Open");
        if (!first) { activate.Set(); return 0; }
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, eventArgs) =>
            MessageBox.Show(eventArgs.Exception.Message, "NoFocus Speaker", MessageBoxButtons.OK, MessageBoxIcon.Error);
        using MainForm form = new();
        RegisteredWaitHandle registration = ThreadPool.RegisterWaitForSingleObject(activate, (_, _) => {
            if (form.IsHandleCreated && !form.IsDisposed) form.BeginInvoke(form.ShowHome);
        }, null, -1, false);
        try { Application.Run(form); } finally { registration.Unregister(null); }
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
