// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Redux;

/// <summary>
/// The receiver's only window: a task-tray icon. Its menu shows the controller state, the
/// phone connection, the PC address and the PIN, and offers disconnect, forget phones,
/// joy.cpl, the log folder, Start with Windows and Exit. A timer polls the receiver so no
/// receiver thread ever touches the UI; transitions show as balloon notifications.
/// </summary>
public sealed partial class TrayApp : ApplicationContext
{
    private readonly Receiver receiver;
    private readonly NotifyIcon icon;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 500 };
    private readonly ToolStripMenuItem controllerItem = new() { Enabled = false };
    private readonly ToolStripMenuItem phoneItem = new() { Enabled = false };
    private readonly ToolStripMenuItem profileItem = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem addressItem = new() { Enabled = false };
    private readonly ToolStripMenuItem pinItem = new();
    private readonly ToolStripMenuItem disconnectItem = new("Disconnect phone");
    private readonly ToolStripMenuItem forgetItem = new("Forget paired phones…");
    private readonly ToolStripMenuItem startupItem = new("Start with Windows");
    private readonly Icon readyIcon = MakeIcon(Color.FromArgb(46, 160, 67));
    private readonly Icon waitingIcon = MakeIcon(Color.FromArgb(255, 106, 80));
    private readonly Icon errorIcon = MakeIcon(Color.FromArgb(120, 120, 120));
    private ReceiverSnapshot last;
    private bool first = true;

    public TrayApp(Receiver receiver)
    {
        this.receiver = receiver;
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem($"Redux Controller receiver {Receiver.Version}") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(controllerItem);
        menu.Items.Add(phoneItem);
        menu.Items.Add(profileItem);
        menu.Items.Add(addressItem);
        menu.Items.Add(pinItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(disconnectItem);
        menu.Items.Add(forgetItem);
        menu.Items.Add(new ToolStripMenuItem("Open joy.cpl (Windows game controllers)", null, (_, _) => Open("control.exe", "joy.cpl")));
        menu.Items.Add(new ToolStripMenuItem("Open log folder", null, (_, _) => Open("explorer.exe", Receiver.DataDirectory)));
        menu.Items.Add(startupItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit and remove the controller", null, (_, _) => Exit()));
        menu.Opening += (_, _) => { Refresh(); RefreshAddress(); RefreshStartup(); };
        pinItem.Click += (_, _) => CopyPin();
        disconnectItem.Click += (_, _) => receiver.DisconnectPhone();
        forgetItem.Click += (_, _) => ForgetPhones();
        startupItem.Click += (_, _) => ToggleStartup();

        icon = new NotifyIcon { ContextMenuStrip = menu, Visible = true, Icon = waitingIcon, Text = "Redux Controller" };
        icon.DoubleClick += (_, _) => ShowPin();
        timer.Tick += (_, _) => Refresh();
        Refresh();
        RefreshAddress();
        timer.Start();
        ShowPin();
    }

    private void Refresh()
    {
        var now = receiver.Snapshot();
        controllerItem.Text = now.OutputReady ? "Windows controller: Redux Jaguar Controller ready (32 buttons)" : "Windows controller unavailable: " + (now.OutputError ?? "unknown error");
        var phone = now.PhoneName ?? "Phone";
        phoneItem.Text = now.PhonePaired ? (now.Released ? $"{phone}: connected, waiting for input" : $"{phone}: connected · {now.Packets} frames") : (now.PairedPhones > 0 ? $"Phone: not connected · {now.PairedPhones} paired phone(s) may reconnect" : "Phone: not paired");
        profileItem.Visible = now.Profile != null;
        profileItem.Text = "Profile handed to the phone: " + now.Profile;
        pinItem.Text = $"Pairing PIN: {receiver.Pin}   (click to copy)";
        disconnectItem.Enabled = now.PhonePaired;
        forgetItem.Enabled = now.PairedPhones > 0;
        icon.Icon = !now.OutputReady && !receiver.TestMode ? errorIcon : now.PhonePaired ? readyIcon : waitingIcon;
        icon.Text = Tooltip(now);

        if (!first)
        {
            if (now.PhonePaired && !last.PhonePaired) Balloon($"{phone} connected", now.OutputReady ? "Controls go to the Redux Jaguar Controller." : "Connected, but Windows controller output is unavailable.", ToolTipIcon.Info);
            if (!now.PhonePaired && last.PhonePaired) Balloon($"{last.PhoneName ?? "Phone"} disconnected", "All controls released.", ToolTipIcon.Info);
            if (now.OutputError != last.OutputError && now.OutputError != null && !receiver.TestMode) Balloon("Windows controller unavailable", now.OutputError, ToolTipIcon.Warning);
        }
        last = now; first = false;
    }

    private string Tooltip(ReceiverSnapshot now)
    {
        var text = $"Redux · PIN {receiver.Pin} · " + (now.PhonePaired ? (now.PhoneName ?? "phone") + " connected" : "waiting for the phone") + (now.OutputReady ? "" : " · no controller");
        return text.Length > 63 ? text[..63] : text;
    }

    private void RefreshAddress()
    {
        var addresses = Receiver.LocalAddresses().Select(a => a.Address).ToList();
        addressItem.Text = addresses.Count == 0 ? "PC address: no network" : "PC address: " + string.Join("  ", addresses);
    }

    private void RefreshStartup()
    {
        try { startupItem.Checked = StartupTask.IsRegistered(); }
        catch (Exception) { startupItem.Checked = false; }
    }

    private void ToggleStartup()
    {
        try
        {
            if (StartupTask.IsRegistered()) { StartupTask.Remove(); Balloon("Start with Windows off", "The receiver no longer starts at sign-in.", ToolTipIcon.Info); }
            else { StartupTask.Register(); Balloon("Start with Windows on", "The receiver starts elevated at sign-in, with no UAC prompt.", ToolTipIcon.Info); }
        }
        catch (Exception e) { MessageBox.Show(e.Message, "Redux Controller", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
        RefreshStartup();
    }

    private void ForgetPhones()
    {
        var answer = MessageBox.Show("Forget every paired phone? Each phone will need the PIN to pair again.", "Redux Controller", MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2);
        if (answer == DialogResult.Yes) { receiver.ForgetPhones(); Balloon("Paired phones forgotten", $"Pair again with PIN {receiver.Pin}.", ToolTipIcon.Info); }
    }

    private void ShowPin()
    {
        var address = Receiver.LocalAddresses().Select(a => a.Address).FirstOrDefault() ?? "no network";
        Balloon($"Pairing PIN {receiver.Pin}", $"PC address {address}. Paired phones reconnect on their own; a new phone uses Connections → Find PCs → PIN.", ToolTipIcon.Info);
    }

    private void CopyPin()
    {
        try { Clipboard.SetText(receiver.Pin); Balloon("PIN copied", receiver.Pin, ToolTipIcon.Info); }
        catch (Exception) { ShowPin(); }
    }

    private void Balloon(string title, string text, ToolTipIcon kind) => icon.ShowBalloonTip(5000, title, text, kind);

    private static void Open(string file, string arguments)
    {
        try { Process.Start(new ProcessStartInfo(file, arguments) { UseShellExecute = true }); } catch (Exception) { }
    }

    private void Exit()
    {
        timer.Stop();
        icon.Visible = false;
        try { receiver.StopAsync().GetAwaiter().GetResult(); } catch (Exception e) { ReceiverLog.WriteLine("Stop failed: " + e.Message); }
        icon.Dispose();
        ExitThread();
    }

    // A drawn icon: a filled disc with an R, in the state color. No image assets to ship.
    private static Icon MakeIcon(Color fill)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);
            using var brush = new SolidBrush(fill);
            g.FillEllipse(brush, 1, 1, 30, 30);
            using var font = new Font("Segoe UI", 18, FontStyle.Bold, GraphicsUnit.Pixel);
            using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("R", font, Brushes.White, new RectangleF(0, 0, 32, 32), format);
        }
        IntPtr handle = bitmap.GetHicon();
        try { using var temporary = Icon.FromHandle(handle); return (Icon)temporary.Clone(); }
        finally { DestroyIcon(handle); }
    }

    [LibraryImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static partial bool DestroyIcon(IntPtr handle);
}
