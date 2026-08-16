# Changelog

All notable changes to this app will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The app is pre-1.0 and every release is a **beta**: the roadmap (P1…P4 in the
[README](README.md)) is still being worked through, and the BLE wire contract in
[PROTOCOL.md](PROTOCOL.md) may still gain commands. Firmware changes it depends on
are strictly additive, so an older APK keeps working against newer firmware.

## [Unreleased]

No unreleased changes.

## [0.2.0-beta.4] - 2026-08-16

### Added

- **P2 — button packs.** Export the buttons to a JSON file, share them into any app, or import a pack. Files go through the Storage Access Framework, so no storage permission is needed and the user picks the destination.
- On import, a prompt asks whether to *add* the buttons or *replace* the existing set. Imported buttons always receive fresh ids, so importing a pack — including one exported from the same phone — duplicates rather than overwriting. Silently losing a button to an id collision would be unrecoverable.
- A pack that cannot be parsed, is empty, or was written in a newer format version is refused with a message naming the reason, before the merge choice is offered. Refusing an empty pack matters most in *replace* mode, where accepting it would wipe everything.
- Buttons whose frames the app cannot send are imported rather than dropped: they show as invalid on the grid and can be repaired, and the import reports how many there were.
- `:app` now has JVM unit tests (the pack rules are pure Kotlin), wired into CI.

### Removed

- The release runbook is no longer carried in the repository; it is kept locally instead. It held no secrets — only the public certificate fingerprint and the procedure — so this is a scope decision, not a leak. The fingerprint needed to check a downloaded APK now lives in the README.

## [0.2.0-beta.3] - 2026-08-16

### Changed

- **`applicationId` is now `org.ev_open_can_tools.ev_can_app`** (was `com.evcantools.app`), matching the package name registered for Android developer verification. From 30 September 2026 Google requires every app on a certified device — sideloaded ones included — to be tied to a verified developer, and coverage depends on the applicationId and release signing key matching the registration. The Kotlin/R namespace deliberately stays `com.evcantools.app`; only the applicationId is the app's identity. **Android treats this as a different app: the previous version must be uninstalled, and stored buttons do not carry over.**

### Added

- Release pipeline (`.github/workflows/release.yml`): pushing a `v*` tag builds a release-signed APK, prints its package name and certificate fingerprint into the job summary, and publishes the APK plus a `.sha256` as a GitHub Release. Beta tags are marked pre-release. The workflow fails and publishes nothing if the signing secrets are absent, because a debug-signed APK would not match the registration.
- A release runbook covering the verification requirement, why the committed debug keystore must never become the release key, keystore setup, and how to check a published APK. Kept outside the repository.

### Requires

- Firmware `4.0.0-beta.2` or newer (BLE `send` and `inject`).

## [0.2.0-beta.1] - 2026-08-16

First release that can actually control the device rather than only read from it.

### Added

- **Button grid.** A button is a label plus up to 16 CAN frames; one tap injects them via the firmware's new `send` command. Edit mode adds, edits and deletes buttons.
- **Injection switch** in the Device card. The firmware's master injection switch was previously reachable only from the web dashboard, which does not run while the device is in BLE mode — so "gated — injection disabled", by far the most common rejection, was a dead end for the app. The switch stays visible even when the Device card is collapsed. After flipping it the app re-reads `status` rather than trusting the echo, because the other gates (warm-up, AP, summon-only) still apply.
- `:protocol` gained `SendCommand.kt`: `CanFrameSpec`, `buildSendCommand()`, and `parseCanId`/`parseCanData` for the forms a user actually types (`0x3E1`, `3e1`, `#993`; `48 A6 00`, `48-A6-00`, `48A600`).
- Buttons persist as a versioned JSON document (`ButtonBook`) in private storage, written via write-then-rename so an interrupted write cannot truncate the set. A file that fails to parse is set aside as `.bad` instead of being lost. The device stores none of it — that file is what P2 will export.
- Rejections from the device are surfaced verbatim through `Ack.failureText`, including which safety gate was closed and which frame of a burst was malformed.

### Changed

- Chunked command writes. The firmware rejects any single ATT write over 255 bytes, but it accumulates writes and dispatches on the newline, so a long multi-frame command is now split client-side at `MAX_COMMAND_WRITE_BYTES` (180). No protocol change was needed for this — the earlier plan to invent a compact encoding turned out to be unnecessary.
- `MainScreen` replaces the P0 smoke screen. The old smoke controls (Refresh, Ping, Switch to WiFi, full status) moved into a collapsible Device card.

### Verified

- 26 JVM unit tests in `:protocol`, including a fake firmware that accumulates writes and asserts the 255-byte per-write cap the same way `bleCmdWriteCb` does.
- On device (ESP32, HW3, dev mode): injection off → `gated — injection disabled`; injection on → `Sent 1 frame(s)`. Both directions of the safety gate observed. No frames on a real bus — dev mode routes them through the TX loopback.

### Requires

- Firmware with the `send` and `inject` commands (ev-open-can-tools, after `4.0.0-beta.1`). Against older firmware a button tap reports `unknown cmd`.

## [0.1.1] - 2026-08-04

### Fixed

- Pairing no longer times out while the passkey dialog is open. A single overall connect timeout was tearing the link down mid-entry, which surfaced as a vanished dialog and a "wrong passkey" error. Each phase now has its own budget and bonding gets a generous one, because the user is reading the passkey off the dashboard and typing it in.

### Changed

- Debug builds are signed with a committed throwaway keystore, so every CI APK carries the same signature and sideloaded updates install over the previous one instead of being rejected for a signature mismatch.

## [0.1.0] - 2026-08-04

### Added

- P0 foundation: BLE scan, connect, LE Secure Connections pairing with the device's passkey, and the paged request/response transport, verified against the firmware's `status` and `ping`.
- Two-module split: `:protocol` is plain Kotlin/JVM (framing, paging, reply types) and unit-testable without an Android SDK; `:app` is Compose/Material 3. The build configures `:protocol` alone when no SDK is present.
- GitHub Actions CI: protocol tests plus a debug APK uploaded as an artifact.

[Unreleased]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/compare/v0.2.0-beta.4...HEAD
[0.2.0-beta.4]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases/tag/v0.2.0-beta.4
[0.2.0-beta.3]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases/tag/v0.2.0-beta.3
[0.2.0-beta.1]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases/tag/v0.2.0-beta.1
[0.1.1]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases/tag/v0.1.1
[0.1.0]: https://github.com/ev-open-can-tools/ev-open-can-tools-app/releases/tag/v0.1.0
