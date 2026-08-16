package com.evcantools.app.data

import kotlinx.serialization.json.Json

/**
 * Reading and writing button packs — the export/import format.
 *
 * Deliberately free of Android APIs so the parsing and merge rules can be unit
 * tested on a plain JVM. Anything that touches a `Uri` lives in the ViewModel.
 *
 * The format is the same [ButtonBook] document the app stores locally, so an
 * export is a copy of the store and nothing has to be kept in sync.
 */

/** How an imported pack is combined with what the user already has. */
enum class ImportMode {
    /** Append the imported buttons, keeping every existing one. */
    ADD,

    /** Discard the current buttons and keep only the imported ones. */
    REPLACE,
}

sealed interface ImportResult {
    /**
     * @param buttons the full set to persist, already merged.
     * @param imported how many buttons came out of the file.
     * @param invalidFrames how many imported buttons carry frames the app cannot
     *   send — they are kept, not dropped, and show up as editable and broken.
     */
    data class Ok(
        val buttons: List<CanButton>,
        val imported: Int,
        val invalidFrames: Int,
    ) : ImportResult

    /** [message] is written to be shown to the user as-is. */
    data class Failed(val message: String) : ImportResult
}

private val transferJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Serialise [buttons] as a pack. */
fun encodeButtonPack(buttons: List<CanButton>): String =
    transferJson.encodeToString(ButtonBook.serializer(), ButtonBook(buttons = buttons))

/**
 * Parse [text] and merge it into [current] according to [mode].
 *
 * Imported buttons always get fresh ids. Ids only have to be unique within one
 * phone, and reusing them would let an imported pack silently overwrite an
 * existing button that happens to share one — losing work with no way back.
 */
fun importButtonPack(
    text: String,
    current: List<CanButton>,
    mode: ImportMode,
): ImportResult {
    val book = try {
        transferJson.decodeFromString(ButtonBook.serializer(), text)
    } catch (e: Exception) {
        return ImportResult.Failed("Not a valid button pack: ${e.message ?: "could not be read"}")
    }

    if (book.version > CURRENT_BUTTON_BOOK_VERSION) {
        return ImportResult.Failed(
            "This pack was written by a newer version of the app " +
                "(format ${book.version}, this app understands $CURRENT_BUTTON_BOOK_VERSION). Update first.",
        )
    }
    if (book.buttons.isEmpty()) {
        return ImportResult.Failed("That pack contains no buttons.")
    }

    // Deterministic ids rather than random ones, so importing the same pack into
    // the same state twice is reproducible and testable. Each id is claimed as it
    // is handed out, otherwise two buttons could be pushed onto the same free
    // slot by a collision and one would overwrite the other on save.
    val taken = (current.map { it.id } + book.buttons.map { it.id }).toMutableSet()
    var next = 0
    val imported = book.buttons.map { button ->
        while ("imported-$next" in taken) next++
        val id = "imported-$next"
        taken += id
        button.copy(id = id)
    }

    return ImportResult.Ok(
        buttons = when (mode) {
            ImportMode.ADD -> current + imported
            ImportMode.REPLACE -> imported
        },
        imported = imported.size,
        invalidFrames = imported.count { !it.isSendable },
    )
}

/** Filename offered when exporting, e.g. `ev-can-buttons-4.json`. */
fun exportFileName(buttonCount: Int): String = "ev-can-buttons-$buttonCount.json"
