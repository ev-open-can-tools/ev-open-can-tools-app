package com.evcantools.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcantools.app.EvCanViewModel
import com.evcantools.app.ble.ConnectionState
import com.evcantools.protocol.StatusReply

@Composable
fun SmokeScreen(vm: EvCanViewModel, modifier: Modifier = Modifier) {
    val conn by vm.connectionState.collectAsState()
    val ui by vm.ui.collectAsState()

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
        Text("EV CAN Tools", style = MaterialTheme.typography.headlineMedium)
        Text(
            "BLE smoke test — connect, read status, ping, switch back to WiFi.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ConnectionRow(
            conn = conn,
            onConnect = { permissionLauncher.launch(permissions) },
            onDisconnect = vm::disconnect,
        )

        if (conn is ConnectionState.Ready) {
            StatusCard(ui.status)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = vm::refreshStatus, enabled = !ui.busy) { Text("Refresh") }
                Button(onClick = vm::ping, enabled = !ui.busy) { Text("Ping") }
            }
            OutlinedButton(onClick = vm::switchToWifi, enabled = !ui.busy) {
                Text("Switch device to WiFi mode")
            }
            ui.lastPongOk?.let {
                Text(if (it) "Pong ✓" else "Pong ✗", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(0.dp))
                Text("  Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

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
private fun StatusCard(status: StatusReply?) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Device status", style = MaterialTheme.typography.titleMedium)
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
