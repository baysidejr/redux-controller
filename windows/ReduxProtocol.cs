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

/// <summary>
/// The phone's trackpad as a second Windows device: a plain three-button relative mouse with a
/// wheel. HIDMaestro requires a mouse collection to be a device of its own, which also keeps the
/// Redux Jaguar Controller's descriptor, identity and SDL mapping exactly as they were.
/// </summary>
public static class MouseProfile
{
    /// <summary>Wire layout appended to the controller report: dx, dy (signed 16-bit counts), wheel (signed 8-bit detents), buttons (bit 0 left, 1 right, 2 middle).</summary>
    public const int WireSize = 6;
    public const byte ButtonMask = 0x07;

    public static HMProfile Build() => new HMProfileBuilder()
        .Id("redux-mouse").Name("Redux Phone Mouse").Vendor("Redux")
        .Vid(0x1209).Pid(0x5245).ProductString("Redux Phone Mouse")
        .ManufacturerString("Redux Controller Project").Connection("usb")
        .FromDescriptorBuilder(new HidDescriptorBuilder().Mouse(buttons: 3, absolute: false, wheel: true, pan: false))
        .Build();

    public static HMMouseState StateOf(ReadOnlySpan<byte> wire)
    {
        if (wire.Length != WireSize) throw new ArgumentException("A mouse report must be 6 bytes.");
        return new HMMouseState
        {
            X = BinaryPrimitives.ReadInt16LittleEndian(wire),
            Y = BinaryPrimitives.ReadInt16LittleEndian(wire[2..]),
            Wheel = (sbyte)wire[4],
            Buttons = (HMMouseButtons)(wire[5] & ButtonMask),
        };
    }

    public static bool HasMotion(ReadOnlySpan<byte> wire) => wire[0] != 0 || wire[1] != 0 || wire[2] != 0 || wire[3] != 0 || wire[4] != 0;
}

public sealed class InputSession
{
    /// <summary>Header (24) + controller report (17) + tag (16); app 0.2 and earlier send only this.</summary>
    public const int ControllerPacket = 57;
    /// <summary>The same with the 6-byte mouse section appended to the plaintext; app 0.3 sends this when the receiver advertises it.</summary>
    public const int MousePacket = ControllerPacket + MouseProfile.WireSize;

    public byte[] Id { get; } = RandomNumberGenerator.GetBytes(16);
    public byte[] Key { get; } = RandomNumberGenerator.GetBytes(32);
    public long Sequence { get; private set; }
    public long Packets { get; private set; }
    public long LastFrame { get; private set; } = Environment.TickCount64;
    public byte[] Report { get; private set; } = new byte[17];
    /// <summary>The most recent packet's mouse section; neutral for a controller-only packet.</summary>
    public byte[] Mouse { get; private set; } = new byte[MouseProfile.WireSize];
    public bool Released { get; private set; } = true;

    public bool Accept(ReadOnlySpan<byte> packet)
    {
        if ((packet.Length != ControllerPacket && packet.Length != MousePacket) || !packet[..16].SequenceEqual(Id)) return false;
        var seq = BinaryPrimitives.ReadInt64LittleEndian(packet[16..24]);
        if (seq <= Sequence) return false;
        Span<byte> nonce = stackalloc byte[12];
        Id.AsSpan(0, 4).CopyTo(nonce);
        packet[16..24].CopyTo(nonce[4..]);
        int length = packet.Length - 40;
        byte[] plaintext = new byte[length];
        try
        {
            using var aes = new AesGcm(Key, 16);
            aes.Decrypt(nonce, packet[24..(24 + length)], packet[(24 + length)..], plaintext, packet[..24]);
        }
        catch (CryptographicException) { return false; }
        // Hat 0 = neutral, 1–8 = compass directions. Unused button bits stay harmless.
        if (plaintext[16] > 8) return false;
        // Mouse buttons past the three declared ones would never reach Windows; refuse rather than guess.
        if (length > 17 && (plaintext[17 + 5] & ~MouseProfile.ButtonMask) != 0) return false;
        Sequence = seq; Packets++; LastFrame = Environment.TickCount64;
        Report = plaintext[..17];
        Mouse = length > 17 ? plaintext[17..] : new byte[MouseProfile.WireSize];
        Released = false;
        return true;
    }

    public bool ReleaseIfStale(long now)
    {
        if (Released || now - LastFrame <= 250) return false;
        Release(); return true;
    }
    public void Release() { Report = new byte[17]; Mouse = new byte[MouseProfile.WireSize]; Released = true; }
}
