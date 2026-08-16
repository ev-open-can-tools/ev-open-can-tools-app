package com.evcantools.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcantools.app.EvCanViewModel
import com.evcantools.app.ble.ConnectionState
import com.evcantools.app.data.CanButton
import com.evcantools.protocol.StatusReply

@Composable
fun MainScreen(vm: EvCanViewModel, modifier: Modifier = Modifier) {
    val conn by vm.connectionState.collectAsState()
    val ui by vm.ui.collectAsState()
    val buttons by vm.buttons.collectAsState()

    var editorTarget by remember { mutableStateOf<CanButton?>(null) }
    var deviceCardOpen by remember { mutableStateOf(false) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) vm.connect()
    }

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
            Text("EV CAN Tools", style = MaterialTheme.typography.headlineMedium)
            Row {
                if (ui.editing) {
                    IconButton(onClick = { editorTarget = vm.newButton() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add button")
                    }
                }
                IconButton(onClick = { vm.setEditing(!ui.editing) }) {
                    Icon(
                        if (ui.editing) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (ui.editing) "Done editing" else "Edit buttons",
                    )
                }
            }
        }

        ConnectionRow(
            conn = conn,
            onConnect = { permissionLauncher.launch(permissions) },
            onDisconnect = vm::disconnect,
        )

        ButtonGrid(
            buttons = buttons,
            editing = ui.editing,
            enabled = conn is ConnectionState.Ready && !ui.busy,
            sendingButtonId = ui.sendingButtonId,
            onTap = vm::send,
            onEdit = { editorTarget = it },
            onAdd = { editorTarget = vm.newButton() },
        )

        // Only in edit mode: managing packs is a housekeeping task, and the grid
        // should stay uncluttered when the app is being used to send frames.
        if (ui.editing) {
            ButtonPackBar(vm = vm, hasButtons = buttons.isNotEmpty())
        }

        if (conn is ConnectionState.Ready) {
            DeviceCard(
                status = ui.status,
                expanded = deviceCardOpen,
                busy = ui.busy,
                lastPongOk = ui.lastPongOk,
                onToggle = { deviceCardOpen = !deviceCardOpen },
                onRefresh = vm::refreshStatus,
                onPing = vm::ping,
                onSetInjection = vm::setInjection,
                onSwitchToWifi = vm::switchToWifi,
            )
        }

        if (ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("  Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        ui.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    ui.pendingImport?.let { pending ->
        ImportModeDialog(
            pending = pending,
            currentCount = buttons.size,
            onApply = vm::applyImport,
            onDismiss = vm::cancelImport,
        )
    }

    editorTarget?.let { target ->
        ButtonEditorDialog(
            initial = target,
            isNew = buttons.none { it.id == target.id },
            onSave = {
                vm.saveButton(it)
                editorTarget = null
            },
            onDelete = {
                vm.deleteButton(target.id)
                editorTarget = null
            },
            onDismiss = { editorTarget = null },
        )
    }
}

// ---- button grid ---------------------------------------------------------

@Composable
private fun ButtonGrid(
    buttons: List<CanButton>,
    editing: Boolean,
    enabled: Boolean,
    sendingButtonId: String?,
    onTap: (CanButton) -> Unit,
    onEdit: (CanButton) -> Unit,
    onAdd: () -> Unit,
) {
    if (buttons.isEmpty()) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No buttons yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A button sends a stored burst of CAN frames to the device with one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onAdd) { Text("Add your first button") }
            }
        }
        return
    }

    // A plain Row-per-pair layout rather than LazyVerticalGrid: the screen is
    // already inside a verticalScroll, and a lazy grid nested in one needs a
    // bounded height or it fails to measure. The button count is small enough
    // that laziness buys nothing.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { button ->
                    ButtonTile(
                        button = button,
                        editing = editing,
                        enabled = enabled && button.isSendable,
                        sending = sendingButtonId == button.id,
                        onClick = { if (editing) onEdit(button) else onTap(button) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep a lone trailing tile at half width instead of stretching it.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ButtonTile(
    button: CanButton,
    editing: Boolean,
    enabled: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        // In edit mode a tile is always tappable — that is how you open its editor,
        // including for a button whose frames are currently broken.
        enabled = editing || enabled,
        modifier = modifier.height(96.dp),
        colors = if (button.isSendable) {
            CardDefaults.elevatedCardColors()
        } else {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        },
    ) {
        Box(Modifier.padding(12.dp).fillMaxWidth()) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text(
                    button.label.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (button.isSendable) button.summary else "invalid — tap Edit",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            if (sending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
                )
            } else if (editing) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit ${button.label}",
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
                )
            }
        }
    }
}

// ---- connection + device -------------------------------------------------

@Composable
private fun ConnectionRow(
    conn: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val label = when (conn) {
        ConnectionState.Idle -> "Idle"
        ConnectionState.Scanning -> "Scanning for EVCANTool…"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Pairing -> "Pairing — enter the passkey from the dashboard"
        ConnectionState.Preparing -> "Preparing…"
        ConnectionState.Ready -> "Connected"
        is ConnectionState.Error -> "Error: ${conn.message}"
    }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Status: $label", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val connecting = conn is ConnectionState.Scanning ||
                    conn is ConnectionState.Connecting ||
                    conn is ConnectionState.Pairing ||
                    conn is ConnectionState.Preparing
                Button(onClick = onConnect, enabled = conn !is ConnectionState.Ready && !connecting) {
                    Text("Connect")
                }
                OutlinedButton(onClick = onDisconnect, enabled = conn is ConnectionState.Ready) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    status: StatusReply?,
    expanded: Boolean,
    busy: Boolean,
    lastPongOk: Boolean?,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onSetInjection: (Boolean) -> Unit,
    onSwitchToWifi: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Device", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
            }
            // The master injection switch stays visible even when the details are
            // collapsed: "gated — injection disabled" is by far the most common
            // reason a button does nothing, and this is the only way to clear it
            // while the device is in BLE mode.
            status?.let {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Injection ${if (it.injectActive) "active" else if (it.inject) "gated" else "off"}" +
                            " · ${it.hardwareLabel}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = it.inject, onCheckedChange = onSetInjection, enabled = !busy)
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (status == null) {
                    Text("—", style = MaterialTheme.typography.bodyMedium)
                } else {
                    StatusLine("Dev mode", if (status.dev) "on" else "off")
                    StatusLine("BLE mode", if (status.ble) "on" else "off")
                    StatusLine("Hardware", status.hardwareLabel)
                    StatusLine("CAN active", if (status.inject) "yes" else "no")
                    StatusLine("Injection", if (status.injectActive) "active" else "idle")
                    status.uptimeS?.let { StatusLine("Uptime", "${it}s") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
                    Button(onClick = onPing, enabled = !busy) { Text("Ping") }
                }
                lastPongOk?.let {
                    Text(if (it) "Pong ✓" else "Pong ✗", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onSwitchToWifi, enabled = !busy) {
                    Text("Switch device to WiFi mode")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
