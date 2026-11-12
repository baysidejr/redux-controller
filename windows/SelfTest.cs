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
        Console.WriteLine("All receiver self-tests passed. No driver or virtual controller was installed by these tests.");
    }
    sealed class PausedConsole(ManualResetEventSlim resume) : TextWriter
    {
        public override System.Text.Encoding Encoding => System.Text.Encoding.UTF8;
        public override void Write(char value) => resume.Wait();
        public override void Write(string? value) => resume.Wait();
        public override void WriteLine(string? value) => resume.Wait();
    }
    static byte[] Encrypt(InputSession s, long seq, byte[] report)
    {
        var packet = new byte[57]; s.Id.CopyTo(packet, 0);
        BinaryPrimitives.WriteInt64LittleEndian(packet.AsSpan(16), seq);
        Span<byte> nonce = stackalloc byte[12]; s.Id.AsSpan(0, 4).CopyTo(nonce);
        packet.AsSpan(16, 8).CopyTo(nonce[4..]);
        using var aes = new AesGcm(s.Key, 16);
        aes.Encrypt(nonce, report, packet.AsSpan(24, 17), packet.AsSpan(41, 16), packet.AsSpan(0, 24));
        return packet;
    }
    static void Check(bool condition, string name)
    {
        if (!condition) throw new InvalidOperationException("FAIL: " + name);
        Console.WriteLine("PASS: " + name);
    }
}
