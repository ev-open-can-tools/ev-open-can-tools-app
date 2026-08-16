package com.evcantools.protocol

/**
 * The `send` command: inject a short burst of CAN frames once.
 *
 * Mirrors `bleHandleSend` in the firmware's `include/ble/ble_service.h`. The
 * device applies the same safety gates as its automatic injection, so a send may
 * legitimately come back as `{"ok":false,"error":"gated","reason":"..."}` —
 * that is not a transport failure and should be shown to the user as-is.
 *
 * Not modelled, because the firmware does not support it: a per-frame delay
 * (it would stall the device's BLE task) and extended 29-bit ids (the CAN
 * drivers accept 11-bit ids only).
 */

/** Must match `kBleSendMaxFrames` in the firmware. */
const val MAX_SEND_FRAMES = 16

/** Widest id the CAN drivers accept — 11-bit standard frames. */
const val MAX_CAN_ID = 0x7FF

/** Longest CAN payload. */
const val MAX_CAN_DATA_BYTES = 8

/**
 * One frame to inject.
 *
 * @param id 11-bit CAN identifier.
 * @param data 1..8 payload bytes.
 * @param bus optional bus mask; null means the firmware's default (any bus).
 */
data class CanFrameSpec(
    val id: Int,
    val data: ByteArray,
    val bus: Int? = null,
) {
    init {
        require(id in 0..MAX_CAN_ID) {
            "CAN id 0x${id.toString(16).uppercase()} outside the 11-bit range (0x000..0x7FF)"
        }
        require(data.isNotEmpty() && data.size <= MAX_CAN_DATA_BYTES) {
            "CAN payload must be 1..$MAX_CAN_DATA_BYTES bytes, got ${data.size}"
        }
    }

    /** Uppercase hex payload, as the firmware expects it. */
    fun dataHex(): String = data.joinToString("") { "%02X".format(it) }

    /** `0x3E1`-style id, for display and for the wire. */
    fun idHex(): String = "0x%03X".format(id)

    // Value semantics despite the ByteArray member.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrameSpec) return false
        return id == other.id && bus == other.bus && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = (31 * id + data.contentHashCode()) * 31 + (bus ?: 0)
}

/**
 * Parse a CAN id typed by a user: `0x3E1`, `3E1` (hex) or `#993` (decimal).
 *
 * @throws IllegalArgumentException with a message fit to show in the UI.
 */
fun parseCanId(text: String): Int {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "Enter a CAN id" }
    val value = if (trimmed.startsWith("#")) {
        trimmed.drop(1).toIntOrNull(10)
    } else {
        trimmed.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
    }
    requireNotNull(value) { "'$trimmed' is not a valid CAN id (try 0x3E1)" }
    require(value in 0..MAX_CAN_ID) {
        "CAN id must be between 0x000 and 0x7FF"
    }
    return value
}

/**
 * Parse a payload typed by a user: hex digits, optionally separated by spaces
 * or dashes, e.g. `48 A6 B1` or `48A6B1`.
 *
 * @throws IllegalArgumentException with a message fit to show in the UI.
 */
fun parseCanData(text: String): ByteArray {
    val hex = text.filterNot { it.isWhitespace() || it == '-' || it == ':' }
    require(hex.isNotEmpty()) { "Enter payload bytes" }
    require(hex.length % 2 == 0) { "Payload needs an even number of hex digits" }
    require(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        "Payload may only contain hex digits (0-9, A-F)"
    }
    require(hex.length / 2 <= MAX_CAN_DATA_BYTES) {
        "Payload is ${hex.length / 2} bytes, the CAN maximum is $MAX_CAN_DATA_BYTES"
    }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Build the `send` command payload for [frames].
 *
 * @throws IllegalArgumentException if the burst is empty or longer than the
 *   firmware accepts — checked here so the failure surfaces before the write.
 */
fun buildSendCommand(frames: List<CanFrameSpec>): String {
    require(frames.isNotEmpty()) { "Nothing to send" }
    require(frames.size <= MAX_SEND_FRAMES) {
        "A button may send at most $MAX_SEND_FRAMES frames, got ${frames.size}"
    }
    val body = frames.joinToString(",") { frame ->
        buildString {
            append("{\"id\":\"").append(frame.idHex()).append("\"")
            append(",\"data\":\"").append(frame.dataHex()).append("\"")
            frame.bus?.let { append(",\"bus\":").append(it) }
            append("}")
        }
    }
    return "{\"cmd\":\"send\",\"args\":{\"frames\":[$body]}}"
}
