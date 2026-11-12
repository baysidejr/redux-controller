# Redux Controller

An Android companion app for a Pixel 9 mounted in a GameSir telescopic controller, sending physical and touchscreen inputs over local Wi-Fi to a Windows 11 PC.

Initial hardware: GameSir G8 Galileo USB-C. Second target: GameSir X5 (exact variant to confirm).

## Working prototype

The implementation is in `mobile/` (Android app) and `windows/` (Windows companion). It reuses Dish's button-layout conversion and adapts its certificate-pinning logic, with a new focused UI and a dedicated encrypted input protocol. The custom receiver replaces Satellite for this prototype so one generic HID device can expose the extra Jaguar buttons.

- Connections: controller detection/test, local PC discovery, manual address fallback, PIN pairing once per PC, then automatic reconnection with a stored token; status, connect and disconnect.
- Game Profiles: the plain Atari Jaguar keypad plus game overlays reproduced from the original Atari plates (Alien vs. Predator with three plates, Baldies, Battlemorph, Blue Lightning, Cannon Fodder, Cybermorph, DOOM, Dragon, Hover Strike, Iron Soldier, JagMIND, Trevor McFur, Wolfenstein 3D), each a multi-touch landscape play surface sending the same Windows buttons 17–28. The Redux front end can switch the phone to the game it launches.
- Windows: one 32-button generic HID controller with both sticks, independent triggers and a D-pad. Phone keys occupy buttons 17–28. The receiver runs as a task-tray application showing the PIN, address and connection state, registers its own firewall rules, starts elevated at sign-in on request, and offers a loopback control port that the Redux front end uses for status and per-game overlay hand-off.
- Safe releases: touch cancellation, focus loss, unplugging, explicit disconnect and a receiver stale-input watchdog.

See [QUICKSTART.md](QUICKSTART.md) to install and try it, [BUILDING.md](BUILDING.md) for builds/protocol details, and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for provenance and licenses.

The sections below retain the project requirements and development roadmap. Editable physical bindings and X5-specific testing remain future work. The hardware acceptance check has passed once; longer sessions and the X5 are still open.

## Main pages

### Connections

One place to see and configure everything required to play:

- **Physical controller:** detected device, connection status, selected mapping, and a button/stick/trigger test. Include guidance for the G8's Android mode when detection fails.
- **Gaming PC:** discover local receivers, select a PC, pair with its PIN, and reconnect to a saved PC. Include manual address entry as a fallback.
- **Connection health:** distinguish controller detection, PC discovery, pairing, network connection, and receiver output availability. Show actionable messages for the stage that failed.
- **Windows output:** display the active virtual controller type and whether the PC receiver reports it available. Do not claim Windows or BigPEmu has accepted inputs solely because pairing succeeded.
- **Actions:** connect/disconnect and open the active game profile.

Remember the selected PC and controller mapping. Reconnect to an already paired PC when the app opens, with a visible disconnect control. Changing pages must not interrupt input forwarding.

### Game Profiles

- List saved profiles and show the active profile.
- Ship with **Atari Jaguar** selected by default on first launch.
- Selecting Jaguar opens its landscape play screen.
- Remember an explicitly selected profile on later launches.
- Allow returning to Connections without losing the selected profile.
- Future profiles can customize touch layouts, labels, and physical input mappings.

### Jaguar play screen

- Twelve large touchscreen keys arranged as `1 2 3 / 4 5 6 / 7 8 9 / * 0 #`.
- Touch controls remain available while a physical controller is attached.
- G8 D-pad/left stick supplies movement; three configurable face buttons supply Jaguar A/B/C.
- Configurable physical bindings for Pause and Option.
- A small connection indicator and access to Connections and Game Profiles.
- Support physical and touchscreen holds simultaneously, including multiple touch pointers.
- Release cancelled touches; prevent stuck inputs on focus loss, disconnect, or profile changes.
- Use a practical landscape layout for thumb reach while the phone is mounted in the G8.
- Game-specific key labels and artwork are later additions.

## Windows input requirement

The target is **one virtual controller per player**, combining the physical controller and touchscreen controls. The Jaguar keypad must expose twelve distinct inputs in addition to the required physical controls.

A standard Xbox controller has a fixed control set. Dish's existing forwarding and a new Android UI alone do not establish support for the expanded controller. We must prototype a suitable Windows virtual-controller backend and verify its extra buttons in BigPEmu before committing to that implementation.

Keyboard output or a second virtual gamepad are alternatives, not the agreed default architecture.

## Development sequence

1. Pin and inspect the Dish Android and Satellite source revisions, licenses, and build instructions. Retain attribution and applicable notices.
2. Build a separately named/signed Android app with its own application ID and install it alongside Dish. Prove G8 input forwarding with the existing Satellite receiver.
3. Implement Connections and Game Profiles, with Jaguar as the first/default profile. Add the fixed Jaguar touch layout and physical input diagnostics.
4. Prototype the expanded single-controller output. Verify all required distinct buttons in Windows and bind physical plus touchscreen inputs to BigPEmu player one.
5. Integrate the expanded output and test simultaneous input, touch cancellation, focus loss, reconnects, and profile switching.
6. Verify the exact X5 model and add/test its controller mapping.

## First end-to-end acceptance check

On the Pixel 9, pair with the Windows 11 receiver from Connections, open the default Jaguar profile, and use physical movement, a physical action button, and a touchscreen keypad key simultaneously in BigPEmu. Windows must expose one controller for this player, and releasing or disconnecting must not leave an input held.

## Upstream references

- [Dish Android](https://github.com/TinkerNorth/dish-android)
- [Satellite receiver](https://github.com/TinkerNorth/satellite)
- [BigPEmu manual](https://www.richwhitehouse.com/jaguar/index.php?content=userman)

Status: the first end-to-end acceptance check passed on the user's Pixel 9/G8 and Windows 11 PC with receiver 0.2.0 on November 11, 2026 (see [VALIDATION.md](VALIDATION.md)). Longer play sessions, Start with Windows across a sign-in, and the GameSir X5 remain to be tested.
