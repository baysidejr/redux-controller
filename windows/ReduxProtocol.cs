// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Buffers.Binary;
using System.Security.Cryptography;
using HIDMaestro;

namespace Redux;

public static class ControllerProfile
{
    public static byte[] HidReportOf(ReadOnlySpan<byte> wire)
    {
        if (wire.Length != 17) throw new ArgumentException("A controller report must be 17 bytes.");
        var hid = wire.ToArray();
        for (int offset = 0; offset < 8; offset += 2)
        {
            int value = BinaryPrimitives.ReadInt16LittleEndian(wire[offset..]);
            BinaryPrimitives.WriteUInt16LittleEndian(hid.AsSpan(offset), (ushort)(value + 32768));
        }
        hid[16] = wire[16] == 0 ? (byte)255 : (byte)(wire[16] - 1);
        return hid;
    }

    public static byte[] Neutral => HidReportOf(new byte[17]);

    // Prototype VID/PID: local identity only, not an assigned USB product identity.
    public static HMProfile Build() => new HMProfileBuilder()
        .Id("redux-jaguar").Name("Redux Jaguar Controller").Vendor("Redux")
        .Vid(0x1209).Pid(0x5244).ProductString("Redux Jaguar Controller")
        .ManufacturerString("Redux Controller Project").Connection("usb")
        .Descriptor(new HidDescriptorBuilder().Gamepad()
            .AddStick("Left", 16).AddStick("Right", 16)
            .AddTrigger("Left", 16).AddTrigger("Right", 16)
            .AddButtons(32).AddHat().Build())
        .InputReportSize(17).Build();
}

public sealed class InputSession
{
    public byte[] Id { get; } = RandomNumberGenerator.GetBytes(16);
    public byte[] Key { get; } = RandomNumberGenerator.GetBytes(32);
    public long Sequence { get; private set; }
    public long Packets { get; private set; }
    public long LastFrame { get; private set; } = Environment.TickCount64;
    public byte[] Report { get; private set; } = new byte[17];
    public bool Released { get; private set; } = true;

    public bool Accept(ReadOnlySpan<byte> packet)
    {
        if (packet.Length != 57 || !packet[..16].SequenceEqual(Id)) return false;
        var seq = BinaryPrimitives.ReadInt64LittleEndian(packet[16..24]);
        if (seq <= Sequence) return false;
        Span<byte> nonce = stackalloc byte[12];
        Id.AsSpan(0, 4).CopyTo(nonce);
        packet[16..24].CopyTo(nonce[4..]);
        byte[] plaintext = new byte[17];
        try
        {
            using var aes = new AesGcm(Key, 16);
            aes.Decrypt(nonce, packet[24..41], packet[41..57], plaintext, packet[..24]);
        }
        catch (CryptographicException) { return false; }
        // Hat 0 = neutral, 1–8 = compass directions. Unused button bits stay harmless.
        if (plaintext[16] > 8) return false;
        Sequence = seq; Packets++; LastFrame = Environment.TickCount64;
        Report = plaintext; Released = false;
        return true;
    }

    public bool ReleaseIfStale(long now)
    {
        if (Released || now - LastFrame <= 250) return false;
        Release(); return true;
    }
    public void Release() { Report = new byte[17]; Released = true; }
}
