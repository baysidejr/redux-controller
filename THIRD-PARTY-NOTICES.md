# Attribution and source provenance

Redux Controller is an independent modified project, not an official Dish or GameSir release.

## Dish Android — TinkerNorth

- Source: https://github.com/TinkerNorth/dish-android
- Inspected revision: `0d93353818b46869d2d88aa54acfc78ff4b9120a`
- License: LGPL-3.0-or-later. Full license texts are included in `mobile/LICENSE` and `mobile/COPYING.GPL3`.
- `mobile/app/src/main/java/dev/redux/controller/dish/GamepadButtonLayouts.kt` is copied from Dish, with its package changed.
- Certificate-pinning logic in `ReduxLink.kt` is adapted from Dish's `TofuTrustManager.kt`. Storage, transport endpoints, and application structure differ.
- The Gradle wrapper files are retained from the Dish checkout.

Redux's UI, full-state input protocol, and Windows companion are implemented in this workspace. The custom receiver is not wire-compatible with Satellite. The original Dish and Satellite checkouts in `android/` and `receiver/` are local reference sources; the build uses `mobile/` and `windows/`.

## HIDMaestro — HIDMaestro Contributors

- Source: https://github.com/hifihedgehog/HIDMaestro
- Binary: official `v2.1.0` release, `HIDMaestro-v2.1.0.zip`.
- Archive SHA256: `7fe349f163864b8dfc36117af260d51bac94a2263b6cdfa719cb364a808397b4`.
- The SDK assembly is retained in `windows/lib/HIDMaestro.Core.dll`.
- MIT license and bundled dependency notices are retained alongside it and distributed with the receiver.

The custom HID VID/PID is a development identity for local testing, not a claim to an assigned USB product identity. Revisit it before public distribution.

## Atari Jaguar overlay artwork

- `mobile/app/src/main/assets/overlays` reproduces the keypad overlays shipped with Atari Jaguar games: scans from the AtariAge Jaguar overlay archive (https://atariage.com/system_items.php?SystemID=JAGUAR&itemTypeID=OVERLAY: JagMIND: Bomb Squad, Trevor McFur, Wolfenstein 3D) and community renders of the same plates supplied by the project owner (cropped to the plate). The artwork and the game names are trademarks and copyrights of their respective owners (Atari, id Software, Twentieth Century Fox, Starcat Developments and others) and are included for personal use with games the owner holds; they are not covered by this project's LGPL license and must not be redistributed commercially.

## Build/runtime distribution

- Gradle wrapper: Apache-2.0; the license is included in `mobile/gradle/GRADLE-LICENSE.txt` (from Gradle v9.8.2).
- The self-contained Windows receiver includes the .NET runtime. Microsoft .NET's MIT license and third-party notices are included in `windows/lib/Dotnet-LICENSE.txt` and `windows/lib/Dotnet-ThirdPartyNotices.txt` and copied into the receiver package.

Redistributions of the modified app must retain the applicable notices and provide the corresponding source as required by its license. The source in `mobile/` and `windows/` is the source for this prototype.
