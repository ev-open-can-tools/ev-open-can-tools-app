package com.evcantools.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient JSON, so firmware replies may add fields without breaking the app. */
val BleJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * Reply to `{"cmd":"status"}` — mirrors `dashBuildBleStatusJson()` in the
 * firmware. `uptimeS` is only present on-device (omitted in native builds).
 */
@Serializable
data class StatusReply(
    val ok: Boolean = false,
    val dev: Boolean = false,
    val ble: Boolean = false,
    val hw: Int = -1,
    val inject: Boolean = false,
    val injectActive: Boolean = false,
    val uptimeS: Long? = null,
) {
    /** Human label for the `hw` field: 0=LEGACY, 1=HW3, 2=HW4. */
    val hardwareLabel: String
        get() = when (hw) {
            0 -> "Legacy"
            1 -> "HW3"
            2 -> "HW4"
            else -> "?"
        }
}

/** Reply to `{"cmd":"ping"}`. */
@Serializable
data class PingReply(
    val ok: Boolean = false,
    val pong: Boolean = false,
)

/** Generic ack, e.g. `{"ok":true,"reboot":true}` or an error `{"ok":false,"error":"..."}`. */
@Serializable
data class Ack(
    val ok: Boolean = false,
    val reboot: Boolean = false,
    val sent: Int? = null,
    val error: String? = null,
)

fun parseStatus(json: String): StatusReply = BleJson.decodeFromString(StatusReply.serializer(), json)
fun parsePing(json: String): PingReply = BleJson.decodeFromString(PingReply.serializer(), json)
fun parseAck(json: String): Ack = BleJson.decodeFromString(Ack.serializer(), json)
