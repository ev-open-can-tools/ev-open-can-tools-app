# EV-CAN-Tool BLE protocol

The app talks to the device over a small GATT service defined by the firmware in
[`ev-open-can-tools`](https://github.com/ev-open-can-tools/ev-open-can-tools),
`include/ble/ble_service.h`. This file is the app-side mirror of that contract;
keep both in sync when either changes.

## GATT layout

Advertised GAP name: **`EVCANTool`**

| Role      | UUID                                     | Properties                         |
|-----------|------------------------------------------|------------------------------------|
| Service   | `e7c10001-9a3f-4bd2-b1a7-0c0ffee00001`   | primary                            |
| Command   | `e7c10002-9a3f-4bd2-b1a7-0c0ffee00001`   | WRITE (encrypted + authenticated)  |
| Response  | `e7c10003-9a3f-4bd2-b1a7-0c0ffee00001`   | READ + NOTIFY (encrypted + auth.)  |

Both characteristics require an **encrypted, authenticated** link, so the peer
must be **bonded** first. Pairing is LE Secure Connections with a **passkey**
(MITM) + bonding: the device displays the passkey (injected from the value shown
on its web dashboard), the user types it into the phone's system pairing dialog.
Bonds persist in NVS. This is what locks out third parties.

## Command framing

A command is a JSON object terminated by `\n`, written to the Command
characteristic:

```
{"cmd":"status"}\n
```

## Response paging

A reply can exceed the 512-byte GATT attribute cap, so it is fetched in pages.
Each **READ** of the Response characteristic returns:

```
[ uint32 total ][ uint32 offset ][ up to 400 bytes of reply ]
   4 bytes LE      4 bytes LE       payload slice
```

- `total`  — full reply length in bytes
- `offset` — byte offset of this slice within the reply

A READ does **not** advance the device cursor (so Android's ATT Read Blob
reassembly of one page stays stable). To fetch the **next** page, write
`{"cmd":"next"}\n` to the Command characteristic, then READ again. Issuing any
other command replaces the reply buffer and resets the cursor to 0.

Client loop (see `BleTransport.request` in `:protocol`):

1. write `{"cmd":...}\n`
2. READ page → parse header + slice, append at `offset`
3. if assembled `< total`: write `{"cmd":"next"}\n`, go to 2
4. else: done — parse the assembled JSON

## Commands

Available in firmware `v4.0.0-beta.1` (do not modify the existing ones — additive
changes only):

| Command                  | Reply                                             |
|--------------------------|---------------------------------------------------|
| `{"cmd":"status"}`       | `{"ok":true,"dev":…,"ble":…,"hw":0\|1\|2,"inject":…,"injectActive":…,"uptimeS":…}` |
| `{"cmd":"ping"}`         | `{"ok":true,"pong":true}`                         |
| `{"cmd":"wifi_mode"}`    | `{"ok":true,"reboot":true}` (device reboots to WiFi) |
| `{"cmd":"next"}`         | (paging cursor advance; no distinct reply)        |
| unknown                  | `{"ok":false,"error":"unknown cmd"}`              |

`hw`: 0 = Legacy, 1 = HW3, 2 = HW4. `uptimeS` is present only on-device.

## Planned additive commands

- **P1 — `send`** (firmware `4.0.0-beta.2`): `{"cmd":"send","args":{"frames":[{"id":"0x3C2","ext":false,"data":"48A6…","delayMs":0}]}}`
  → injects 1..N frames once through the injection pipeline (subject to the
  existing `injection_policy` safety gates); reply `{"ok":true,"sent":N}`.
- **P4 — config get/set + car stats** (firmware `4.0.0-beta.3`): mirror the web
  dashboard's configuration routes for the app's settings page.
