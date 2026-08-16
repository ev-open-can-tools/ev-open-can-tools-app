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

**A single write may not exceed 255 bytes.** The firmware flattens one ATT write
into a fixed stack buffer and rejects anything longer outright. Longer commands —
a multi-frame `send` — are simply split across several writes: the device appends
every write to an accumulator and only dispatches when it sees the newline, so
only the last chunk carries it. `frameCommandChunks` in `:protocol` does this,
capped at `MAX_COMMAND_WRITE_BYTES` (180, comfortably inside one ATT packet at
the negotiated MTU of 247).

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

Do not modify the existing ones — additive changes only, so an older APK keeps
working against newer firmware.

| Command                  | Reply                                             |
|--------------------------|---------------------------------------------------|
| `{"cmd":"status"}`       | `{"ok":true,"dev":…,"ble":…,"hw":0\|1\|2,"inject":…,"injectActive":…,"uptimeS":…}` |
| `{"cmd":"ping"}`         | `{"ok":true,"pong":true}`                         |
| `{"cmd":"send",…}`       | `{"ok":true,"sent":N}` — see below                |
| `{"cmd":"wifi_mode"}`    | `{"ok":true,"reboot":true}` (device reboots to WiFi) |
| `{"cmd":"next"}`         | (paging cursor advance; no distinct reply)        |
| unknown                  | `{"ok":false,"error":"unknown cmd"}`              |

`hw`: 0 = Legacy, 1 = HW3, 2 = HW4. `uptimeS` is present only on-device.

### `send` — one-shot frame injection

```json
{"cmd":"send","args":{"frames":[{"id":"0x3E1","data":"48A600"},{"id":993,"data":"AABB","bus":2}]}}
```

- `id` — 11-bit identifier, either a hex string (`"0x3E1"` / `"3E1"`) or a number.
  Ids above `0x7FF` are rejected: `CanFrame` carries no extended-frame flag and
  the drivers refuse them anyway.
- `data` — 2..16 hex digits, i.e. 1..8 payload bytes. Sets the DLC.
- `bus` — optional bus mask; omitted means the firmware default (any bus).
- At most `kBleSendMaxFrames` (16) frames per command.

There is deliberately **no per-frame delay**: the command runs on the BLE host
task, so a delay would stall the link for its duration.

Every frame is validated before any of them is sent, so a typo in the last frame
cannot leave half a burst on the bus.

Rejections carry a `reason`, and frame errors carry the offending `index`:

| Reply                                                            | Meaning                                  |
|------------------------------------------------------------------|------------------------------------------|
| `{"ok":false,"error":"gated","reason":"injection disabled"}`      | injection switched off on the device      |
| `{"ok":false,"error":"gated","reason":"warming up"}`              | CAN warm-up gate still closed             |
| `{"ok":false,"error":"gated","reason":"ap gate"}`                 | autopilot/park gate blocked the injection |
| `{"ok":false,"error":"gated","reason":"summon-only gate"}`        | summon-only mode blocked the injection    |
| `{"ok":false,"error":"bad frame","reason":…,"index":1}`           | frame 2 is malformed                      |
| `{"ok":false,"sent":1,"error":"tx failed"}`                       | the bus rejected a frame mid-burst        |

**A `gated` reply is normal, not an app failure** — it means the firmware refused
to inject in the car's current state. Show it to the user verbatim.

## Planned additive commands

- **P4 — config get/set + car stats**: mirror the web dashboard's configuration
  routes for the app's settings page. Needs the shared command core in the
  firmware first (`ctrlBuildStatusJson` / `ctrlApplyConfig` / `ctrlDispatch`), so
  HTTP and BLE do not drift apart.
