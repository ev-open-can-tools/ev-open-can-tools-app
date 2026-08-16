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
| `{"cmd":"inject","args":{"on":true}}` | `{"ok":true,"inject":true}` — master injection switch |
| `{"cmd":"config"}` / `{"cmd":"config","args":{…}}` | `{"ok":true,"config":{…}}` — read or write settings |
| `{"cmd":"stats"}`        | `{"ok":true,"canFrames":…,"parked":…,…}` — live counters + vehicle state |
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

### `inject` — the master injection switch

```json
{"cmd":"inject","args":{"on":true}}  ->  {"ok":true,"inject":true}
```

Mirrors the dashboard's injection toggle (`dashSetCanActive`), persisted to NVS.
It exists because the dashboard is unreachable while the device is in BLE mode,
which left `gated / injection disabled` — the most common rejection by far — as a
dead end for the app. `on` must be a real boolean; anything else is rejected.

Turning it on does not guarantee the next `send` succeeds: the other gates
(warm-up, AP, summon-only) still apply, so re-read `status` rather than assuming.

### `config` — read and write the dashboard settings

Without `args` it reads. With `args` it applies **only the keys present** and
echoes the stored state back, so a settings screen can send one field per toggle
instead of rewriting everything.

```json
{"cmd":"config","args":{"apg":false}}  ->  {"ok":true,"config":{ …full config… }}
```

| Key | Meaning |
|---|---|
| `hw` | 0 Legacy, 1 HW3, 2 HW4 |
| `sp` / `spa` | speed profile / pick it automatically |
| `can` | master injection switch (same one `inject` flips) |
| `apg` | autopilot gate |
| `smo` | summon-only injection |
| `nag` | nag suppression mode |
| `plgr` | plugin replay count |
| `hw3OffsetSlew` / `hw3SlewRate` | HW3 offset ramping |

Booleans go on the wire as real JSON booleans; the firmware renders them into
the `"1"`/`"0"` its validators accept.

**The device is the authority.** `ctrlApplyConfig` in the firmware is the single
implementation, shared with the dashboard's `POST /config` — the validation
cannot drift between the two transports. It clamps, and it refuses outright:

```json
{"ok":false,"error":"Nag Mode C is blocked on HW4 after reported control faults"}
```

Take the echoed `config` as the new truth rather than assuming a write applied.

### `stats` — live counters and vehicle state

```json
{"cmd":"stats"}
```

Returns `uptimeS`, `canFrames`, `canAgeMs`, `txOk`, `txFail`, `freeHeap`, plus
the state the injection gates key off: `gateEnabled`, `gateAllowed`, `apActive`,
`parked`, `summoning`, `gateReason`. Kept separate from `status` so the
frequently polled reply stays small.

## Planned additive commands

- WiFi configuration, plugin management and OTA remain dashboard-only. Plugin
  upload would mean large payloads over a 255-byte-per-write channel, and pushing
  firmware images over BLE is its own project.
