# Prototype validation

Validated on November 11, 2026 (America/New_York).

## Passed

- Android debug APK build and lint (zero lint errors; remaining warnings concern English-only strings, deliberate first-contact certificate persistence, and the prototype target SDK).
- Four Android unit tests: coherent physical/keypad report packing, independent touch release, complete neutralization, and Dish button/hat conversion.
- Windows Release build and self-contained x64 publish with no build warnings or errors.
- Eighteen receiver self-test assertions: custom descriptor/report size and controls, device-model validation, HID centers/hat conversion, authenticated input, simultaneous button preservation, replay/tamper/short/wrong-session rejection, counter integrity, timeout release, resumed input, out-of-order rejection and invalid hat rejection.
- Pixel 9-sized Android emulator install and launch, with no Android runtime crash observed.
- Portrait Connections and Game Profiles, and landscape Jaguar screen inspected from actual emulator screenshots. Corrected margins and clipped key labels.
- Android emulator paired over HTTPS with the real .NET receiver in **test mode** and sent thousands of AES-GCM-protected full-state UDP reports.
- All twelve actual touchscreen controls independently reached the receiver as buttons 17–28; each released correctly.
- Sending Home during an active touch produced a neutral receiver state.
- Force-stopping the Android app caused the receiver watchdog to release its state.
- On the user's Windows 11 PC, the elevated receiver installed HIDMaestro and created the **Redux Jaguar Controller** device (status OK in Device Manager, VID 1209 / PID 5244).
- Nineteenth self-test assertion: receiver console messages return at once while the console host is paused.

## Receiver 0.3 and app 0.2 (reconnect, overlays, Redux front end)

- Receiver: self-registering firewall rules, paired-phone token store (`/reconnect`), loopback control port 19444, profile hand-off; six new self-test assertions cover the token store (25 in total). Release build with zero warnings.
- Android: automatic reconnection with the stored token, fifteen overlay plates across fourteen profiles, a custom multi-touch keypad view with slide-off release, Follow-the-PC profile switching; unit tests cover profile parsing, key geometry and report packing.
- Redux front end (`C:\dev\polymega-redux`): built-in SDL mapping for the receiver's device (verified live against the running receiver: SDL reported the device as a gamepad only after the mapping), Settings → Controllers → Phone controller menu, receiver auto-start option and per-game overlay hand-off on Jaguar launches; its full test suite passes with the new tests.
- Run on the user's hardware the same evening: receiver 0.3.0 registered its three firewall rules at start, the Pixel 9 paired with the PIN, was disconnected and reconnected with its token without a PIN (receiver log 22:58), and streamed input with a 16 ms input age; the control port reported the full state; Redux's SDL layer listed the device as a mapped gamepad in player slot 0 with Guide. The user confirmed pairing, reconnection and the phone overlays worked; the first Redux attempt used the previously installed Redux build, which predates the mapping; after the installed copy was updated in place to the new 0.10.9 build, the user confirmed that the Redux menus respond to the controller.
- Receiver 0.3.1 bundled in the Redux release: the user ran Redux's one-click set-up from the installed copy and confirmed the pairing PIN appears in Redux (Controllers page, menu and pop-up).
- Still open: a longer session across a receiver restart and a phone sleep, and the GameSir X5.

## Receiver 0.2 (tray application)

- Release build with zero warnings, all nineteen self-test assertions passing under the `dotnet` host, and a self-contained x64 publish.
- The receiver logic moved unchanged into `Receiver.cs`; the console window is replaced by a task-tray icon, menu and notifications, a log file in `%LOCALAPPDATA%\ReduxController`, and an optional elevated logon task.
- Run elevated on the user's Windows 11 PC on November 11, 2026: the Redux Jaguar Controller device came up, the log recorded start-up and the phone's pairing 30 seconds later, discovery answered, and unauthenticated status probes returned 401 in about 10 ms while a phone was paired, so the receiver no longer blocks. Start with Windows, Disconnect phone and Exit remain to be exercised by the user.

## Found and fixed on hardware

- First real pairing attempt timed out. The receiver window was in classic-console text-selection mode (QuickEdit), which pauses every console write; the receiver printed inside its input lock and froze all pairing, status and input handling. Receiver 0.1.1 queues console output on a background thread and disables QuickEdit at startup, so a click in the window can no longer stall the receiver. Pressing Esc in the window unfreezes an older receiver.

Receiver test mode does **not** install a driver or create a Windows virtual controller; its phone status explicitly identifies this. It validates the real phone-side code, network path and receiver decoding without implying hardware-output validation.

## Passed on hardware (user-reported, November 11, 2026)

With receiver 0.2.0 on the Windows 11 PC and the 0.1 app on the Pixel 9 in the G8, the user ran the QUICKSTART end-to-end check and reported that everything worked: pairing, the physical controls and the twelve touch keys reaching the one Redux Jaguar Controller, and play in BigPEmu. This is the first end-to-end acceptance check from the README.

## Still to validate on hardware

- Longer play sessions: physical reach, Wi-Fi responsiveness, battery use, Wi-Fi loss and receiver restart while playing.
- Start with Windows (elevated logon task) across a sign-out and sign-in.
- The second controller's exact GameSir X5 model and compatibility.

The user's earlier Dish test demonstrated that their G8/Pixel can forward input over Wi-Fi to Windows. It did not test this new app or its expanded HID backend.
