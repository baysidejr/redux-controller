# Build and test

## Android

Install JDK 21 and the Android SDK (platform 37.0 and build-tools 36.0.0). Set `ANDROID_HOME` or add an escaped `sdk.dir` entry to `mobile/local.properties`. The Gradle wrapper downloads Gradle 9.8.2.

```powershell
cd mobile
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `mobile/app/build/outputs/apk/debug/app-debug.apk`.

The package is `dev.redux.controller`, so it installs alongside Dish. The initial prototype uses Android's debug signing key; preserve that key for updates to an existing installation. Do not treat this development signing configuration as a production release setup.

Code layout: `MainActivity.kt` (Connections, Game Profiles and the play screen), `ReduxLink.kt` (discovery, PIN pairing, token reconnection, status polling, the 8 ms input sender), `ControllerState.kt` (the 17-byte report), `OverlayKeypad.kt` (the twelve-key play surface: artwork, multi-touch, slide-off release), `Profiles.kt` (profile model, `profiles.json` loader and the key geometry per plate template), `dish/GamepadButtonLayouts.kt` (Dish's button/hat conversion). Overlay artwork lives in `app/src/main/assets/overlays`.

### Game profiles

`assets/profiles.json` lists every profile: an id shared with the Redux front end (`jaguar`, `jaguar-avp`, `jaguar-doom`, …), a name, and one or more plates. A plate has artwork (`image`, or none for the drawn default keypad), a geometry `template` (`render` for the 462×338 crops of the community renders, `scan` for the AtariAge 400-pixel scans, `drawn` for the default) and twelve labels in keypad order 1–9, *, 0, #. Every plate sends the same Windows buttons 17–28; the artwork only changes what the player sees. To add a game: drop its plate under `assets/overlays`, add an entry to `profiles.json`, and (optionally) add its title pattern to the Redux front end's `redux_controller.py` so a launch switches the phone automatically.

## Windows

Install .NET SDK 10. The pinned HIDMaestro SDK assembly and notices are in `windows/lib`.

```powershell
dotnet build windows/ReduxReceiver.csproj -c Release
dotnet windows/bin/Release/net10.0-windows10.0.26100.0/ReduxReceiver.dll --self-test
dotnet publish windows/ReduxReceiver.csproj -c Release -r win-x64 --self-contained true -o dist/windows
```

Run `ReduxReceiver.exe` from the published directory. It is a task-tray application with no console window; its executable requests administrator rights because HIDMaestro requires them to create the virtual controller. On first use it installs HIDMaestro if the driver is missing. The receiver does not require Satellite.

On every start the receiver registers its own inbound Windows Firewall rules (TCP 19443, UDP 19876 and 19879, limited to the local subnet on private and domain networks) for the executable that is running, so no firewall prompt has to be answered. `Allow-Private-Network.ps1` remains as a manual fallback.

The tray menu shows the Windows controller state, the connected phone and its frame count, the profile handed to the phone, the PC address and the PIN (click to copy), and offers **Disconnect phone**, **Forget paired phones**, **Open joy.cpl**, **Open log folder**, **Start with Windows** and **Exit**. **Start with Windows** registers a Task Scheduler logon task (`Redux Controller Receiver`) that runs the published executable with the highest available privileges and no execution time limit; a Windows service would be the wrong shape because it runs in an isolated session and cannot show a tray icon or the PIN.

Receiver messages are queued and written by one background thread to `%LOCALAPPDATA%\ReduxController\receiver.log` and, when a console is attached (under the `dotnet` host), to that console; QuickEdit is disabled on an attached console. Nothing under the receiver lock may block on the console or the network: a paused console froze pairing and input in receiver 0.1. Keep every message on `ReceiverLog.WriteLine`.

The self-test runs without administrator privileges or a device. A development-only `--test-mode` option runs pairing and input capture without touching the virtual controller driver; status explicitly reports that no Windows output is present in this mode. Both are run through the `dotnet` host, which supplies the console the WinExe lacks.

Code layout: `Receiver.cs` (pairing and reconnection API, UDP input, discovery, watchdog, HIDMaestro device, control port), `PairedPhones.cs` (reconnect-token store), `Firewall.cs`, `ReduxProtocol.cs` (HID profile, session decoding), `TrayApp.cs` (tray icon and menu), `StartupTask.cs` (logon task), `ReceiverLog.cs`, `SelfTest.cs`, `Program.cs`.

## Input protocol

- Local discovery: UDP 19879, request `REDUX_DISCOVER_V1`, JSON response with `service=redux-v1`, name and HTTPS port.
- Pair/reconnect/status/disconnect: HTTPS 19443. `POST /pair` with the six-digit PIN displayed on the PC returns a session, its key, the UDP port, the PC name and a 32-byte reconnect **token**. `POST /reconnect` with that token returns a fresh session without the PIN; the receiver stores only the token's SHA-256 (`%LOCALAPPDATA%\ReduxController\phones.json`, at most eight phones), and **Forget paired phones** revokes them all. Pair and reconnect attempts are rate-limited to one per second. The Android client pins the first receiver certificate and refuses changes until the user explicitly resets trust, which also discards the token.
- `GET /status` (session headers) reports output readiness, packet count, input age, the PC name and `profile`: the profile id the PC asked the phone to show, or null.
- Control port: plain HTTP on 127.0.0.1:19444, local processes only. `GET /control/state` returns version, PIN, addresses, controller and phone state; `POST /control/profile` with `{"profile": "jaguar-avp"}` (or null) hands the phone a profile; `POST /control/disconnect` releases the phone. The Redux front end uses this port; the phone's API is never served on it.
- Input: UDP 19876. Each packet is 57 bytes: random session ID (16), increasing signed-positive little-endian counter (8), AES-256-GCM ciphertext (17), authentication tag (16).
- AES nonce: first four bytes of session ID plus counter (8); the complete 24-byte header is authenticated additional data. Each session mints a fresh 32-byte key and random ID. Counter reuse and out-of-order packets are rejected.
- A complete neutral or held state is sent about every 8 ms while connected; no press/release delta can remain lost indefinitely. Controls release when the phone loses focus. The receiver neutralizes stale input after 250 ms, with a watchdog check every 50 ms.
- The 17-byte plaintext contains signed LX, LY, RX, RY (8 bytes), unsigned LT/RT (4), button bitmap (4), and hat (1). All multibyte fields are little endian. Hat 0 means neutral; 1–8 run clockwise from North.
- The Windows adapter shifts signed stick values to the HID descriptor's unsigned center and converts the hat to 0–7/255. No additional controller is created for touch input.

Network control requests run separately from the regular input sender so discovery and status requests do not pause gameplay. While the app is visible and a token is saved, the link retries a reconnect every 3 seconds (10 seconds after three failures) until the receiver answers; an explicit Disconnect stops that until Connect. Android application state is protected with a single lock for coherent snapshots. The receiver serializes pairing, decoding and HID submits under its own lock.

## Button map

| Windows button | Physical / touch control |
|---|---|
| 1–4 | A, B, X, Y |
| 5–6 | LB, RB |
| 7–8 | View, Menu |
| 9–10 | Left/right stick click |
| 11 | Home, if Android delivers it to the app |
| 12–16 | Reserved |
| 17–25 | Jaguar 1–9 |
| 26 | Jaguar * |
| 27 | Jaguar 0 |
| 28 | Jaguar # |
| 29–32 | Reserved |

## SDL and the Redux front end

The device is a generic HID gamepad (VID 0x1209, PID 0x5244), so SDL-based applications need a mapping before they treat it as a gamepad. DirectInput orders its axes X, Y, Z, Rx, Ry, Rz, and the descriptor puts the right stick on Z/Rz and the triggers on Rx/Ry, which gives:

```
03000000091200004452000000000000,Redux Jaguar Controller,a:b0,b:b1,x:b2,y:b3,leftshoulder:b4,rightshoulder:b5,back:b6,start:b7,leftstick:b8,rightstick:b9,guide:b10,leftx:a0,lefty:a1,rightx:a2,righty:a5,lefttrigger:a3,righttrigger:a4,dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,platform:Windows,
```

The Redux front end ships this mapping built in (`redux/redux_controller.py`), so its menus and integrated cores accept the controller, and it uses the control port to show pairing state under Settings → Controllers → Phone controller and to hand the phone the overlay for a launched Jaguar game. Other SDL applications can take the line through the `SDL_GAMECONTROLLERCONFIG` environment variable. BigPEmu binds the raw device directly and needs no mapping.

## Remaining hardware validation

Validate on the actual Pixel 9/G8 and an elevated Windows receiver: all 12 touch keys on each overlay, simultaneous touch and physical holds, all axes and both triggers, unplug/replug, receiver restart (the phone should reconnect without a PIN) and Wi-Fi loss. The exact GameSir X5 variant remains unconfirmed and untested.

Generic HID output suits applications that support joystick/DirectInput input. Games that accept only XInput will require a future Xbox output profile with a smaller button set.
