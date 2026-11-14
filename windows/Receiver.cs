// SPDX-License-Identifier: LGPL-3.0-or-later
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using HIDMaestro;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace Redux;

public readonly record struct ReceiverSnapshot(bool OutputReady, string? OutputError, bool PhonePaired, string? PhoneName, long Packets, long InputAgeMs, bool Released, string? Profile, int PairedPhones, bool MouseReady, string? MouseError);

/// <summary>
/// The receiver proper: PIN pairing and token reconnection over pinned HTTPS, the encrypted
/// full-state UDP input listener, the discovery responder, the stale-input watchdog, the
/// HIDMaestro devices (the Jaguar controller and, since 0.4, the phone mouse) and a loopback
/// control port for the Redux front end. Pairing, decoding and HID submits are serialized under
/// one lock; nothing under that lock may block on the console or the network.
/// </summary>
public sealed class Receiver
{
    public const string Version = "0.4.0";
    public const int HttpsPort = 19443, ControlPort = 19444, InputPort = 19876, DiscoveryPort = 19879;
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ReduxController");

    public string Pin { get; } = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
    public bool TestMode { get; }
    public string CertificateFingerprint { get; }

    private readonly object gate = new();
    private readonly X509Certificate2 cert;
    private readonly PairedPhones phones;
    private readonly CancellationTokenSource cancel = new();
    private readonly List<Task> loops = new();
    private InputSession? session;
    private string? phoneName;
    private string? profile;
    private HMContext? context;
    private HMController? controller;
    private HMController? mouse;
    private string? outputError;
    private string? mouseError;
    private byte mouseButtons;
    private long lastPairAttempt;
    private WebApplication? app;
    private UdpClient? udp, discovery;

    public Receiver(bool testMode)
    {
        TestMode = testMode;
        cert = LoadOrCreateCertificate();
        CertificateFingerprint = Convert.ToHexStringLower(SHA256.HashData(cert.RawData));
        phones = new PairedPhones(Path.Combine(DataDirectory, "phones.json"));
    }

    public ReceiverSnapshot Snapshot()
    {
        lock (gate)
            return new(controller != null, outputError, session != null, phoneName, session?.Packets ?? 0, session == null ? 0 : Environment.TickCount64 - session.LastFrame, session?.Released ?? true, profile, phones.Count, mouse != null, mouseError);
    }

