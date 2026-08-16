package com.evcantools.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evcantools.app.EvCanViewModel
import com.evcantools.app.PendingImport
import com.evcantools.app.data.ImportMode

/**
 * Export, share and import of button packs.
 *
 * Files are picked through the Storage Access Framework, so the app needs no
 * storage permission and the user decides where a pack lands — local, Drive,
 * anywhere a document provider reaches.
 */
@Composable
fun ButtonPackBar(vm: EvCanViewModel, hasButtons: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::stageImport) }

    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Button packs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save your buttons to a file to back them up, move them to another " +
                    "phone, or pass them to someone else.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch(vm.suggestedExportFileName()) },
                    enabled = hasButtons,
                ) { Text("Export") }

                OutlinedButton(
                    onClick = {
                        vm.preparePackToShare()?.let { uri ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                // The receiving app gets read access to this one
                                // URI only, and only until it is done.
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share button pack"))
                        }
                    },
                    enabled = hasButtons,
                ) { Text("Share") }

                // Some providers hand JSON out as octet-stream or text/plain, so
                // filtering on application/json alone would grey out valid files.
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                ) { Text("Import") }
            }
        }
    }
}

/**
 * Asks how a successfully read pack should be applied. Shown only once the file
 * has parsed, so the choice is never offered for something unusable.
 */
@Composable
fun ImportModeDialog(
    pending: PendingImport,
    currentCount: Int,
    onApply: (ImportMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import ${pending.buttonCount} button(s)") },
        text = {
            Text(
                if (currentCount == 0) {
                    "You have no buttons yet, so these will simply be added."
                } else {
                    "You currently have $currentCount button(s). Add the imported ones " +
                        "alongside them, or replace everything with the imported set?"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onApply(ImportMode.ADD) }) { Text("Add") }
        },
        dismissButton = {
            Row {
                if (currentCount > 0) {
                    TextButton(onClick = { onApply(ImportMode.REPLACE) }) {
                        Text("Replace all", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
