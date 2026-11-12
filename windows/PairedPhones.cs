// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Security.Cryptography;
using System.Text.Json;

namespace Redux;

/// <summary>
/// Phones that completed PIN pairing. Each got a random 32-byte token; only its SHA-256 is
/// stored here, so the file cannot be replayed if copied. A phone that presents its token
/// gets a fresh session without the PIN, which is what makes reconnection automatic across
/// receiver restarts. Forgetting the phones (tray menu) clears the file.
/// </summary>
public sealed class PairedPhones
{
    public sealed record Entry(string Hash, string Name, DateTimeOffset Paired);
    private const int Limit = 8;
    private readonly string? path;
    private readonly List<Entry> entries = new();

    public PairedPhones(string? path)
    {
        this.path = path;
        if (path == null || !File.Exists(path)) return;
        try { entries.AddRange(JsonSerializer.Deserialize<List<Entry>>(File.ReadAllText(path)) ?? new()); }
        catch (Exception) { entries.Clear(); }
    }

    public int Count => entries.Count;
    public IReadOnlyList<Entry> Entries => entries;

    /// <summary>Mint a token for a freshly paired phone and remember its hash.</summary>
    public byte[] Add(string name)
    {
        var token = RandomNumberGenerator.GetBytes(32);
        entries.RemoveAll(e => e.Name == name);
        entries.Add(new Entry(Convert.ToHexStringLower(SHA256.HashData(token)), name, DateTimeOffset.Now));
        while (entries.Count > Limit) entries.RemoveAt(0);
        Save();
        return token;
    }

    public Entry? Find(byte[] token)
    {
        if (token.Length != 32) return null;
        var hash = SHA256.HashData(token);
        Entry? found = null;
        foreach (var entry in entries)
        {
            byte[] stored;
            try { stored = Convert.FromHexString(entry.Hash); } catch (FormatException) { continue; }
            if (CryptographicOperations.FixedTimeEquals(stored, hash)) found = entry;
        }
        return found;
    }

    public void Clear() { entries.Clear(); Save(); }

    private void Save()
    {
        if (path == null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, JsonSerializer.Serialize(entries, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception e) { ReceiverLog.WriteLine("Paired phones could not be saved: " + e.Message); }
    }
}
