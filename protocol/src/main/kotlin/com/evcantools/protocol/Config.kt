package com.evcantools.protocol

import kotlinx.serialization.Serializable

/**
 * Device configuration and live statistics.
 *
 * The firmware runs one validator for both transports (`ctrlApplyConfig`), so
 * the device is the authority: a `config` write echoes back the stored state
 * rather than confirming what was asked for. Clamping, hardware-dependent limits
 * and outright refusals all show up there, and the app displays what came back.
 */

/** Reply to `{"cmd":"config"}` and to a config write. */
@Serializable
data class ConfigReply(
    val ok: Boolean = false,
    val config: DeviceConfig? = null,
    val error: String? = null,
)

@Serializable
data class DeviceConfig(
    /** 0 = Legacy, 1 = HW3, 2 = HW4. */
    val hw: Int = 0,
    /** Profile the active handler reports — not necessarily the manual setting. */
    val speedProfile: Int = 0,
    val speedAuto: Boolean = false,
    /** The master injection switch, the same one the `inject` command flips. */
    val injectionArmed: Boolean = false,
    val pluginReplay: Int = 1,
    val pluginReplayMax: Int = 1,
    val apGate: Boolean = false,
    val summonOnly: Boolean = false,
    val nagMode: Int = 0,
    val hw3OffsetSlew: Boolean = false,
    val hw3SlewRate: Int = 0,
    val ledBrightness: Int = 0,
) {
    val hardwareLabel: String
        get() = when (hw) {
            0 -> "Legacy"
            1 -> "HW3"
            2 -> "HW4"
            else -> "?"
        }

    /** HW4 offers five speed profiles, everything else three. */
    val speedProfileRange: IntRange get() = 0..(if (hw == 2) 4 else 2)

    /**
     * Nag Mode C is refused on HW4 by the firmware after reported control
     * faults, so the app must not offer it there either.
     */
    val nagModeRange: IntRange get() = 0..(if (hw == 2) 2 else 3)
}

/** Reply to `{"cmd":"stats"}` — live counters and the vehicle state. */
@Serializable
data class StatsReply(
    val ok: Boolean = false,
    val uptimeS: Long = 0,
    val canFrames: Long = 0,
    /** Age of the most recent CAN frame; large means the bus went quiet. */
    val canAgeMs: Long = 0,
    val txOk: Long = 0,
    val txFail: Long = 0,
    val freeHeap: Long = 0,
    val gateEnabled: Boolean = false,
    val gateAllowed: Boolean = false,
    val apActive: Boolean = false,
    val parked: Boolean = false,
    val summoning: Boolean = false,
    val gateReason: String = "",
)

fun parseConfig(json: String): ConfigReply = BleJson.decodeFromString(ConfigReply.serializer(), json)
fun parseStats(json: String): StatsReply = BleJson.decodeFromString(StatsReply.serializer(), json)

/** The settings a client may write. Names match the firmware's argument keys. */
enum class ConfigKey(val wire: String) {
    HARDWARE("hw"),
    SPEED_PROFILE("sp"),
    SPEED_AUTO("spa"),
    INJECTION_ARMED("can"),
    AP_GATE("apg"),
    SUMMON_ONLY("smo"),
    NAG_MODE("nag"),
    PLUGIN_REPLAY("plgr"),
    HW3_OFFSET_SLEW("hw3OffsetSlew"),
    HW3_SLEW_RATE("hw3SlewRate"),
}

/**
 * Build a config write. Only the given keys are touched — the firmware leaves
 * anything absent alone, so a settings screen can send one field at a time
 * instead of rewriting the whole configuration on every toggle.
 */
fun buildConfigCommand(values: Map<ConfigKey, Any>): String {
    require(values.isNotEmpty()) { "Nothing to change" }
    val body = values.entries.joinToString(",") { (key, value) ->
        val encoded = when (value) {
            is Boolean -> if (value) "true" else "false"
            is Int, is Long -> value.toString()
            else -> throw IllegalArgumentException(
                "${key.wire} must be a boolean or a number, got ${value::class.simpleName}",
            )
        }
        "\"${key.wire}\":$encoded"
    }
    return "{\"cmd\":\"config\",\"args\":{$body}}"
}

/** Read the current configuration without changing anything. */
const val READ_CONFIG_COMMAND = "{\"cmd\":\"config\"}"

/** Read live counters and vehicle state. */
const val STATS_COMMAND = "{\"cmd\":\"stats\"}"
