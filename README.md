# EV CAN Tools — Android app

Android companion app for the [ev-open-can-tools](https://github.com/ev-open-can-tools/ev-open-can-tools)
ESP32 firmware. It talks to the device over **Bluetooth LE**, mirroring the WiFi
web dashboard, with an encrypted + passkey-paired link so only the phone that
paired can control the device.

> Status: **v0.2.0-beta.1 — P1, button grid.** On top of the P0 foundation (BLE
> connect, passkey pairing, paged transport) the app now stores CAN-frame buttons
> on the phone and injects them with one tap via the firmware's `send` command,
> and can flip the device's injection switch itself. Import/export, icons/themes
> and the settings page follow (see the roadmap). Every release is a beta until
> the roadmap is through — see [CHANGELOG.md](CHANGELOG.md).

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

## Buttons

A button is a label plus up to 16 CAN frames. Tap it and the frames go out in one
`send` command; tap the pencil to edit, add or delete buttons.

The device applies the same safety gates to a button as to its own automatic
injection, so a tap can legitimately come back as e.g. *"gated — injection
disabled"* or *"gated — ap gate"*. That is the firmware refusing to write to the
bus in the car's current state, not a bug in the app.

Buttons are stored in `buttons.json` in the app's private storage — the device
holds none of it, which is what makes them exportable in P2.

## Install

Releases are published as sideloadable APKs under
[Releases](https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases),
each with a `.sha256` next to it — verify before installing.

The app is **not** on the Play Store. It is signed with a key registered for
Android developer verification against `org.ev_open_can_tools.ev_can_app`, which
is what keeps it installable on certified devices once Google's verification
requirement takes effect (30 September 2026 in the first countries, global from
2027).

Check any APK you downloaded before installing it:

```bash
sha256sum -c ev-can-tools-<version>.apk.sha256
apksigner verify --print-certs ev-can-tools-<version>.apk
```

The certificate SHA-256 must be
`26fc837ca9da703af7dd4b8f32e777804a1dc60020705efc5886675b7ee49cad`.

## Button packs

In edit mode you can **Export** your buttons to a JSON file, **Share** them
straight into any app, or **Import** a pack someone sent you. Files are picked
through the system document picker, so the app needs no storage permission and
the pack can land wherever you like — local, Drive, a chat.

On import you choose whether to *add* the buttons alongside yours or *replace*
everything. Imported buttons always get fresh ids, so importing a pack — even one
exported from this same phone — can never overwrite a button you already had.

## Roadmap

- **P0** — foundation: BLE connect/pair + paged transport + status/ping *(done)*
- **P1** — custom-command **button grid** (tap = send stored CAN frame(s)) + edit mode *(done)*
- **P2** — import / export / share function packs (backup, new device) *(done)*
- **P3** — custom icons/images + themes
- **P4** — settings page mirroring the web config + car stats *(current)*

App state (buttons, icons, codes) is stored **on the phone**; the device stores
none of it. Firmware changes are strictly additive to the existing dev branch.
