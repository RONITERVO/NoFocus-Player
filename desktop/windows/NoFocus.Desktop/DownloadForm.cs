using System.Diagnostics;

namespace NoFocus.Desktop;

internal sealed class DownloadForm : Form
{
    private readonly TextBox link = new() { PlaceholderText = "YouTube link", Dock = DockStyle.Fill };
    private readonly ComboBox format = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly Button folder = new() { Text = "Save to Music", Dock = DockStyle.Fill, AutoEllipsis = true };
    private readonly Label status = new() { Text = "", Dock = DockStyle.Fill, AutoEllipsis = true };
    private readonly Button download = new() { Text = "Download", Dock = DockStyle.Fill };
    private readonly Button cancel = new() { Text = "Close", Dock = DockStyle.Fill };
    private readonly Button showFile = new() { Text = "Show file", Dock = DockStyle.Fill, Enabled = false };
    private readonly Button play = new() { Text = "Play", Dock = DockStyle.Fill, Enabled = false };
    private readonly Button paste = new() { Text = "Paste", Dock = DockStyle.Fill };
    private CancellationTokenSource? job;
    private string destination = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
    private string? result;
    private bool closeAfterCancel;

    internal DownloadForm()
    {
        Text = "Download song";
        Font = new Font("Segoe UI", 11);
        BackColor = Color.FromArgb(244, 246, 249);
        ClientSize = new Size(510, 390);
        MinimumSize = new Size(450, 420);
        StartPosition = FormStartPosition.CenterParent;
        AutoScaleMode = AutoScaleMode.Dpi;
        TableLayoutPanel layout = new() { Dock = DockStyle.Fill, Padding = new Padding(20), ColumnCount = 2, RowCount = 7 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 75));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
        foreach (int height in new[] { 42, 42, 46, 26, 80, 52, 52 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        Controls.Add(layout);
        layout.Controls.Add(link, 0, 0);
        paste.Click += (_, _) => { try { link.Text = Clipboard.GetText(); } catch { status.Text = "Copy a YouTube link, then try again."; } };
        layout.Controls.Add(paste, 1, 0);
        format.Items.AddRange(SongDownload.FormatLabels); format.SelectedIndex = 0;
        layout.Controls.Add(format, 0, 1); layout.SetColumnSpan(format, 2);
        folder.AccessibleDescription = destination;
        folder.Click += (_, _) =>
        {
            using FolderBrowserDialog picker = new() { InitialDirectory = destination, Description = "Save songs here", UseDescriptionForTitle = true };
            if (picker.ShowDialog(this) == DialogResult.OK)
            {
                destination = picker.SelectedPath;
                folder.Text = "Save to " + Path.GetFileName(destination.TrimEnd(Path.DirectorySeparatorChar));
                folder.AccessibleDescription = destination;
            }
        };
        layout.Controls.Add(folder, 0, 2); layout.SetColumnSpan(folder, 2);
        Label hint = new() { Text = "MP3 works with most web apps.", Dock = DockStyle.Fill, ForeColor = Color.DimGray };
        layout.Controls.Add(hint, 0, 3); layout.SetColumnSpan(hint, 2);
        layout.Controls.Add(status, 0, 4); layout.SetColumnSpan(status, 2);
        layout.Controls.Add(download, 0, 5); layout.Controls.Add(cancel, 1, 5);
        layout.Controls.Add(showFile, 0, 6); layout.Controls.Add(play, 1, 6);
        download.Click += async (_, _) => await StartDownload();
        cancel.Click += (_, _) => { if (job != null) { status.Text = "Cancelling…"; job.Cancel(); } else Close(); };
        showFile.Click += (_, _) => OpenResult(true);
        play.Click += (_, _) => OpenResult(false);
        FormClosing += (_, e) =>
        {
            if (job == null) return;
            e.Cancel = true; closeAfterCancel = true; job.Cancel(); status.Text = "Cancelling…";
        };
        AcceptButton = download;
        CancelButton = cancel;
    }

    private async Task StartDownload()
    {
        if (job != null) return;
        using CancellationTokenSource current = new();
        job = current;
        result = null;
        paste.Enabled = link.Enabled = format.Enabled = folder.Enabled = download.Enabled = showFile.Enabled = play.Enabled = false;
        cancel.Text = "Cancel";
        try
        {
            result = await SongDownload.RunAsync(link.Text, SongDownload.Formats[format.SelectedIndex], destination,
                new Progress<string>(message => { if (!IsDisposed && job == current && !current.IsCancellationRequested) status.Text = message; }), current.Token);
            status.Text = "Saved: " + Path.GetFileName(result);
            showFile.Enabled = play.Enabled = true;
        }
        catch (OperationCanceledException) { status.Text = "Cancelled"; }
        catch (Exception error) { status.Text = error.Message; }
        finally
        {
            job = null;
            paste.Enabled = link.Enabled = format.Enabled = folder.Enabled = download.Enabled = true;
            cancel.Text = "Close";
            if (closeAfterCancel) Close();
        }
    }

    private void OpenResult(bool reveal)
    {
        if (result == null) return;
        try
        {
            Process.Start(reveal
                ? new ProcessStartInfo("explorer.exe", "/select,\"" + result + "\"") { UseShellExecute = true }
                : new ProcessStartInfo(result) { UseShellExecute = true });
        }
        catch (Exception error) { status.Text = error.Message; }
    }
}
