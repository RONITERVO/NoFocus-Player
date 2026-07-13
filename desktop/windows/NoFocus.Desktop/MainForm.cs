using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Net;
using System.Text.RegularExpressions;

namespace NoFocus.Desktop;

internal sealed class MainForm : Form
{
    private readonly AppConfig config = AppConfig.Load();
    private readonly AudioEngine engine = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly TextBox addressBox = new();
    private readonly TextBox codeBox = new();
    private readonly Label discoveryLabel = new();
    private readonly Label statusTitle = new();
    private readonly Label statusDetail = new();
    private readonly Panel statusDot = new();
    private readonly Button startButton = new();
    private readonly Button findButton = new();
    private readonly System.Windows.Forms.Timer statsTimer = new() { Interval = 1000 };
    private TimeSpan previousCpu;
    private DateTime previousCpuTime;

    internal MainForm()
    {
        Text = "NoFocus PC Speaker";
        ClientSize = new Size(640, 650);
        MinimumSize = MaximumSize = new Size(656, 689);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(244, 246, 249);
        Font = new Font("Segoe UI", 10f);
        AutoScaleMode = AutoScaleMode.Dpi;
        BuildInterface();

        addressBox.Text = config.PhoneAddress;
        codeBox.Text = FormatCode(config.PairingCode);
        engine.Failed += EngineFailed;
        Shown += async (_, _) => await FindPhoneAsync(quiet: true);
        FormClosing += (_, _) =>
        {
            lifetime.Cancel();
            statsTimer.Stop();
            engine.Dispose();
        };
        statsTimer.Tick += UpdateStats;
    }

    private void BuildInterface()
    {
        Label title = NewLabel("NoFocus PC Speaker", 28, FontStyle.Bold, Color.FromArgb(18, 24, 32));
        title.SetBounds(42, 30, 550, 48);
        Controls.Add(title);

        Label subtitle = NewLabel("Hear PC and phone audio together — wirelessly and privately.", 11,
            FontStyle.Regular, Color.FromArgb(78, 87, 99));
        subtitle.SetBounds(45, 82, 550, 28);
        Controls.Add(subtitle);

        Panel card = NewCard(new Rectangle(38, 128, 564, 356));
        Controls.Add(card);

        Label step1 = NewLabel("1   On the phone, tap Start Wi-Fi receiver", 12, FontStyle.Bold,
            Color.FromArgb(28, 35, 45));
        step1.SetBounds(28, 22, 500, 30);
        card.Controls.Add(step1);

        discoveryLabel.Text = "Looking for your phone…";
        discoveryLabel.ForeColor = Color.FromArgb(90, 99, 112);
        discoveryLabel.SetBounds(30, 60, 340, 25);
        card.Controls.Add(discoveryLabel);

        findButton.Text = "Find phone";
        StyleSecondaryButton(findButton);
        findButton.SetBounds(390, 52, 132, 40);
        findButton.Click += async (_, _) => await FindPhoneAsync(quiet: false);
        card.Controls.Add(findButton);

        Label addressLabel = NewLabel("Phone address", 9, FontStyle.Regular, Color.FromArgb(100, 109, 121));
        addressLabel.SetBounds(30, 101, 180, 22);
        card.Controls.Add(addressLabel);
        StyleTextBox(addressBox);
        addressBox.PlaceholderText = "Found automatically";
        addressBox.SetBounds(30, 125, 492, 38);
        card.Controls.Add(addressBox);

        Label step2 = NewLabel("2   Enter the pairing code shown on the phone", 12, FontStyle.Bold,
            Color.FromArgb(28, 35, 45));
        step2.SetBounds(28, 184, 500, 30);
        card.Controls.Add(step2);

        StyleTextBox(codeBox);
        codeBox.CharacterCasing = CharacterCasing.Upper;
        codeBox.Font = new Font("Cascadia Mono", 13f, FontStyle.Bold);
        codeBox.PlaceholderText = "ABCD-EFGH-JKLM-NPQR";
        codeBox.MaxLength = 19;
        codeBox.SetBounds(30, 222, 315, 42);
        codeBox.Leave += (_, _) => codeBox.Text = FormatCode(codeBox.Text);
        card.Controls.Add(codeBox);

        Button pasteButton = new() { Text = "Paste phone setup" };
        StyleSecondaryButton(pasteButton);
        pasteButton.SetBounds(360, 220, 162, 44);
        pasteButton.Click += (_, _) => PasteSetup();
        card.Controls.Add(pasteButton);

        Label privacy = NewLabel("The code is encrypted with your Windows account and remembered on this PC.", 9,
            FontStyle.Regular, Color.FromArgb(100, 109, 121));
        privacy.SetBounds(30, 275, 500, 42);
        card.Controls.Add(privacy);

        Panel statusCard = NewCard(new Rectangle(38, 502, 564, 70));
        Controls.Add(statusCard);
        statusDot.BackColor = Color.FromArgb(150, 158, 168);
        statusDot.SetBounds(22, 22, 18, 18);
        statusDot.Paint += (_, eventArgs) =>
        {
            eventArgs.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using SolidBrush brush = new(statusDot.BackColor);
            eventArgs.Graphics.FillEllipse(brush, 0, 0, 17, 17);
        };
        statusCard.Controls.Add(statusDot);
        statusTitle.Text = "Ready";
        statusTitle.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        statusTitle.ForeColor = Color.FromArgb(35, 42, 52);
        statusTitle.SetBounds(52, 12, 470, 24);
        statusCard.Controls.Add(statusTitle);
        statusDetail.Text = "Start the receiver on your phone, then press Start.";
        statusDetail.ForeColor = Color.FromArgb(95, 104, 116);
        statusDetail.SetBounds(52, 37, 485, 22);
        statusCard.Controls.Add(statusDetail);

        startButton.Text = "Start listening on phone";
        startButton.Font = new Font("Segoe UI", 13f, FontStyle.Bold);
        startButton.ForeColor = Color.White;
        startButton.BackColor = Color.FromArgb(26, 130, 91);
        startButton.FlatStyle = FlatStyle.Flat;
        startButton.FlatAppearance.BorderSize = 0;
        startButton.Cursor = Cursors.Hand;
        startButton.SetBounds(38, 590, 564, 54);
        startButton.Click += (_, _) => ToggleStreaming();
        Controls.Add(startButton);
        AcceptButton = startButton;
    }

