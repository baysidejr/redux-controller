// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Diagnostics;
using System.Security;
using System.Security.Principal;
using System.Text;

namespace Redux;

/// <summary>
/// "Start with Windows": a Task Scheduler logon task that runs the receiver with the highest
/// available privileges, so HIDMaestro gets the elevation it needs without a UAC prompt at
/// every sign-in. A Windows service would be the wrong shape: it runs in an isolated session
/// and could show neither a tray icon nor the PIN.
/// </summary>
public static class StartupTask
{
    public const string Name = "Redux Controller Receiver";

    public static bool IsRegistered() => Run("/Query", "/TN", Name) == 0;

    public static void Register()
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("The receiver's path is unknown.");
        if (Path.GetFileName(exe).Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Start with Windows needs the published ReduxReceiver.exe, not the dotnet host.");
        var user = WindowsIdentity.GetCurrent().Name;
        // ExecutionTimeLimit PT0S: schtasks' default would stop the receiver after three days.
        var xml = $"""
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo><Description>Starts the Redux Controller receiver at sign-in.</Description></RegistrationInfo>
              <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{Escape(user)}</UserId></LogonTrigger></Triggers>
              <Principals><Principal id="Author"><UserId>{Escape(user)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <AllowHardTerminate>false</AllowHardTerminate>
                <StartWhenAvailable>false</StartWhenAvailable>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Enabled>true</Enabled>
                <Priority>4</Priority>
              </Settings>
              <Actions Context="Author"><Exec><Command>{Escape(exe)}</Command><WorkingDirectory>{Escape(Path.GetDirectoryName(exe)!)}</WorkingDirectory></Exec></Actions>
            </Task>
            """;
        Directory.CreateDirectory(Receiver.DataDirectory);
        var path = Path.Combine(Receiver.DataDirectory, "startup-task.xml");
        File.WriteAllText(path, xml, Encoding.Unicode);
        if (Run("/Create", "/F", "/TN", Name, "/XML", path) != 0)
            throw new InvalidOperationException("Windows refused to create the start-up task. Check Task Scheduler for \"" + Name + "\".");
    }

    public static void Remove()
    {
        if (Run("/Delete", "/F", "/TN", Name) != 0)
            throw new InvalidOperationException("Windows refused to delete the start-up task \"" + Name + "\".");
    }

    private static string Escape(string value) => SecurityElement.Escape(value) ?? value;

    private static int Run(params string[] arguments)
    {
        var start = new ProcessStartInfo("schtasks.exe") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException("schtasks.exe did not start.");
        process.StandardOutput.ReadToEnd(); process.StandardError.ReadToEnd();
        process.WaitForExit();
        return process.ExitCode;
    }
}
