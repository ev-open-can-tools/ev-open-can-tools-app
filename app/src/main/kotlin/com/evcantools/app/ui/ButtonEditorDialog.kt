package com.evcantools.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.evcantools.app.data.ButtonFrame
import com.evcantools.app.data.CanButton
import com.evcantools.protocol.MAX_SEND_FRAMES
import com.evcantools.protocol.parseCanData
import com.evcantools.protocol.parseCanId

/** A frame row while it is being edited — free text, validated on save. */
private data class FrameDraft(var idText: String, var dataText: String)

/**
 * Create or edit one button.
 *
 * Input is kept as raw text so the user can type freely; validation runs on save
 * and reports the first offending field, because a half-valid button must never
 * reach storage (the grid trusts stored frames to be sendable).
 */
@Composable
fun ButtonEditorDialog(
    initial: CanButton,
    isNew: Boolean,
    onSave: (CanButton) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    val drafts = remember {
        mutableStateListOf<FrameDraft>().apply {
            if (initial.frames.isEmpty()) {
                add(FrameDraft("", ""))
            } else {
                initial.frames.forEach { add(FrameDraft(it.idHex, it.data)) }
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New button" else "Edit button") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                drafts.forEachIndexed { index, draft ->
                    FrameRow(
                        draft = draft,
                        canRemove = drafts.size > 1,
                        onIdChange = { drafts[index] = draft.copy(idText = it) },
                        onDataChange = { drafts[index] = draft.copy(dataText = it) },
                        onRemove = { drafts.removeAt(index) },
                    )
                }

                if (drafts.size < MAX_SEND_FRAMES) {
                    TextButton(onClick = { drafts.add(FrameDraft("", "")) }) {
                        Text("Add frame")
                    }
                } else {
                    Text(
                        "A button can hold at most $MAX_SEND_FRAMES frames.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val result = validate(label, drafts)) {
                    is Validation.Invalid -> error = result.message
                    is Validation.Valid -> onSave(
                        initial.copy(label = label.trim(), frames = result.frames),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun FrameRow(
    draft: FrameDraft,
    canRemove: Boolean,
    onIdChange: (String) -> Unit,
    onDataChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft.idText,
            onValueChange = onIdChange,
            label = { Text("CAN id") },
            placeholder = { Text("0x3E1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.width(112.dp),
        )
        OutlinedTextField(
            value = draft.dataText,
            onValueChange = onDataChange,
            label = { Text("Data (hex)") },
            placeholder = { Text("48A600") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.weight(1f),
        )
        if (canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove frame", Modifier.size(20.dp))
            }
        }
    }
}

private sealed interface Validation {
    data class Valid(val frames: List<ButtonFrame>) : Validation
    data class Invalid(val message: String) : Validation
}

private fun validate(label: String, drafts: List<FrameDraft>): Validation {
    if (label.isBlank()) return Validation.Invalid("Give the button a label")
    if (drafts.isEmpty()) return Validation.Invalid("Add at least one frame")

    val frames = mutableListOf<ButtonFrame>()
    drafts.forEachIndexed { index, draft ->
        val id = runCatching { parseCanId(draft.idText) }
            .getOrElse { return Validation.Invalid("Frame ${index + 1}: ${it.message}") }
        val data = runCatching { parseCanData(draft.dataText) }
            .getOrElse { return Validation.Invalid("Frame ${index + 1}: ${it.message}") }
        frames += ButtonFrame(id = id, data = data.joinToString("") { b -> "%02X".format(b) })
    }
    return Validation.Valid(frames)
}
