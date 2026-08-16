package com.evcantools.app.data

import com.evcantools.protocol.CanFrameSpec
import com.evcantools.protocol.MAX_CAN_ID
import com.evcantools.protocol.parseCanData
import kotlinx.serialization.Serializable

/**
 * A stored CAN frame belonging to a button.
 *
 * Values are kept in their canonical form — the id as a number, the payload as
 * uppercase hex — because they are validated when the user saves the button, so
 * everything on disk is already known-good.
 */
@Serializable
data class ButtonFrame(
    val id: Int,
    val data: String,
    val bus: Int? = null,
) {
    /** Null when the stored values are out of range (hand-edited or corrupt file). */
    fun toSpecOrNull(): CanFrameSpec? = runCatching {
        CanFrameSpec(id = id, data = parseCanData(data), bus = bus)
    }.getOrNull()

    val idHex: String get() = "0x%03X".format(id)

    val isValid: Boolean get() = id in 0..MAX_CAN_ID && toSpecOrNull() != null
}

/**
 * One tile on the grid: a label the user recognises and the burst of frames a
 * tap injects. [id] is a stable identifier so edits and deletes survive
 * reordering.
 */
@Serializable
data class CanButton(
    val id: String,
    val label: String,
    val frames: List<ButtonFrame> = emptyList(),
) {
    val isSendable: Boolean get() = frames.isNotEmpty() && frames.all { it.isValid }

    /** Short one-liner for the tile, e.g. "0x3E1 · 3 frames". */
    val summary: String
        get() = when {
            frames.isEmpty() -> "no frames"
            frames.size == 1 -> frames.first().idHex
            else -> "${frames.first().idHex} · ${frames.size} frames"
        }
}

/**
 * The on-disk document. Versioned from the start so the P2 export/import can
 * migrate old files instead of guessing.
 */
@Serializable
data class ButtonBook(
    val version: Int = CURRENT_BUTTON_BOOK_VERSION,
    val buttons: List<CanButton> = emptyList(),
)

const val CURRENT_BUTTON_BOOK_VERSION = 1
