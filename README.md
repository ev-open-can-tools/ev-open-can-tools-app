# EV CAN Tools — Android app

Android companion app for the [ev-open-can-tools](https://github.com/ev-open-can-tools/ev-open-can-tools)
ESP32 firmware. It talks to the device over **Bluetooth LE**, mirroring the WiFi
web dashboard, with an encrypted + passkey-paired link so only the phone that
paired can control the device.

> Status: **P0 — foundation.** BLE connect + passkey pairing + the paged
> request/response transport, verified against the firmware's `status`/`ping`/
> `wifi_mode` commands. The custom-command button grid, import/export, themes and
> the settings page follow (see the roadmap).

## Modules

| Module      | What it is                                                                 |
|-------------|----------------------------------------------------------------------------|
| `:protocol` | Pure Kotlin/JVM: BLE command framing + paged response reassembly + reply types. No Android deps, unit-tested on the JVM. |
| `:app`      | Android (Jetpack Compose + Material 3): BLE client, UI, storage.            |

The wire contract lives in [`PROTOCOL.md`](PROTOCOL.md), mirrored from the
firmware's `include/ble/ble_service.h`.

## Build

Requires the Android SDK for the `:app` module. CI ([`.github/workflows/android.yml`](.github/workflows/android.yml))
builds a debug APK on every push and uploads it as an artifact — download it and
sideload it to test on a phone.

```bash
# App (needs Android SDK on PATH / ANDROID_HOME):
./gradlew :app:assembleDebug

# Protocol core only — no Android SDK needed:
./gradlew :protocol:test
```

Without an Android SDK the build automatically configures only `:protocol`
(see `settings.gradle.kts`), so the transport core stays testable on any JDK box.

## Pairing

1. On the device's web dashboard, switch to **BLE mode** and note the **passkey**.
2. In the app, tap **Connect** → it scans for `EVCANTool`, connects, and starts
   pairing.
3. Enter the passkey in the Android system dialog. The bond persists; controls
   unlock once bonded.

## Roadmap

- **P0** — foundation: BLE connect/pair + paged transport + status/ping *(current)*
- **P1** — custom-command **button grid** (tap = send stored CAN frame(s)) + edit mode
- **P2** — import / export / share function packs (backup, new device)
- **P3** — custom icons/images + themes
- **P4** — settings page mirroring the web config + car stats

App state (buttons, icons, codes) is stored **on the phone**; the device stores
none of it. Firmware changes are strictly additive to the existing dev branch.
