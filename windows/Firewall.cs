// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Diagnostics;

namespace Redux;

/// <summary>
/// Inbound Windows Firewall rules for the receiver, registered by the receiver itself each
/// time it starts (it already runs elevated). Scoped to the local subnet on private and
/// domain networks. Windows' own "allow this app" prompt is unreliable for an elevated
/// tray application, and a missed prompt left the phone unable to connect.
/// </summary>
public static class Firewall
{
    private static readonly (string Name, string Protocol, int Port)[] Rules =
    {
        ("Redux Controller receiver (TCP 19443 pairing)", "TCP", Receiver.HttpsPort),
        ("Redux Controller receiver (UDP 19876 input)", "UDP", Receiver.InputPort),
        ("Redux Controller receiver (UDP 19879 discovery)", "UDP", Receiver.DiscoveryPort),
    };

    /// <summary>Replace the receiver's rules for the running executable. Returns a one-line result for the log.</summary>
    public static string EnsureRules()
    {
        var exe = Environment.ProcessPath;
        if (exe == null || Path.GetFileName(exe).Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase))
            return "Firewall rules not registered under the dotnet host.";
        int added = 0;
        foreach (var (name, protocol, port) in Rules)
        {
            Netsh($"advfirewall firewall delete rule name=\"{name}\"");
            if (Netsh($"advfirewall firewall add rule name=\"{name}\" dir=in action=allow enable=yes profile=private,domain remoteip=localsubnet program=\"{exe}\" protocol={protocol} localport={port}") == 0) added++;
        }
        return added == Rules.Length ? "Firewall: receiver allowed from the local subnet on private networks." : $"Firewall: only {added} of {Rules.Length} rules could be registered; run Allow-Private-Network.ps1 as administrator.";
    }

    private static int Netsh(string arguments)
    {
        try
        {
            var start = new ProcessStartInfo("netsh.exe", arguments) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using var process = Process.Start(start);
            if (process == null) return -1;
            process.StandardOutput.ReadToEnd(); process.StandardError.ReadToEnd();
            process.WaitForExit(15000);
            return process.HasExited ? process.ExitCode : -1;
        }
        catch (Exception) { return -1; }
    }
}
