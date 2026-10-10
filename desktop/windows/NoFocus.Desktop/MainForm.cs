using System.Text.Json;

namespace NoFocus.Desktop;

internal sealed class MainForm : Form
{
    private readonly AppConfig config = AppConfig.Load();
    private readonly ExportCompanion companion = new();
    private readonly AudioEngine sender = new();
    private readonly PhoneAudioReceiver receiver = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly TabControl tabs = new() { Dock = DockStyle.Fill, Padding = new Point(22, 10) };
    private readonly Label connection = Label("Starting NoFocus…", 11), audioStatus = Label("Choose where you want to listen.", 11);
    private readonly Label exportStatus = Label("Preparing exports…", 11), jobDetail = Label("Videos sent from your phone appear here.", 10);
    private readonly Button toPhone = AudioButton("Listen on phone\nPC → phone"), toPc = AudioButton("Listen on PC\nPhone → PC");
    private readonly Button pause = Button("Pause"), resume = Button("Resume"), save = Button("Save video…"), remove = Button("Remove…");
    private readonly DataGridView jobs = new() { Dock = DockStyle.Fill, ReadOnly = true, AllowUserToAddRows = false,
        AllowUserToDeleteRows = false, AllowUserToResizeRows = false, MultiSelect = false,
        SelectionMode = DataGridViewSelectionMode.FullRowSelect, RowHeadersVisible = false,
        AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill, BackgroundColor = Color.White,
        BorderStyle = BorderStyle.None, AutoGenerateColumns = false };
    private readonly PictureBox qr = new() { Dock = DockStyle.Fill, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.White };
    private readonly ComboBox networks = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly Label pairStatus = Label("Preparing your pairing code…", 11);
    private readonly NotifyIcon tray = new() { Icon = SystemIcons.Application, Text = "NoFocus", Visible = true };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1500 };
    private JsonElement[] pairings = [], currentJobs = [];
    private string phoneAddress = "", sendCode = "", receiveCode = "", pairingCode = "";
    private bool refreshing, working, exiting, closed;
    private int reconnects;

    internal MainForm(bool diagnostics = false)
    {
        Text = "NoFocus"; ClientSize = new Size(800, 710); MinimumSize = new Size(640, 720);
        Font = new Font("Segoe UI", 11); AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Color.FromArgb(244, 247, 246); StartPosition = FormStartPosition.CenterScreen;
        Build();
        ContextMenuStrip menu = new(); menu.Items.Add("Open NoFocus", null, (_, _) => ShowHome());
        menu.Items.Add("Quit NoFocus", null, async (_, _) => await QuitAsync()); tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowHome();
        sender.Failed += PostError; receiver.Failed += PostError;
        timer.Tick += async (_, _) => await RefreshAsync();
        if (!diagnostics) Shown += async (_, _) => await StartAsync();
        else tray.Visible = false;
        FormClosing += (_, e) => {
            if (e.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing) {
                exiting = true; lifetime.Cancel(); sender.Dispose(); receiver.Dispose(); companion.SignalStop(); tray.Visible = false;
            } else if (!closed) { e.Cancel = true; Hide(); }
        };
    }
    private void Build()
    {
        TableLayoutPanel shell = Column(4); shell.Padding = new Padding(22, 16, 22, 10); Controls.Add(shell);
        Rows(shell, 2); FlowLayoutPanel header = Flow(); header.Controls.Add(Label("NoFocus", 26, true));
        Button pair = Button("Pair phone"); pair.Click += async (_, _) => { tabs.SelectedIndex = 1; await LoadPairingsAsync(); };
        header.Controls.Add(pair); shell.Controls.Add(header); shell.Controls.Add(connection); shell.Controls.Add(tabs);
        shell.Controls.Add(Label("Closing this window keeps audio and exports running. Quit from Settings.", 9));

        TabPage home = Page("Home"), setup = Page("Pair phone"), settings = Page("Settings"); tabs.TabPages.AddRange([home, setup, settings]);
        TableLayoutPanel body = Column(7); body.Padding = new Padding(16); home.Controls.Add(body); Rows(body, 4);
        body.Controls.Add(Label("Where do you want to listen?", 16, true));
        TableLayoutPanel directions = new() { Dock = DockStyle.Top, Height = 86, ColumnCount = 2 };
        directions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); directions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        directions.Controls.Add(toPhone); directions.Controls.Add(toPc); body.Controls.Add(directions);
        toPhone.Click += (_, _) => StartAudio(false); toPc.Click += (_, _) => StartAudio(true); body.Controls.Add(audioStatus);
        FlowLayoutPanel exportHeader = Flow(); exportHeader.Controls.Add(Label("Phone exports", 16, true)); exportHeader.Controls.Add(exportStatus); body.Controls.Add(exportHeader);
        jobs.Columns.Add(new DataGridViewTextBoxColumn { Name = "Song", FillWeight = 55 });
        jobs.Columns.Add(new DataGridViewTextBoxColumn { Name = "Status", FillWeight = 25 });
        jobs.Columns.Add(new DataGridViewTextBoxColumn { Name = "Progress", FillWeight = 20 });
        jobs.RowTemplate.Height = 38; jobs.ColumnHeadersHeight = 36; jobs.MinimumSize = new Size(0, 110);
        jobs.SelectionChanged += (_, _) => UpdateActions(); body.Controls.Add(jobs);
        FlowLayoutPanel actions = Flow(); actions.Controls.AddRange([pause, resume, save, remove]); body.Controls.Add(actions); body.Controls.Add(jobDetail);
        pause.Click += async (_, _) => await ChangeJobAsync("pause"); resume.Click += async (_, _) => await ChangeJobAsync("start");
        remove.Click += async (_, _) => await ChangeJobAsync("remove"); save.Click += async (_, _) => await SaveJobAsync();

        TableLayoutPanel pairing = Column(6); pairing.Padding = new Padding(20); setup.Controls.Add(pairing); Rows(pairing, 2);
        pairing.Controls.Add(Label("Pair once. Audio and exports are ready.", 18, true));
        pairing.Controls.Add(Label("Use your phone camera to scan, then tap Pair PC in NoFocus.", 11));
        pairing.Controls.Add(qr); qr.MinimumSize = new Size(160, 160); pairing.Controls.Add(networks);
        networks.SelectedIndexChanged += (_, _) => DisplayPairing();
        FlowLayoutPanel pairActions = Flow(); Button copy = Button("Copy pairing code"), retry = Button("Retry connection");
        copy.Click += (_, _) => { if (pairingCode.Length > 0) { Clipboard.SetText(pairingCode); pairStatus.Text = "On your phone: Connect PC → Pair PC → Paste code."; } };
        retry.Click += async (_, _) => { reconnects = 0; await StartAsync(); await LoadPairingsAsync(); };
        pairActions.Controls.AddRange([copy, retry]); pairing.Controls.Add(pairActions); pairing.Controls.Add(pairStatus);
        tabs.SelectedIndexChanged += async (_, _) => { if (tabs.SelectedIndex == 1) await LoadPairingsAsync(); };

        FlowLayoutPanel options = new() { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(20) };
        settings.Controls.Add(options); options.Controls.Add(Label("Extras", 18, true));
        Button download = Button("Download a song"); download.Click += (_, _) => { using DownloadForm form = new(); form.ShowDialog(this); }; options.Controls.Add(download);
        options.Controls.Add(Label("Phone audio buffer", 11));
        ComboBox buffer = new() { Width = 290, DropDownStyle = ComboBoxStyle.DropDownList };
        buffer.Items.AddRange(["Fast (10 ms)", "Balanced (20 ms)", "Steady (40 ms)"]);
        buffer.SelectedIndex = config.ReceiverBufferPackets <= 2 ? 0 : config.ReceiverBufferPackets >= 8 ? 2 : 1;
        buffer.SelectedIndexChanged += (_, _) => { config.ReceiverBufferPackets = new[] { 2, 4, 8 }[buffer.SelectedIndex]; AppConfig.SaveReceiverBuffer(config.ReceiverBufferPackets); audioStatus.Text = "Buffer saved. Applies next time you start listening."; }; options.Controls.Add(buffer);
        Button legacy = Button("Older phone app / manual setup…"); legacy.Click += (_, _) => {
            sender.Stop(); receiver.Stop(); using LegacySenderForm form = new(); form.ShowDialog(this); }; options.Controls.Add(legacy);
        options.Controls.Add(Label("Audio capture needs Android’s sharing approval each time.\nFinished exports are kept for 24 hours; save videos you want to keep.", 11));
        Button quit = Button("Quit NoFocus"); quit.Click += async (_, _) => await QuitAsync(); options.Controls.Add(quit);
        UpdateActions();
    }
    private async Task StartAsync()
    {
        if (working || exiting) return; working = true; connection.Text = "Starting audio and exports…";
        try { await companion.StartAsync(lifetime.Token); await RefreshAsync(); timer.Start(); }
        catch (Exception error) { connection.Text = "Exports need attention"; exportStatus.Text = "Not ready"; pairStatus.Text = error.Message; tabs.SelectedIndex = 1; }
        finally { working = false; UpdateActions(); }
    }
    private async Task RefreshAsync()
    {
        if (refreshing || exiting) return;
        if (!companion.Ready) { if (!working && reconnects++ < 3) await StartAsync(); return; }
        refreshing = true;
        try
        {
            JsonElement state = await companion.RequestAsync("GET", "/v1/desktop", lifetime.Token);
            string nextSend = state.GetProperty("audio").GetProperty("toPhone").GetString()!, nextReceive = state.GetProperty("audio").GetProperty("toPc").GetString()!;
            if ((sendCode.Length > 0 && sendCode != nextSend) || (receiveCode.Length > 0 && receiveCode != nextReceive)) { sender.Stop(); receiver.Stop(); }
            sendCode = nextSend; receiveCode = nextReceive; var phone = state.GetProperty("phone");
            phoneAddress = phone.ValueKind == JsonValueKind.Object ? phone.GetProperty("address").GetString()! : "";
            connection.Text = phoneAddress.Length > 0 ? phone.GetProperty("name").GetString() + " paired · Audio and exports ready" : "Pair your phone to get started";
            JsonElement queue = await companion.RequestAsync("GET", "/v1/jobs", lifetime.Token);
            string? selected = SelectedJob()?.GetProperty("id").GetString();
            currentJobs = queue.GetProperty("jobs").EnumerateArray().Select(j => j.Clone()).ToArray(); jobs.Rows.Clear();
            foreach (JsonElement job in currentJobs)
            {
                int frames = job.GetProperty("frames").GetInt32(), total = job.GetProperty("total").GetInt32();
                int index = jobs.Rows.Add(job.GetProperty("title").GetString(), Status(job.GetProperty("status").GetString()!), total > 0 ? $"{frames * 100L / total}%" : "");
                jobs.Rows[index].Tag = job;
                if (job.GetProperty("id").GetString() == selected) jobs.Rows[index].Selected = true;
            }
            reconnects = 0; exportStatus.Text = currentJobs.Length == 0 ? "Ready for your phone" : $"{currentJobs.Length} in your queue"; UpdateActions();
        }
        catch (OperationCanceledException) when (exiting) { }
        catch (Exception error) { companion.Disconnected(); exportStatus.Text = "Reconnecting…"; connection.Text = "Exports unavailable · Retry in Pair phone"; pairStatus.Text = error.Message; }
        finally { refreshing = false; }
        if (sender.IsRunning) audioStatus.Text = sender.IsConfirmed ? "PC audio is playing on your phone." : "On your phone: PC audio → Start.";
        else if (receiver.IsRunning) audioStatus.Text = receiver.IsConnected ? "Phone audio is playing through your PC." : "On your phone: Connect PC → Listen on PC → Start.";
        toPhone.Text = sender.IsRunning ? "Stop audio\nPC → phone" : "Listen on phone\nPC → phone";
        toPc.Text = receiver.IsRunning ? "Stop audio\nPhone → PC" : "Listen on PC\nPhone → PC";
    }
    private void StartAudio(bool fromPhone)
    {
        try
        {
            if ((fromPhone && receiver.IsRunning) || (!fromPhone && sender.IsRunning)) { receiver.Stop(); sender.Stop(); audioStatus.Text = "Audio stopped."; }
            else {
                if (phoneAddress.Length == 0 || receiveCode.Length == 0) { tabs.SelectedIndex = 1; return; }
                sender.Stop(); receiver.Stop();
                if (fromPhone) receiver.Start(receiveCode, config.ReceiverBufferPackets); else sender.Start(phoneAddress, sendCode);
            }
            _ = RefreshAsync();
        }
        catch (Exception error) { audioStatus.Text = error.Message; }
    }
    private async Task LoadPairingsAsync()
    {
        if (!companion.Ready || exiting) return;
        try
        {
            var result = await companion.RequestAsync("GET", "/v1/desktop/pairings", lifetime.Token);
            string previous = networks.SelectedItem?.ToString() ?? "";
            pairings = result.GetProperty("pairs").EnumerateArray().Select(p => p.Clone()).OrderBy(p => AddressRank(p.GetProperty("address").GetString()!)).ToArray();
            networks.Items.Clear(); foreach (var pair in pairings) networks.Items.Add(pair.GetProperty("address").GetString()!);
            if (networks.Items.Count > 0) networks.SelectedIndex = Math.Max(0, networks.Items.IndexOf(previous));
            else pairStatus.Text = "Connect this PC and your phone to the same home network.";
        }
        catch (Exception error) { pairStatus.Text = error.Message; }
    }
    private static int AddressRank(string address) => address.StartsWith("192.168.") ? 0 : address.StartsWith("10.") ? 1 : address.StartsWith("172.") ? 2 : 3;
    private void DisplayPairing()
    {
        if (networks.SelectedIndex < 0 || networks.SelectedIndex >= pairings.Length) return;
        var pair = pairings[networks.SelectedIndex]; pairingCode = pair.GetProperty("code").GetString()!;
        using MemoryStream bytes = new(Convert.FromBase64String(pair.GetProperty("qr").GetString()!.Split(',')[1]));
        using Image source = Image.FromStream(bytes); Image? previous = qr.Image; qr.Image = new Bitmap(source); previous?.Dispose();
        pairStatus.Text = "Same home network. One scan sets up audio and video exports.";
    }
    private JsonElement? SelectedJob() => jobs.SelectedRows.Count > 0 && jobs.SelectedRows[0].Tag is JsonElement job ? job : null;
    private void UpdateActions()
    {
        var job = SelectedJob(); string status = job?.GetProperty("status").GetString() ?? "";
        pause.Enabled = !working && status is "queued" or "rendering"; resume.Enabled = !working && status is "paused" or "failed";
        save.Enabled = !working && status == "complete"; remove.Enabled = !working && job.HasValue;
        jobDetail.Text = job.HasValue && job.Value.TryGetProperty("error", out var error) && error.GetString()?.Length > 0
            ? error.GetString() : currentJobs.Length == 0 ? "On your phone: Visuals → Export video → Automatic." : "Select a video to pause, resume or save it.";
    }
    private async Task ChangeJobAsync(string action)
    {
        var job = SelectedJob(); if (!job.HasValue || working) return;
        if (action == "remove" && MessageBox.Show(this, "Remove this export from the PC queue? Save its video first if you want to keep it.", "Remove export", MessageBoxButtons.OKCancel) != DialogResult.OK) return;
        working = true; UpdateActions();
        try { string route = "/v1/jobs/" + job.Value.GetProperty("id").GetString(); await companion.RequestAsync(action == "remove" ? "DELETE" : "POST", route + (action == "remove" ? "" : "/" + action), lifetime.Token); }
        catch (Exception error) { MessageBox.Show(this, error.Message, "Export needs attention"); }
        finally { working = false; await RefreshAsync(); }
    }
    private async Task SaveJobAsync()
    {
        var job = SelectedJob(); if (!job.HasValue || working) return;
        string extension = job.Value.GetProperty("extension").GetString()!, title = job.Value.GetProperty("title").GetString() ?? "NoFocus video";
        foreach (char c in Path.GetInvalidFileNameChars()) title = title.Replace(c, '_');
        using SaveFileDialog dialog = new() { FileName = title + "." + extension, Filter = $"Video (*.{extension})|*.{extension}", OverwritePrompt = true };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        working = true; UpdateActions();
        try { await companion.SaveAsync(job.Value.GetProperty("id").GetString()!, dialog.FileName, lifetime.Token); MessageBox.Show(this, "Video saved.", "NoFocus"); }
        catch (Exception error) { MessageBox.Show(this, error.Message, "Could not save video"); }
        finally { working = false; UpdateActions(); }
    }
    private void PostError(Exception error) { if (!IsDisposed) BeginInvoke(() => { sender.Stop(); receiver.Stop(); audioStatus.Text = error.Message; }); }
    internal void ShowHome() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    internal void CheckLayout(string directory)
    {
        Directory.CreateDirectory(directory);
        StartPosition = FormStartPosition.Manual; Location = new Point(-20000, -20000); Show();
        foreach (Size size in new[] { new Size(800, 710), new Size(640, 700) })
        {
            ClientSize = size;
            for (int page = 0; page < tabs.TabPages.Count; page++)
            {
                tabs.SelectedIndex = page; PerformLayout(); Application.DoEvents();
                CheckBounds(tabs.SelectedTab!);
                if (page == 0 && jobs.Parent is TableLayoutPanel body && body.GetRowHeights()[4] < jobs.ColumnHeadersHeight + jobs.RowTemplate.Height * 2)
                    throw new InvalidOperationException("Export queue needs space for at least two visible rows.");
                using Bitmap bitmap = new(Width, Height); DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                bitmap.Save(Path.Combine(directory, $"windows-{size.Width}-{page}.png"));
            }
        }
        closed = true; tray.Dispose(); timer.Dispose(); Close();
    }
    private static void CheckBounds(Control parent)
    {
        foreach (Control control in parent.Controls)
        {
            if (!control.Visible) continue;
            if (control is Button && !parent.ClientRectangle.Contains(control.Bounds))
                throw new InvalidOperationException("Clipped control: " + control.Text);
            if (control is Button button && button.Height < 40) throw new InvalidOperationException("Small control: " + button.Text);
            CheckBounds(control);
        }
    }
    private async Task QuitAsync()
    {
        if (exiting) return;
        if (currentJobs.Any(j => j.GetProperty("status").GetString() is "rendering" or "queued") && companion.OwnsProcess
            && MessageBox.Show(this, "Quit and pause PC exports? They will restart automatically when you open NoFocus again.", "Quit NoFocus", MessageBoxButtons.OKCancel) != DialogResult.OK) return;
        exiting = true; timer.Stop(); lifetime.Cancel(); sender.Dispose(); receiver.Dispose();
        await companion.StopAsync(); companion.Dispose(); tray.Visible = false; tray.Dispose(); timer.Dispose();
        qr.Image?.Dispose(); closed = true; Close();
    }
    private static string Status(string value) => value switch { "uploading" => "Uploading", "rendering" => "Exporting", "queued" => "Waiting", "complete" => "Ready to save", "paused" => "Paused", "failed" => "Needs attention", _ => value };
    private static TabPage Page(string title) => new(title) { BackColor = Color.White };
    private static TableLayoutPanel Column(int rows) => new() { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = rows };
    private static void Rows(TableLayoutPanel panel, int fill) { for (int i = 0; i < panel.RowCount; i++) panel.RowStyles.Add(new RowStyle(i == fill ? SizeType.Percent : SizeType.AutoSize, i == fill ? 100 : 0)); }
    private static FlowLayoutPanel Flow() => new() { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0, 5, 0, 5) };
    private static Label Label(string text, float size, bool bold = false) => new() { Text = text, AutoSize = true, MaximumSize = new Size(620, 0), Margin = new Padding(4, 6, 4, 8), Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular), ForeColor = Color.FromArgb(31, 52, 45) };
    private static Button Button(string text) => new() { Text = text, AutoSize = true, MinimumSize = new Size(90, 42), Padding = new Padding(10, 3, 10, 3), FlatStyle = FlatStyle.Flat, BackColor = Color.White, Margin = new Padding(4) };
    private static Button AudioButton(string text) => new() { Text = text, Dock = DockStyle.Fill, MinimumSize = new Size(0, 74), Margin = new Padding(4), FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(26, 130, 91), ForeColor = Color.White, Font = new Font("Segoe UI", 13, FontStyle.Bold) };
}
