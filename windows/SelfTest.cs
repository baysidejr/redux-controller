// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Buffers.Binary;
using System.Security.Cryptography;
using Redux;

static class SelfTest
{
    public static void Run()
    {
        var profile = ControllerProfile.Build();
        Check(profile.InputReportSize == 17, "17-byte custom controller report");
        Check(profile.ButtonCount == 32 && profile.StickCount == 2 && profile.TriggerCount == 2, "descriptor exposes 32 buttons, two sticks and two triggers");
        Check(profile.DeviceModelProblem == null, "custom profile has no device-model validation error");
        var neutral = ControllerProfile.Neutral;
        Check(BinaryPrimitives.ReadUInt16LittleEndian(neutral) == 32768 && neutral[16] == 255, "HID neutral centers sticks and releases hat");
        var direction = new byte[17]; direction[16] = 1;
        Check(ControllerProfile.HidReportOf(direction)[16] == 0, "wire North maps to HID North");
        var session = new InputSession();
        byte[] report = new byte[17];
        BinaryPrimitives.WriteUInt32LittleEndian(report.AsSpan(12), (1u << 0) | (1u << 16) | (1u << 27));
        report[16] = 3;
        var first = Encrypt(session, 1, report);
        Check(session.Accept(first), "authenticated full-state input accepted");
        Check(session.Report.SequenceEqual(report), "physical + first and last keypad inputs preserved together");
        Check(!session.Accept(first), "replay rejected");
        var tampered = Encrypt(session, 2, report); tampered[25] ^= 1;
        Check(!session.Accept(tampered), "tampered ciphertext rejected");
        Check(session.Sequence == 1, "invalid packet cannot advance sequence");
        Check(!session.Accept(new byte[56]), "short packet rejected");
        var other = new InputSession();
        Check(!session.Accept(Encrypt(other, 5, report)), "different session rejected");
        Check(session.ReleaseIfStale(session.LastFrame + 251), "disconnect timeout releases inputs");
        Check(session.Report.All(b => b == 0), "neutral report clears all buttons, axes and triggers");
        Check(!session.ReleaseIfStale(session.LastFrame + 300), "release is idempotent");
        Check(session.Accept(Encrypt(session, 3, report)), "input resumes after timeout");
        Check(!session.Accept(Encrypt(session, 2, report)), "out-of-order packet rejected");
        report[16] = 9;
        Check(!session.Accept(Encrypt(session, 4, report)), "invalid hat rejected");
        report[16] = 3;
        // Phone mouse (receiver 0.4): a second plain HID device, fed from six bytes appended to the controller report.
        var mouseProfile = MouseProfile.Build();
        Check(mouseProfile.InputReportSize == 6, "6-byte relative mouse report: three buttons, X, Y and wheel");
        Check(mouseProfile.DeviceModelProblem == null, "mouse profile has no device-model validation error");
        var decoded = MouseProfile.StateOf(new byte[] { 0x0A, 0x00, 0xF6, 0xFF, 0xFF, 0x03 });
        Check(decoded.X == 10 && decoded.Y == -10 && decoded.Wheel == -1 && decoded.Buttons == (HIDMaestro.HMMouseButtons.Left | HIDMaestro.HMMouseButtons.Right), "wire mouse fields decode as signed counts, detents and buttons");
        var extended = new byte[23]; report.CopyTo(extended, 0);
        BinaryPrimitives.WriteInt16LittleEndian(extended.AsSpan(17), 5); BinaryPrimitives.WriteInt16LittleEndian(extended.AsSpan(19), -3); extended[21] = 1; extended[22] = 0x01;
        Check(session.Accept(Encrypt(session, 5, extended)), "controller + mouse packet (63 bytes) accepted");
        Check(session.Report.SequenceEqual(report) && session.Mouse.SequenceEqual(extended[17..]), "controller and mouse sections preserved together");
        Check(MouseProfile.HasMotion(session.Mouse), "motion is detected in the mouse section");
        Check(session.Accept(Encrypt(session, 6, report)) && session.Mouse.All(b => b == 0), "controller-only packet from an older app still accepted and leaves the mouse neutral");
        extended[22] = 0x08;
        Check(!session.Accept(Encrypt(session, 7, extended)), "undeclared mouse button bits rejected");
        Check(!session.Accept(new byte[60]), "packet of neither length rejected");
        extended[22] = 0x01;
        Check(session.Accept(Encrypt(session, 8, extended)) && session.ReleaseIfStale(session.LastFrame + 251) && session.Mouse.All(b => b == 0), "disconnect timeout releases held mouse buttons");
        // The console host pauses every write while text is selected; receiver messages must still return at once.
        var originalOut = Console.Out;
        using var consolePaused = new ManualResetEventSlim(false);
        Console.SetOut(new PausedConsole(consolePaused));
        var queued = Task.Run(() => { ReceiverLog.WriteLine("(queued console line released by the self-test)"); ReceiverLog.WriteLine("(queued console line released by the self-test)"); });
        bool returned = queued.Wait(TimeSpan.FromSeconds(2));
        consolePaused.Set();
        Console.SetOut(originalOut);
        Check(returned, "console messages never block while the console is paused");
        // Paired phones: a token reconnects without the PIN; only its hash is stored; forgetting revokes it.
        var store = Path.Combine(Path.GetTempPath(), "redux-selftest-phones-" + Guid.NewGuid().ToString("N") + ".json");
        try
        {
            var phones = new PairedPhones(store);
            var token = phones.Add("Pixel 9");
            Check(token.Length == 32 && phones.Count == 1, "pairing mints a 32-byte reconnect token");
            Check(!File.ReadAllText(store).Contains(Convert.ToHexStringLower(token)), "the token itself is never written to disk");
            Check(new PairedPhones(store).Find(token)?.Name == "Pixel 9", "a reloaded store recognizes the token");
            var wrong = (byte[])token.Clone(); wrong[31] ^= 1;
            Check(new PairedPhones(store).Find(wrong) == null && phones.Find(new byte[5]) == null, "a wrong or short token is rejected");
            phones.Add("Pixel 9");
            Check(phones.Count == 1 && phones.Find(token) == null, "re-pairing the same phone replaces its old token");
            phones.Clear();
            Check(new PairedPhones(store).Count == 0, "forgetting phones empties the store");
        }
        finally { File.Delete(store); }
        // Self-update (receiver 0.5): the release feed names the package, versions compare numerically and a download is only trusted with its published checksum.
        var release = Updater.ParseRelease("""{"tag_name":"v0.6.0","html_url":"https://example.invalid/release","assets":[{"name":"ReduxController-0.4-debug.apk","browser_download_url":"https://example.invalid/apk"},{"name":"ReduxReceiver-0.5-win-x64.zip","browser_download_url":"https://example.invalid/zip"},{"name":"SHA256SUMS-0.6.txt","browser_download_url":"https://example.invalid/sums"}]}""");
        Check(release != null && release.Version == new Version(0, 5, 0) && release.ZipName == "ReduxReceiver-0.5-win-x64.zip" && release.ZipUrl == "https://example.invalid/zip" && release.SumsUrl == "https://example.invalid/sums" && release.Tag == "v0.6.0", "the latest release yields the receiver package, its version and the checksum file");
        Check(Updater.ParseRelease("""{"tag_name":"database","assets":[{"name":"gamedb-psx-art.zip","browser_download_url":"https://example.invalid/x"}]}""") == null, "a release without a receiver package offers no update");
        Check(Updater.ParseVersion("0.4") == new Version(0, 4, 0) && Updater.ParseVersion("0.4.1") > Updater.ParseVersion("0.4") && Updater.ParseVersion("0.10") > Updater.ParseVersion("0.9.9") && Updater.ParseVersion("v1.2.3") == new Version(1, 2, 3), "two- and three-part versions compare numerically");
        var hash = new string('a', 60) + "0f1e";
        Check(Updater.ChecksumFor(hash + " *ReduxReceiver-0.5-win-x64.zip\n" + new string('b', 64) + "  SHA256SUMS-0.6.txt\n", "ReduxReceiver-0.5-win-x64.zip") == hash, "a sha256sum line with the binary marker is matched by file name");
        Check(Updater.ChecksumFor(hash.ToUpperInvariant() + "  reduxreceiver-0.5-win-x64.zip", "ReduxReceiver-0.5-win-x64.zip") == hash, "a two-space line matches regardless of case and is returned lower-case");
        Check(Updater.ChecksumFor(hash + " *ReduxReceiver-0.4-win-x64.zip", "ReduxReceiver-0.5-win-x64.zip") == null && Updater.ChecksumFor("not a checksum file", "x.zip") == null, "a package missing from the checksum file is not trusted");
        var relaunch = Updater.RelaunchArguments(new[] { @"C:\Program Files\Redux\ReduxReceiver.exe", "--test-mode", "--pretend-version", "0.4.9", "--start-with-windows", "--update-source", "http://127.0.0.1:1/x" }, @"C:\Program Files\Redux\ReduxReceiver.exe");
        Check(relaunch.SequenceEqual(new[] { "--test-mode" }), "the restarted receiver keeps runtime flags and drops set-up and test-only ones");
        Check(Updater.RelaunchArguments(new[] { @"C:\x\ReduxReceiver.dll", "--test-mode" }, @"C:\dotnet\dotnet.exe").SequenceEqual(new[] { @"C:\x\ReduxReceiver.dll", "--test-mode" }), "under the dotnet host the assembly stays the first argument");
        Console.WriteLine("All receiver self-tests passed. No driver or virtual controller was installed by these tests.");
    }
    sealed class PausedConsole(ManualResetEventSlim resume) : TextWriter
    {
        public override System.Text.Encoding Encoding => System.Text.Encoding.UTF8;
        public override void Write(char value) => resume.Wait();
        public override void Write(string? value) => resume.Wait();
        public override void WriteLine(string? value) => resume.Wait();
    }
    static byte[] Encrypt(InputSession s, long seq, byte[] plaintext)
    {
        var packet = new byte[24 + plaintext.Length + 16]; s.Id.CopyTo(packet, 0);
        BinaryPrimitives.WriteInt64LittleEndian(packet.AsSpan(16), seq);
        Span<byte> nonce = stackalloc byte[12]; s.Id.AsSpan(0, 4).CopyTo(nonce);
        packet.AsSpan(16, 8).CopyTo(nonce[4..]);
        using var aes = new AesGcm(s.Key, 16);
        aes.Encrypt(nonce, plaintext, packet.AsSpan(24, plaintext.Length), packet.AsSpan(24 + plaintext.Length, 16), packet.AsSpan(0, 24));
        return packet;
    }
    static void Check(bool condition, string name)
    {
        if (!condition) throw new InvalidOperationException("FAIL: " + name);
        Console.WriteLine("PASS: " + name);
    }
}
