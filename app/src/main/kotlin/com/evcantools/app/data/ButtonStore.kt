package com.evcantools.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Button definitions, persisted as a JSON file in the app's private storage.
 *
 * The device stores none of this — buttons live on the phone, which is what
 * makes them shareable in P2 (the file *is* the export format).
 *
 * A plain file rather than DataStore: the whole document is small, is written
 * only on user edits, and staying dependency-free keeps `:app` lean.
 */
class ButtonStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val writeMutex = Mutex()

    private val _buttons = MutableStateFlow<List<CanButton>>(emptyList())
    val buttons: StateFlow<List<CanButton>> = _buttons.asStateFlow()

    /** Read the file into memory. Safe to call repeatedly. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            _buttons.value = emptyList()
            return@withContext
        }
        val book = runCatching { json.decodeFromString(ButtonBook.serializer(), file.readText()) }
            .getOrElse { e ->
                // Never lose the user's file to a parse error: keep it around for
                // recovery and start empty rather than crashing on launch.
                Log.w(TAG, "Could not read $FILE_NAME, keeping it as .bad", e)
                runCatching { file.copyTo(File(file.parentFile, "$FILE_NAME.bad"), overwrite = true) }
                ButtonBook()
            }
        _buttons.value = book.buttons
    }

    /** Insert [button], or replace the existing one with the same id. */
    suspend fun upsert(button: CanButton) {
        val current = _buttons.value
        val index = current.indexOfFirst { it.id == button.id }
        val next = if (index >= 0) {
            current.toMutableList().also { it[index] = button }
        } else {
            current + button
        }
        persist(next)
    }

    suspend fun delete(buttonId: String) {
        persist(_buttons.value.filterNot { it.id == buttonId })
    }

    private suspend fun persist(next: List<CanButton>) {
        _buttons.value = next
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                // Write-then-rename, so an interrupted write cannot truncate the
                // existing button set to nothing.
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(ButtonBook.serializer(), ButtonBook(buttons = next)))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        const val TAG = "ButtonStore"
        const val FILE_NAME = "buttons.json"
    }
}
