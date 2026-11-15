// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Redux;

public enum UpdateStage { Idle, Checking, UpToDate, Downloading, Ready, Installing, Failed }

public readonly record struct UpdateSnapshot(UpdateStage Stage, string? Latest, string? Error, DateTime? Checked, bool Automatic);

public sealed record ReleaseInfo(Version Version, string ZipName, string ZipUrl, string? SumsUrl, string Tag, string PageUrl);

/// <summary>
/// Self-update from the GitHub releases of redux-controller. The latest release names the receiver
/// package <c>ReduxReceiver-&lt;version&gt;-win-x64.zip</c> beside a <c>SHA256SUMS</c> file; a newer
/// package is downloaded into the data folder, verified against that checksum, unpacked, and then
/// installed by a small PowerShell helper that waits for this process to exit, copies the new files
/// over the install folder and starts the receiver again (through the logon task when it exists, so
/// it comes back elevated without a UAC prompt). Nothing replaces a file while the receiver runs.
/// </summary>
public sealed partial class Updater
{
    public const string DefaultSource = "https://api.github.com/repos/baysidejr/redux-controller/releases/latest";
    public static readonly TimeSpan Interval = TimeSpan.FromHours(6);

    private readonly object gate = new();
    private readonly Version current;
    private readonly string source;
    private readonly string directory;
    private readonly string settingsPath;
    private readonly HttpClient http;
    private UpdateStage stage;
    private ReleaseInfo? latest;
    private string? staged;
    private string? error;
    private DateTime? checkedAt;
    private bool automatic = true;

    /// <summary>Whether the tray schedules checks on its own; off for test-mode runs against the real GitHub.</summary>
    public bool Background { get; init; } = true;

    public Updater(string currentVersion, string source, string dataDirectory)
    {
        current = ParseVersion(currentVersion);
        this.source = source;
        directory = Path.Combine(dataDirectory, "updates");
        settingsPath = Path.Combine(directory, "updater.json");
        http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("ReduxReceiver/" + currentVersion);
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        Load();
        ReportHelperLog();
        CleanUp();
    }

    // The receiver keeps receiver.log open, so the helper writes its own log, which the next start folds into receiver.log.
    private string HelperLogPath => Path.Combine(directory, "update-helper.log");

    private void ReportHelperLog()
    {
        try
        {
            if (!File.Exists(HelperLogPath)) return;
            foreach (var line in File.ReadAllLines(HelperLogPath).Where(l => l.Length > 0)) ReceiverLog.WriteLine("Previous " + line);
            File.Delete(HelperLogPath);
        }
        catch (Exception) { }
    }

    public Version Current => current;

    public bool Automatic
    {
        get { lock (gate) return automatic; }
        set { lock (gate) { automatic = value; Save(); } }
    }

    public UpdateSnapshot Snapshot()
    {
        lock (gate) return new(stage, latest?.Version.ToString(3), error, checkedAt, automatic);
    }

    /// <summary>Check the latest release and, when it is newer, download and verify it in the background. Ignored while busy.</summary>
    public void Check()
    {
        lock (gate)
        {
            if (stage is UpdateStage.Checking or UpdateStage.Downloading or UpdateStage.Installing) return;
            stage = UpdateStage.Checking; error = null;
        }
        _ = Task.Run(CheckAndFetch);
    }

    private async Task CheckAndFetch()
    {
        try
        {
            var release = ParseRelease(await http.GetStringAsync(source)) ?? throw new InvalidOperationException("The latest release has no receiver package.");
            lock (gate) { latest = release; checkedAt = DateTime.UtcNow; Save(); }
            if (release.Version <= current)
            {
                Set(UpdateStage.UpToDate);
                ReceiverLog.WriteLine($"Receiver {current.ToString(3)} is up to date (latest release {release.Tag}).");
                return;
            }
            var folder = Path.Combine(directory, "staged-" + release.Version.ToString(3));
            if (File.Exists(Path.Combine(folder, "ReduxReceiver.exe")))
            {
                lock (gate) staged = folder;
                Set(UpdateStage.Ready);
                return;
            }
            Set(UpdateStage.Downloading);
            ReceiverLog.WriteLine($"Receiver {release.Version.ToString(3)} is available ({release.Tag}); downloading {release.ZipName}.");
            if (release.SumsUrl == null) throw new InvalidOperationException("The release has no SHA256SUMS file, so the download cannot be verified.");
            var expected = ChecksumFor(await http.GetStringAsync(release.SumsUrl), release.ZipName) ?? throw new InvalidOperationException($"The release's SHA256SUMS does not list {release.ZipName}.");
            Directory.CreateDirectory(directory);
            var zip = Path.Combine(directory, release.ZipName);
            using (var response = await http.GetAsync(release.ZipUrl, HttpCompletionOption.ResponseHeadersRead))
            {
                response.EnsureSuccessStatusCode();
                await using var file = File.Create(zip);
                await response.Content.CopyToAsync(file);
            }
            string actual;
            await using (var file = File.OpenRead(zip)) actual = Convert.ToHexStringLower(await SHA256.HashDataAsync(file));
            if (actual != expected) { File.Delete(zip); throw new InvalidOperationException("The downloaded package did not match its published checksum and was deleted."); }
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
            ZipFile.ExtractToDirectory(zip, folder);
            File.Delete(zip);
            if (!File.Exists(Path.Combine(folder, "ReduxReceiver.exe"))) throw new InvalidOperationException("The package does not contain ReduxReceiver.exe.");
            lock (gate) staged = folder;
            Set(UpdateStage.Ready);
            ReceiverLog.WriteLine($"Receiver {release.Version.ToString(3)} downloaded and verified (SHA256 {actual[..12]}…); ready to install.");
        }
        catch (Exception e)
        {
            lock (gate) { stage = UpdateStage.Failed; error = Describe(e); }
            ReceiverLog.WriteLine("Update check failed: " + Describe(e));
        }
    }

