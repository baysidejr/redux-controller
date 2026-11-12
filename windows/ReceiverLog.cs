// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace Redux;

/// <summary>
/// Receiver messages that never block the receiver. Lines are queued and written by one
/// background thread to the console (when one is attached, as under the dotnet host) and
/// to receiver.log in the data folder. A paused console host (text selection) therefore
/// cannot hold the input lock, which froze pairing and input in receiver 0.1.
/// </summary>
public static partial class ReceiverLog
{
    private static readonly BlockingCollection<string> Lines = new();
    public static string Path => System.IO.Path.Combine(Receiver.DataDirectory, "receiver.log");

    static ReceiverLog()
    {
        new Thread(Drain) { IsBackground = true, Name = "receiver-log" }.Start();
    }

    public static void WriteLine(string line) => Lines.Add(line);

    private static void Drain()
    {
        StreamWriter? file = null;
        try
        {
            Directory.CreateDirectory(Receiver.DataDirectory);
            if (File.Exists(Path) && new FileInfo(Path).Length > 1_000_000) File.Delete(Path);
            file = new StreamWriter(Path, append: true) { AutoFlush = true };
        }
        catch (Exception) { file = null; }
        foreach (var line in Lines.GetConsumingEnumerable())
        {
            Console.WriteLine(line);
            try { file?.WriteLine($"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}"); } catch (IOException) { }
        }
    }

    /// <summary>Turn off QuickEdit on an attached console so a click does not start a selection.</summary>
    public static void DisableQuickEdit()
    {
        var input = GetStdHandle(StdInputHandle);
        if (input == IntPtr.Zero || input == InvalidHandle || !GetConsoleMode(input, out uint mode)) return;
        SetConsoleMode(input, (mode & ~EnableQuickEditMode) | EnableExtendedFlags);
    }

    private const int StdInputHandle = -10;
    private const uint EnableQuickEditMode = 0x0040, EnableExtendedFlags = 0x0080;
    private static readonly IntPtr InvalidHandle = new(-1);

    [LibraryImport("kernel32.dll")] private static partial IntPtr GetStdHandle(int handle);
    [LibraryImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static partial bool GetConsoleMode(IntPtr handle, out uint mode);
    [LibraryImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static partial bool SetConsoleMode(IntPtr handle, uint mode);
}
