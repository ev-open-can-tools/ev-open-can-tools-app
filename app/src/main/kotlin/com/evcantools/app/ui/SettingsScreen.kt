package com.evcantools.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.evcantools.protocol.ConfigKey
import com.evcantools.protocol.DeviceConfig
import com.evcantools.protocol.StatsReply

/**
 * Mirrors the web dashboard's configuration block.
 *
 * Every control writes a single key and then shows whatever the device echoes
 * back. The firmware validates, clamps and may refuse outright — Nag Mode C is
 * blocked on HW4, the speed profile range depends on the hardware — so the
 * device is the authority and this screen never assumes a write took effect.
 */
@Composable
fun SettingsScreen(
    config: DeviceConfig?,
    stats: StatsReply?,
    busy: Boolean,
    onSet: (ConfigKey, Any) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        if (config == null) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configuration not loaded", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRefresh, enabled = !busy) { Text("Load from device") }
                }
            }
            return@Column
        }

        VehicleCard(stats)
        InjectionCard(config, busy, onSet)
        HardwareCard(config, busy, onSet)
        PluginCard(config, busy, onSet)

        TextButton(onClick = onRefresh, enabled = !busy) { Text("Reload from device") }
    }
}

// ---- vehicle -------------------------------------------------------------

@Composable
private fun VehicleCard(stats: StatsReply?) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Vehicle & link", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (stats == null) {
                Text("—", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // The gate state first: it is what decides whether a button does
            // anything, and "blocked: ..." is the device's own wording.
            SettingLine("Injection allowed", if (stats.gateAllowed) "yes" else "no")
            if (!stats.gateAllowed && stats.gateReason.isNotBlank()) {
                Text(
                    stats.gateReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingLine("Parked", if (stats.parked) "yes" else "no")
            SettingLine("Autopilot", if (stats.apActive) "active" else "inactive")
            SettingLine("Summoning", if (stats.summoning) "yes" else "no")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingLine("CAN frames", stats.canFrames.toString())
            SettingLine("Last frame", "${stats.canAgeMs} ms ago")
            SettingLine("TX ok / failed", "${stats.txOk} / ${stats.txFail}")
            SettingLine("Free heap", "${stats.freeHeap / 1024} KiB")
            SettingLine("Uptime", "${stats.uptimeS} s")
        }
    }
}

// ---- injection -----------------------------------------------------------

@Composable
private fun InjectionCard(config: DeviceConfig, busy: Boolean, onSet: (ConfigKey, Any) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Injection", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchLine(
                "Injection armed",
                "Master switch. With this off, every button reports \"gated\".",
                config.injectionArmed,
                busy,
            ) { onSet(ConfigKey.INJECTION_ARMED, it) }
            SwitchLine(
                "Autopilot gate",
                "Refuses to inject while the car is driving under autopilot.",
                config.apGate,
                busy,
            ) { onSet(ConfigKey.AP_GATE, it) }
            SwitchLine(
                "Summon only",
                "Restricts injection to summoning.",
                config.summonOnly,
                busy,
            ) { onSet(ConfigKey.SUMMON_ONLY, it) }
        }
    }
}

// ---- hardware ------------------------------------------------------------

@Composable
private fun HardwareCard(config: DeviceConfig, busy: Boolean, onSet: (ConfigKey, Any) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Hardware", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Vehicle hardware", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = listOf(0 to "Legacy", 1 to "HW3", 2 to "HW4"),
                selected = config.hw,
                enabled = !busy,
            ) { onSet(ConfigKey.HARDWARE, it) }

            SwitchLine(
                "Automatic speed profile",
                "Let the device pick the profile from what it sees on the bus.",
                config.speedAuto,
                busy,
            ) { onSet(ConfigKey.SPEED_AUTO, it) }

            if (!config.speedAuto) {
                Text("Speed profile", style = MaterialTheme.typography.bodyMedium)
                ChipRow(
                    // Range comes from the hardware; HW4 offers five.
                    options = config.speedProfileRange.map { it to it.toString() },
                    selected = config.speedProfile,
                    enabled = !busy,
                ) { onSet(ConfigKey.SPEED_PROFILE, it) }
            }

            Text("Nag suppression", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                // Mode C (3) is absent on HW4: the firmware refuses it there.
                options = config.nagModeRange.map { it to nagModeName(it) },
                selected = config.nagMode,
                enabled = !busy,
            ) { onSet(ConfigKey.NAG_MODE, it) }

            if (config.hw == 1) {
                SwitchLine(
                    "Offset slew",
                    "Ramp the HW3 offset instead of stepping it.",
                    config.hw3OffsetSlew,
                    busy,
                ) { onSet(ConfigKey.HW3_OFFSET_SLEW, it) }
                if (config.hw3OffsetSlew) {
                    StepperLine(
                        label = "Slew rate",
                        value = config.hw3SlewRate,
                        suffix = " %/s",
                        enabled = !busy,
                    ) { onSet(ConfigKey.HW3_SLEW_RATE, it) }
                }
            }
        }
    }
}

private fun nagModeName(mode: Int) = when (mode) {
    0 -> "Off"
    1 -> "A"
    2 -> "B"
    3 -> "C"
    else -> mode.toString()
}

// ---- plugins -------------------------------------------------------------

@Composable
private fun PluginCard(config: DeviceConfig, busy: Boolean, onSet: (ConfigKey, Any) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Plugins", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "How often a plugin repeats each frame it sends.",
                style = MaterialTheme.typography.bodySmall,
            )
            var replay by remember(config.pluginReplay) {
                mutableFloatStateOf(config.pluginReplay.toFloat())
            }
            SettingLine("Replay count", "×${replay.toInt()}")
            Slider(
                value = replay,
                onValueChange = { replay = it },
                // Committed on release, not on every pixel of the drag: each
                // write is a BLE round trip and an NVS write on the device.
                onValueChangeFinished = { onSet(ConfigKey.PLUGIN_REPLAY, replay.toInt()) },
                valueRange = 1f..config.pluginReplayMax.coerceAtLeast(1).toFloat(),
                steps = (config.pluginReplayMax - 2).coerceAtLeast(0),
                enabled = !busy,
            )
            Text(
                "Plugin installation stays in the web dashboard.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---- building blocks -----------------------------------------------------

@Composable
private fun SwitchLine(
    label: String,
    hint: String,
    checked: Boolean,
    busy: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = !busy)
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun StepperLine(
    label: String,
    value: Int,
    suffix: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(value - 5) }, enabled = enabled) { Text("−") }
            Text("$value$suffix", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onChange(value + 5) }, enabled = enabled) { Text("+") }
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
