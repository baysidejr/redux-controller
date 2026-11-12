// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Windows.Forms;
using Redux;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        if (args.Contains("--self-test")) { SelfTest.Run(); return 0; }
        ReceiverLog.DisableQuickEdit();
        bool testMode = args.Contains("--test-mode");
        using var instance = new Mutex(true, "Local\\ReduxControllerReceiver", out bool firstInstance);
        if (!firstInstance)
        {
            MessageBox.Show("Redux Receiver is already running. Look for its icon in the task tray.", "Redux Controller", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 1;
        }
        ApplicationConfiguration.Initialize();
        var receiver = new Receiver(testMode);
        try { receiver.StartAsync().GetAwaiter().GetResult(); }
        catch (Exception e)
        {
            ReceiverLog.WriteLine("Receiver failed to start: " + e);
            MessageBox.Show("Redux Receiver could not start.\n\n" + e.Message + "\n\nDetails are in " + ReceiverLog.Path, "Redux Controller", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }
        // Setup from the Redux front end: one elevated launch also registers the logon task.
        if (args.Contains("--start-with-windows"))
        {
            try { StartupTask.Register(); ReceiverLog.WriteLine("Start with Windows registered during setup."); }
            catch (Exception e) { ReceiverLog.WriteLine("Start with Windows could not be registered: " + e.Message); }
        }
        Application.Run(new TrayApp(receiver));
        return 0;
    }
}
