using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace NoFocus.Desktop;

internal sealed class PhoneReceiverForm : Form
{
    private readonly PhoneAudioReceiver receiver = new();
    private readonly AppConfig config;
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(510, 0) };
    private readonly Button start = new() { Text = "Start listening", AutoSize = true, MinimumSize = new Size(510, 48) };
    private readonly Button rotate = new() { Text = "New pairing code", AutoSize = true };
    private readonly TextBox code = new() { ReadOnly = true, Width = 510 };
    private readonly ComboBox buffering = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 510 };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 500 };

    internal PhoneReceiverForm(AppConfig config)
    {
        this.config = config;
        Text = "NoFocus — Listen to phone";
        ClientSize = new Size(580, 650); MinimumSize = new Size(540, 500);
        AutoScaleMode = AutoScaleMode.Dpi; Font = new Font("Segoe UI", 11);
        StartPosition = FormStartPosition.CenterParent;
        FlowLayoutPanel body = new() { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, Padding = new Padding(20) };
        Controls.Add(body);
        void Label(string value) => body.Controls.Add(new Label { Text = value, AutoSize = true,
            MaximumSize = new Size(510, 0), Margin = new Padding(3, 8, 3, 8) });
        Label("Hear phone audio through your PC headphones");
        Label("1. Start listening here.\n2. On the phone: PC audio → Connect PC → Listen on PC.\n3. Find this PC, enter its code, then approve Android sharing.");
        Label("PC address (use the adapter on the same network as the phone)");
        TextBox addresses = new() { ReadOnly = true, Multiline = true, ScrollBars = ScrollBars.Vertical,
            Width = 510, Height = 66, Text = string.Join(Environment.NewLine, LocalAddresses()) };
        body.Controls.Add(addresses);
        Label("Pairing code");
        body.Controls.Add(code);
        if (config.ReceiverCode.Length != 16) { config.ReceiverCode = NewCode(); config.Save(); }
        DisplayCode();
        rotate.Click += (_, _) =>
        {
            if (receiver.IsRunning) return;
            config.ReceiverCode = NewCode(); config.Save(); DisplayCode();
        };
        body.Controls.Add(rotate);
        Label("Network buffer");
        buffering.Items.AddRange(["Fastest · 10 ms", "Balanced · 20 ms", "Reliable · 40 ms"]);
        buffering.SelectedIndex = config.ReceiverBufferPackets <= 2 ? 0 : config.ReceiverBufferPackets >= 8 ? 2 : 1;
        body.Controls.Add(buffering);
        start.Click += (_, _) => Toggle();
        body.Controls.Add(start);
        status.Text = "Ready. Plays through the Windows default output device.";
        body.Controls.Add(status);
        Label("Encrypted, uncompressed 48 kHz stereo PCM. Other PC sounds can keep playing. If Windows asks, allow NoFocus on your private network. Apps that block Android playback capture will be silent.");
        receiver.Failed += error =>
        {
            if (!IsDisposed && IsHandleCreated) BeginInvoke(() =>
            {
                if (IsDisposed) return;
                Stop(); status.Text = "Stopped: " + error.Message + "\nCheck the Windows output device, then start again.";
            });
        };
        timer.Tick += (_, _) =>
        {
            if (!receiver.IsRunning) return;
            status.Text = receiver.IsConnected
                ? $"Receiving phone audio · {receiver.Buffer!.Received:N0} packets\n{receiver.Buffer.Queued * 5} ms queued · {receiver.Buffer.Missing:N0} gaps · {receiver.Buffer.Trimmed:N0} stale packets skipped"
                : "Listening for phone audio… Check its address, pairing code and sharing permission.";
        };
        FormClosed += (_, _) => { timer.Stop(); timer.Dispose(); receiver.Dispose(); };
    }

    private void Toggle()
    {
        if (receiver.IsRunning) { Stop(); return; }
        try
        {
            int packets = new[] { 2, 4, 8 }[buffering.SelectedIndex];
            config.ReceiverBufferPackets = packets; config.Save();
            receiver.Start(config.ReceiverCode, packets);
            start.Text = "Stop listening"; rotate.Enabled = buffering.Enabled = false;
            timer.Start();
        }
        catch (Exception error) { Stop(); status.Text = "Could not listen: " + error.Message; }
    }
    private void Stop()
    {
        receiver.Stop(); timer.Stop(); start.Text = "Start listening";
        rotate.Enabled = buffering.Enabled = true; status.Text = "Stopped.";
    }
    private void DisplayCode()
    {
        string value = config.ReceiverCode;
        code.Text = string.Join('-', Enumerable.Range(0, 4).Select(i => value.Substring(i * 4, 4)));
    }
    private static string NewCode()
    {
        const string alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        return new string(Enumerable.Range(0, 16).Select(_ => alphabet[RandomNumberGenerator.GetInt32(alphabet.Length)]).ToArray());
    }
    private static IEnumerable<string> LocalAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up &&
            n.NetworkInterfaceType != NetworkInterfaceType.Loopback).SelectMany(n => n.GetIPProperties().UnicastAddresses
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork).Select(a => $"{a.Address} · {n.Name}"));
}