    private async Task FindPhoneAsync(bool quiet)
    {
        findButton.Enabled = false;
        discoveryLabel.Text = "Looking for your phone…";
        discoveryLabel.ForeColor = Color.FromArgb(90, 99, 112);
        try
        {
            IPAddress? found = await PhoneDiscovery.FindAsync(TimeSpan.FromSeconds(4), lifetime.Token);
            if (found != null)
            {
                addressBox.Text = found.ToString();
                discoveryLabel.Text = "Phone found automatically";
                discoveryLabel.ForeColor = Color.FromArgb(26, 130, 91);
            }
            else
            {
                discoveryLabel.Text = quiet ? "Phone not found yet — manual address also works"
                    : "Phone not found. Make sure its receiver is running.";
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            discoveryLabel.Text = "Automatic discovery unavailable — enter the address manually";
            if (!quiet) MessageBox.Show(error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        finally
        {
            if (!IsDisposed) findButton.Enabled = true;
        }
    }

    private void PasteSetup()
    {
        try
        {
            string text = Clipboard.GetText();
            Match host = Regex.Match(text, @"(?:--host[= ]+|host=)(?<value>\d{1,3}(?:\.\d{1,3}){3})",
                RegexOptions.IgnoreCase);
            Match code = Regex.Match(text, @"(?:--code[= ]+|code=)(?<value>[A-Z0-9-]{16,19})",
                RegexOptions.IgnoreCase);
            if (host.Success) addressBox.Text = host.Groups["value"].Value;
            if (code.Success) codeBox.Text = FormatCode(code.Groups["value"].Value);
            if (!host.Success && !code.Success)
                MessageBox.Show("Copy the PC setup from the NoFocus phone app, then try again.", Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception error)
        {
            MessageBox.Show("Windows could not read the clipboard: " + error.Message, Text,
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void ToggleStreaming()
    {
        if (engine.IsRunning)
        {
            engine.Stop();
            statsTimer.Stop();
            SetStopped("Stopped", "Press Start whenever you want to listen again.");
            return;
        }
        try
        {
            string normalizedCode = Protocol.NormalizeCode(codeBox.Text);
            engine.Start(addressBox.Text.Trim(), normalizedCode);
            config.PhoneAddress = addressBox.Text.Trim();
            config.PairingCode = normalizedCode;
            config.Save();
            codeBox.Text = FormatCode(normalizedCode);
            startButton.Text = "Stop streaming";
            startButton.BackColor = Color.FromArgb(194, 58, 52);
            statusDot.Invalidate();
            statusDot.BackColor = Color.FromArgb(232, 160, 32);
            statusTitle.Text = "Connecting to phone";
            statusDetail.Text = "Waiting for the phone to confirm the pairing code…";
            previousCpu = Process.GetCurrentProcess().TotalProcessorTime;
            previousCpuTime = DateTime.UtcNow;
            statsTimer.Start();
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Could not start", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void EngineFailed(Exception error)
    {
        if (IsDisposed) return;
        BeginInvoke(() =>
        {
            engine.Stop();
            statsTimer.Stop();
            SetStopped("Connection stopped", "Press Start to reconnect after checking the PC sound device.");
            MessageBox.Show(error.Message, "NoFocus Speaker stopped", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        });
    }

    private void UpdateStats(object? sender, EventArgs eventArgs)
    {
        Process process = Process.GetCurrentProcess();
        DateTime now = DateTime.UtcNow;
        TimeSpan cpu = process.TotalProcessorTime;
        double wallSeconds = Math.Max(0.001, (now - previousCpuTime).TotalSeconds);
        double percent = (cpu - previousCpu).TotalSeconds / wallSeconds / Environment.ProcessorCount * 100;
        previousCpu = cpu;
        previousCpuTime = now;
        if (engine.IsConfirmed)
        {
            statusDot.BackColor = Color.FromArgb(26, 180, 105);
            statusTitle.Text = "Streaming securely";
            statusDetail.Text = $"Encrypted 48 kHz audio  •  {engine.PacketsSent:N0} frames  •  {percent:0.0}% CPU";
        }
        else
        {
            statusDot.BackColor = Color.FromArgb(232, 160, 32);
            statusTitle.Text = "Still connecting…";
            statusDetail.Text = engine.PacketsSent < 800
                ? "Waiting for the phone to confirm the pairing code."
                : "No answer yet — check that the phone receiver and pairing code are correct.";
        }
        statusDot.Invalidate();
    }

    private void SetStopped(string title, string detail)
    {
        startButton.Text = "Start listening on phone";
        startButton.BackColor = Color.FromArgb(26, 130, 91);
        statusDot.BackColor = Color.FromArgb(150, 158, 168);
        statusDot.Invalidate();
        statusTitle.Text = title;
        statusDetail.Text = detail;
    }

    private static string FormatCode(string value)
    {
        string normalized = Protocol.NormalizeCode(value);
        if (normalized.Length != 16) return normalized;
        return string.Join('-', Enumerable.Range(0, 4).Select(index => normalized.Substring(index * 4, 4)));
    }

    private static Label NewLabel(string text, float size, FontStyle style, Color color) => new()
    {
        Text = text,
        Font = new Font("Segoe UI", size, style),
        ForeColor = color,
        BackColor = Color.Transparent
    };

    private static Panel NewCard(Rectangle bounds)
    {
        Panel panel = new() { BackColor = Color.White };
        panel.SetBounds(bounds.X, bounds.Y, bounds.Width, bounds.Height);
        return panel;
    }

    private static void StyleTextBox(TextBox box)
    {
        box.BorderStyle = BorderStyle.FixedSingle;
        box.Font = new Font("Segoe UI", 11f);
    }

    private static void StyleSecondaryButton(Button button)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderColor = Color.FromArgb(198, 205, 214);
        button.BackColor = Color.White;
        button.ForeColor = Color.FromArgb(45, 54, 66);
        button.Cursor = Cursors.Hand;
    }
}