    /// <summary>
    /// Start the helper that applies the staged build once this process has exited, then let the
    /// caller exit the receiver. Returns the helper's log line target for the tray.
    /// </summary>
    public void Install()
    {
        string folder;
        lock (gate)
        {
            if (stage != UpdateStage.Ready || staged == null) throw new InvalidOperationException("No update is ready to install.");
            stage = UpdateStage.Installing; folder = staged;
        }
        var target = Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory);
        var process = Environment.ProcessPath ?? throw new InvalidOperationException("The receiver's own path is unknown.");
        var arguments = RelaunchArguments(Environment.GetCommandLineArgs(), process);
        var script = Path.Combine(directory, "apply-update.ps1");
        File.WriteAllText(script, HelperScript);
        var start = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden, WorkingDirectory = directory };
        foreach (var argument in new[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script,
                                         "-ProcessId", Environment.ProcessId.ToString(), "-Source", folder, "-Target", target, "-Log", HelperLogPath })
            start.ArgumentList.Add(argument);
        bool viaTask = false;
        try { viaTask = StartupTask.IsRegistered(); } catch (Exception) { }
        if (viaTask) { start.ArgumentList.Add("-Task"); start.ArgumentList.Add(StartupTask.Name); }
        else
        {
            start.ArgumentList.Add("-Exe"); start.ArgumentList.Add(process);
            // -File passes parameters literally, so the list travels as one string split on '|', a character Windows file names cannot contain.
            if (arguments.Count > 0) { start.ArgumentList.Add("-Arguments"); start.ArgumentList.Add(string.Join("|", arguments)); }
        }
        Process.Start(start);
        ReceiverLog.WriteLine($"Installing receiver update from {folder}; the receiver restarts in a moment" + (viaTask ? " through the logon task." : "."));
    }

    /// <summary>The arguments the restarted receiver gets: the runtime flags, minus set-up and test-only ones. Under the dotnet host the first argument is the assembly itself.</summary>
    public static IReadOnlyList<string> RelaunchArguments(string[] commandLine, string processPath)
    {
        var keep = new List<string>();
        bool dotnetHost = Path.GetFileName(processPath).Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase);
        for (int i = dotnetHost ? 0 : 1; i < commandLine.Length; i++)
        {
            var argument = commandLine[i];
            if (argument == "--start-with-windows") continue;
            if (argument is "--pretend-version" or "--update-source") { i++; continue; }
            keep.Add(argument);
        }
        return keep;
    }

    /// <summary>The latest release's receiver package: the highest ReduxReceiver-&lt;version&gt;-win-x64.zip asset plus the SHA256SUMS asset.</summary>
    public static ReleaseInfo? ParseRelease(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array) return null;
        Version? version = null; string? zipName = null, zipUrl = null, sumsUrl = null;
        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
            var url = asset.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
            if (url == null) continue;
            var match = ZipName().Match(name);
            if (match.Success)
            {
                var candidate = ParseVersion(match.Groups[1].Value);
                if (version == null || candidate > version) { version = candidate; zipName = name; zipUrl = url; }
            }
            else if (SumsName().IsMatch(name)) sumsUrl = url;
        }
        if (version == null) return null;
        var tag = root.TryGetProperty("tag_name", out var t) ? t.GetString() ?? "" : "";
        var page = root.TryGetProperty("html_url", out var h) ? h.GetString() ?? "" : "";
        return new(version, zipName!, zipUrl!, sumsUrl, tag, page);
    }

    /// <summary>"0.4" and "0.4.0" are the same version; missing parts count as zero rather than -1.</summary>
    public static Version ParseVersion(string text)
    {
        var parts = text.Trim().TrimStart('v').Split('.');
        var numbers = new int[3];
        for (int i = 0; i < 3; i++) numbers[i] = i < parts.Length && int.TryParse(parts[i], out var value) ? value : 0;
        return new Version(numbers[0], numbers[1], numbers[2]);
    }

    /// <summary>The lower-case SHA-256 listed for a file in a SHA256SUMS text ("hash *name" or "hash  name"), or null.</summary>
    public static string? ChecksumFor(string sums, string fileName)
    {
        foreach (var raw in sums.Split('\n'))
        {
            var line = raw.Trim();
            int space = line.IndexOf(' ');
            if (space != 64) continue;
            var hash = line[..64];
            if (!hash.All(char.IsAsciiHexDigit)) continue;
            var name = line[64..].Trim().TrimStart('*').Trim();
            if (name.Equals(fileName, StringComparison.OrdinalIgnoreCase)) return hash.ToLowerInvariant();
        }
        return null;
    }

    private void Set(UpdateStage value) { lock (gate) stage = value; }

    private static string Describe(Exception e) => e switch
    {
        HttpRequestException http => "GitHub could not be reached: " + (http.StatusCode is { } code ? $"HTTP {(int)code}" : http.Message),
        TaskCanceledException => "The download timed out.",
        _ => e.Message,
    };

    private void Load()
    {
        try
        {
            if (!File.Exists(settingsPath)) return;
            using var document = JsonDocument.Parse(File.ReadAllText(settingsPath));
            if (document.RootElement.TryGetProperty("automatic", out var a) && a.ValueKind is JsonValueKind.True or JsonValueKind.False) automatic = a.GetBoolean();
            if (document.RootElement.TryGetProperty("checked", out var c) && c.ValueKind == JsonValueKind.String && DateTime.TryParse(c.GetString(), null, System.Globalization.DateTimeStyles.RoundtripKind, out var when)) checkedAt = when;
        }
        catch (Exception) { }
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(directory);
            File.WriteAllText(settingsPath, JsonSerializer.Serialize(new { automatic, @checked = checkedAt?.ToString("O") }));
        }
        catch (Exception) { }
    }

    /// <summary>Remove staged builds that are not newer than the running receiver and any half-downloaded package.</summary>
    private void CleanUp()
    {
        try
        {
            if (!Directory.Exists(directory)) return;
            foreach (var folder in Directory.GetDirectories(directory, "staged-*"))
                if (ParseVersion(Path.GetFileName(folder)["staged-".Length..]) <= current) Directory.Delete(folder, true);
            foreach (var zip in Directory.GetFiles(directory, "*.zip")) File.Delete(zip);
        }
        catch (Exception e) { ReceiverLog.WriteLine("Update folder clean-up skipped: " + e.Message); }
    }

    [GeneratedRegex(@"^ReduxReceiver-(\d+(?:\.\d+){1,2})-win-x64\.zip$")] private static partial Regex ZipName();
    [GeneratedRegex(@"^SHA256SUMS(?:-[\w.-]+)?\.txt$")] private static partial Regex SumsName();

    private const string HelperScript = """
        param([int]$ProcessId, [string]$Source, [string]$Target, [string]$Log, [string]$Task, [string]$Exe, [string]$Arguments)
        function Note($message) { try { Add-Content -LiteralPath $Log -Value ("{0:yyyy-MM-dd HH:mm:ss} update helper: {1}" -f (Get-Date), $message) } catch { } }
        try {
            $receiver = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
            if ($receiver) {
                Note "waiting for the receiver (process $ProcessId) to exit"
                if (-not $receiver.WaitForExit(120000)) { Note "the receiver did not exit within two minutes; the update was not applied"; exit 1 }
            }
            Start-Sleep -Milliseconds 500
            $copy = Start-Process -FilePath robocopy.exe -ArgumentList @("`"$Source`"", "`"$Target`"", '/E', '/IS', '/IT', '/R:20', '/W:1', '/NP', '/NJH', '/NJS', '/NFL', '/NDL') -Wait -PassThru -WindowStyle Hidden
            if ($copy.ExitCode -ge 8) { Note "robocopy reported failures (exit $($copy.ExitCode)); starting whatever is in place" }
            else { Note "new files copied into $Target"; Remove-Item -LiteralPath $Source -Recurse -Force -ErrorAction SilentlyContinue }
            if ($Task) { & schtasks.exe /Run /TN $Task | Out-Null; Note "receiver started through the task '$Task'" }
            elseif ($Exe) {
                if ($Arguments) {
                    $list = @($Arguments -split '\|' | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
                    Start-Process -FilePath $Exe -ArgumentList $list -WorkingDirectory $Target
                } else { Start-Process -FilePath $Exe -WorkingDirectory $Target }
                Note "receiver started: $Exe"
            }
        } catch { Note "failed: $_" }
        """;
}