    public static IEnumerable<(string Address, string Interface)> LocalAddresses()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback))
            foreach (var address in nic.GetIPProperties().UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IsLinkLocal(a.Address)))
                yield return (address.Address.ToString(), nic.Name);
    }
    private static bool IsLinkLocal(IPAddress address) => address.GetAddressBytes() is [169, 254, ..];

    public async Task StartAsync()
    {
        try
        {
            if (TestMode) outputError = mouseError = "Test mode: input capture only; no Windows controller.";
            else
            {
                context = new HMContext();
                if (!context.IsDriverInstalled) context.InstallDriver();
                controller = context.CreateController(ControllerProfile.Build(), "redux-player-one");
                controller.SubmitRawReport(ControllerProfile.Neutral);
            }
        }
        catch (Exception e) { outputError = e.Message; }
        // The mouse is optional: a failure here leaves the gamepad working.
        if (context != null)
        {
            try { mouse = context.CreateController(MouseProfile.Build(), "redux-mouse"); mouse.SubmitMouse(new HMMouseState()); }
            catch (Exception e) { mouseError = e.Message; }
        }

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.ListenAnyIP(HttpsPort, listen => listen.UseHttps(cert));
            options.ListenLocalhost(ControlPort);
        });
        app = builder.Build();
        app.Use(async (http, next) =>
        {
            http.Response.Headers.CacheControl = "no-store";
            if (http.Request.ContentLength > 4096) { http.Response.StatusCode = 413; return; }
            // The plain control port answers local processes only; the phone's API is HTTPS only.
            bool control = http.Connection.LocalPort == ControlPort;
            if (control != http.Request.Path.StartsWithSegments("/control") || (control && !(http.Connection.RemoteIpAddress is { } remote && IPAddress.IsLoopback(remote))))
            { http.Response.StatusCode = 404; return; }
            await next(http);
        });
        app.MapPost("/pair", Pair);
        app.MapPost("/reconnect", Reconnect);
        app.MapGet("/status", Status);
        app.MapPost("/disconnect", Disconnect);
        app.MapGet("/control/state", ControlState);
        app.MapPost("/control/profile", ControlProfile);
        app.MapPost("/control/disconnect", (HttpRequest _) => { DisconnectPhone(); return Results.Ok(new { ok = true }); });
        // Local-only inspection for automated emulator tests, never present in normal operation.
        if (TestMode) app.MapGet("/control/test-capture", (HttpRequest request) =>
        {
            lock (gate) return Results.Json(new { packets = session?.Packets ?? 0, released = session?.Released ?? true, report = session == null ? null : Convert.ToHexStringLower(session.Report), mouse = session == null ? null : Convert.ToHexStringLower(session.Mouse) });
        });

        udp = new UdpClient(new IPEndPoint(IPAddress.Any, InputPort));
        discovery = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        loops.Add(Task.Run(InputLoop));
        loops.Add(Task.Run(DiscoveryLoop));
        loops.Add(Task.Run(WatchdogLoop));
        await app.StartAsync();

        ReceiverLog.WriteLine($"REDUX CONTROLLER · Windows receiver {Version}");
        ReceiverLog.WriteLine(controller != null ? "One Redux Jaguar Controller is ready (32 buttons)." : "Controller output unavailable: " + outputError);
        ReceiverLog.WriteLine(mouse != null ? "The Redux Phone Mouse is ready (3 buttons, wheel)." : "Phone mouse unavailable: " + mouseError);
        ReceiverLog.WriteLine($"Pairing PIN: {Pin}");
        foreach (var (address, name) in LocalAddresses()) ReceiverLog.WriteLine($"PC address: {address} ({name})");
        ReceiverLog.WriteLine($"Certificate SHA256: {CertificateFingerprint}");
        ReceiverLog.WriteLine(phones.Count == 0 ? "Phone: Connections → Find PCs → enter the PIN → Pair & connect." : $"{phones.Count} paired phone(s) may reconnect without the PIN.");
        if (!TestMode) _ = Task.Run(() => ReceiverLog.WriteLine(Firewall.EnsureRules()));
    }

    public async Task StopAsync()
    {
        cancel.Cancel();
        udp?.Dispose(); discovery?.Dispose();
        await Task.WhenAll(loops);
        if (app != null) await app.StopAsync();
        lock (gate)
        {
            try { controller?.SubmitRawReport(ControllerProfile.Neutral); } catch (Exception) { }
            try { mouse?.SubmitMouse(new HMMouseState()); } catch (Exception) { }
            session?.Release(); session = null;
            controller?.Dispose(); controller = null;
            mouse?.Dispose(); mouse = null;
            context?.Dispose(); context = null;
        }
        cert.Dispose();
        ReceiverLog.WriteLine("Receiver stopped. Virtual controller and mouse removed.");
    }

    /// <summary>Release every control and forget the phone's session, from the tray or the control port.</summary>
    public void DisconnectPhone()
    {
        lock (gate)
        {
            if (session == null) return;
            Submit(new byte[17]); ForwardMouse(new byte[MouseProfile.WireSize]);
            session.Release(); session = null;
        }
        ReceiverLog.WriteLine("Phone disconnected by the PC. All controls released.");
    }

    /// <summary>Forget every paired phone; the current phone must pair with the PIN again.</summary>
    public void ForgetPhones()
    {
        DisconnectPhone();
        lock (gate) phones.Clear();
        ReceiverLog.WriteLine("Paired phones forgotten. Pair again with the PIN.");
    }

    /// <summary>The profile the phone should show, as chosen on the PC (null to let the phone choose).</summary>
    public void SetProfile(string? value)
    {
        lock (gate) profile = string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    }

    private bool Authorized(HttpRequest request)
    {
        if (session == null) return false;
        if (request.Headers["X-Redux-Session"] != Convert.ToHexStringLower(session.Id)) return false;
        var supplied = request.Headers.Authorization.ToString();
        if (!supplied.StartsWith("Bearer ", StringComparison.Ordinal)) return false;
        try { return CryptographicOperations.FixedTimeEquals(Convert.FromHexString(supplied[7..]), session.Key); }
        catch (FormatException) { return false; }
    }

    private static string PhoneNameOf(JsonElement root) =>
        root.TryGetProperty("name", out var name) && name.ValueKind == JsonValueKind.String && name.GetString() is { Length: > 0 } value ? (value.Length > 40 ? value[..40] : value) : "Phone";

    private async Task<IResult> Pair(HttpRequest request)
    {
        JsonDocument body;
        try { body = await JsonDocument.ParseAsync(request.Body); }
        catch (JsonException) { return Results.Json(new { error = "Invalid request" }, statusCode: 400); }
        using (body)
        lock (gate)
        {
            long now = Environment.TickCount64;
            if (now - lastPairAttempt < 1000) return Results.Json(new { error = "Wait a second before trying the PIN again." }, statusCode: 429);
            lastPairAttempt = now;
            if (!body.RootElement.TryGetProperty("pin", out var entry) || entry.ValueKind != JsonValueKind.String || entry.GetString() != Pin)
                return Results.Json(new { error = "Incorrect PIN. Use the six digits shown by Redux Receiver." }, statusCode: 403);
            var name = PhoneNameOf(body.RootElement);
            var token = phones.Add(name);
            return Results.Json(Open(name, $"{name} paired with the PIN. Open the Jaguar profile and test in joy.cpl.", token));
        }
    }

    private async Task<IResult> Reconnect(HttpRequest request)
    {
        JsonDocument body;
        try { body = await JsonDocument.ParseAsync(request.Body); }
        catch (JsonException) { return Results.Json(new { error = "Invalid request" }, statusCode: 400); }
        using (body)
        lock (gate)
        {
            long now = Environment.TickCount64;
            if (now - lastPairAttempt < 1000) return Results.Json(new { error = "Wait a second before reconnecting again." }, statusCode: 429);
            lastPairAttempt = now;
            byte[] token;
            try { token = body.RootElement.TryGetProperty("token", out var entry) && entry.ValueKind == JsonValueKind.String ? Convert.FromHexString(entry.GetString()!) : Array.Empty<byte>(); }
            catch (FormatException) { token = Array.Empty<byte>(); }
            var known = phones.Find(token);
            if (known == null) return Results.Json(new { error = "This phone is no longer paired with the PC. Pair with the PIN." }, statusCode: 403);
            return Results.Json(Open(known.Name, $"{known.Name} reconnected.", null));
        }
    }

    // Caller holds the gate: start a fresh input session for this phone.
    private object Open(string name, string message, byte[]? token)
    {
        Submit(new byte[17]); ForwardMouse(new byte[MouseProfile.WireSize]);
        session = new InputSession();
        phoneName = name;
        ReceiverLog.WriteLine(message);
        // mouseProtocol: this receiver accepts the 63-byte packet with the mouse section; mouseReady: Windows has the mouse device.
        return new { session = Convert.ToHexStringLower(session.Id), key = Convert.ToHexStringLower(session.Key), udpPort = InputPort, token = token == null ? null : Convert.ToHexStringLower(token), pc = Environment.MachineName, mouseProtocol = true, mouseReady = mouse != null };
    }

    private IResult Status(HttpRequest request)
    {
        lock (gate)
        {
            if (!Authorized(request)) return Results.Json(new { error = "Session expired. Reconnecting…" }, statusCode: 401);
            return Results.Json(new { outputReady = controller != null, error = outputError, mouseReady = mouse != null, mouseError, packets = session!.Packets, released = session.Released, inputAgeMs = Environment.TickCount64 - session.LastFrame, profile, pc = Environment.MachineName, testReport = TestMode ? Convert.ToHexStringLower(session.Report) : null });
        }
    }

    private IResult Disconnect(HttpRequest request)
    {
        lock (gate)
        {
            if (!Authorized(request)) return Results.Unauthorized();
            Submit(new byte[17]); ForwardMouse(new byte[MouseProfile.WireSize]);
            session?.Release(); session = null;
            ReceiverLog.WriteLine("Phone disconnected. All controls released.");
            return Results.Ok(new { ok = true });
        }
    }

    // Control port (loopback only): the Redux front end reads state and hands the phone a profile.
    private IResult ControlState(HttpRequest request)
    {
        var now = Snapshot();
        return Results.Json(new
        {
            version = Version, pin = Pin, testMode = TestMode,
            outputReady = now.OutputReady, error = now.OutputError,
            mouseReady = now.MouseReady, mouseError = now.MouseError,
            phonePaired = now.PhonePaired, phoneName = now.PhoneName, packets = now.Packets, inputAgeMs = now.InputAgeMs, released = now.Released,
            profile = now.Profile, pairedPhones = now.PairedPhones,
            addresses = LocalAddresses().Select(a => a.Address).ToArray(), httpsPort = HttpsPort,
        });
    }

    private async Task<IResult> ControlProfile(HttpRequest request)
    {
        JsonDocument body;
        try { body = await JsonDocument.ParseAsync(request.Body); }
        catch (JsonException) { return Results.Json(new { error = "Invalid request" }, statusCode: 400); }
        using (body)
        {
            var value = body.RootElement.TryGetProperty("profile", out var entry) && entry.ValueKind == JsonValueKind.String ? entry.GetString() : null;
            if (value != null && (value.Length > 64 || !value.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) return Results.Json(new { error = "Profile ids use letters, digits, - and _." }, statusCode: 400);
            SetProfile(value);
            ReceiverLog.WriteLine(value == null ? "Profile hand-off cleared." : $"Profile handed to the phone: {value}");
            return Results.Ok(new { ok = true, profile = value });
        }
    }

    // Caller holds the gate. A failing driver submit disables output rather than wedging the receiver.
    private void Submit(byte[] report)
    {
        if (controller == null) return;
        try { controller.SubmitRawReport(ControllerProfile.HidReportOf(report)); }
        catch (Exception e)
        {
            outputError = e.Message;
            try { controller.Dispose(); } catch (Exception) { }
            controller = null;
            ReceiverLog.WriteLine("Controller output failed: " + e.Message);
        }
    }

    // Caller holds the gate. Relative motion and wheel detents are sent as they arrive; button
    // state is sent on change only, so the 8 ms full-state stream does not flood the mouse.
    private void ForwardMouse(byte[] wire)
    {
        bool buttonsChanged = wire[5] != mouseButtons;
        if (!buttonsChanged && !MouseProfile.HasMotion(wire)) return;
        mouseButtons = wire[5];
        if (mouse == null) return;
        try { mouse.SubmitMouse(MouseProfile.StateOf(wire)); }
        catch (Exception e)
        {
            mouseError = e.Message;
            try { mouse.Dispose(); } catch (Exception) { }
            mouse = null;
            ReceiverLog.WriteLine("Phone mouse output failed: " + e.Message);
        }
    }

    private async Task InputLoop()
    {
        while (!cancel.IsCancellationRequested)
        {
            try
            {
                var packet = await udp!.ReceiveAsync(cancel.Token);
                lock (gate) if (session?.Accept(packet.Buffer) == true) { Submit(session.Report); ForwardMouse(session.Mouse); }
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (!cancel.IsCancellationRequested) { }
        }
    }

    private async Task DiscoveryLoop()
    {
        while (!cancel.IsCancellationRequested)
        {
            try
            {
                var packet = await discovery!.ReceiveAsync(cancel.Token);
                if (Encoding.UTF8.GetString(packet.Buffer) != "REDUX_DISCOVER_V1") continue;
                var reply = JsonSerializer.SerializeToUtf8Bytes(new { service = "redux-v1", name = Environment.MachineName, port = HttpsPort });
                await discovery.SendAsync(reply, packet.RemoteEndPoint, cancel.Token);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (!cancel.IsCancellationRequested) { }
        }
    }

    private async Task WatchdogLoop()
    {
        while (!cancel.IsCancellationRequested)
        {
            try { await Task.Delay(50, cancel.Token); }
            catch (OperationCanceledException) { break; }
            lock (gate) if (session?.ReleaseIfStale(Environment.TickCount64) == true) { Submit(session.Report); ForwardMouse(session.Mouse); }
        }
    }

    // A persistent certificate keeps the Android trust pin stable across launches.
    private static X509Certificate2 LoadOrCreateCertificate()
    {
        Directory.CreateDirectory(DataDirectory);
        var path = Path.Combine(DataDirectory, "receiver.pfx");
        if (!File.Exists(path))
        {
            using var rsa = RSA.Create(3072);
            var request = new CertificateRequest("CN=Redux Controller", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
            File.WriteAllBytes(path, generated.Export(X509ContentType.Pfx));
            // Restrict the private certificate to this Windows account.
            var file = new FileInfo(path);
            var acl = file.GetAccessControl();
            acl.SetAccessRuleProtection(true, false);
            var user = WindowsIdentity.GetCurrent().User!;
            acl.SetOwner(user);
            acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl, AccessControlType.Allow));
            file.SetAccessControl(acl);
        }
        return X509CertificateLoader.LoadPkcs12FromFile(path, null);
    }
}
