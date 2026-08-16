package com.evcantools.app

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evcantools.app.ble.ConnectionState
import com.evcantools.app.ble.EvCanBleClient
import com.evcantools.app.data.ButtonStore
import com.evcantools.app.data.CanButton
import com.evcantools.app.data.ImportMode
import com.evcantools.app.data.ImportResult
import com.evcantools.app.data.encodeButtonPack
import com.evcantools.app.data.exportFileName
import com.evcantools.app.data.importButtonPack
import com.evcantools.protocol.BleCommands
import com.evcantools.protocol.ConfigKey
import com.evcantools.protocol.DeviceConfig
import com.evcantools.protocol.READ_CONFIG_COMMAND
import com.evcantools.protocol.STATS_COMMAND
import com.evcantools.protocol.StatsReply
import com.evcantools.protocol.StatusReply
import com.evcantools.protocol.buildConfigCommand
import com.evcantools.protocol.buildSendCommand
import com.evcantools.protocol.parseAck
import com.evcantools.protocol.parseConfig
import com.evcantools.protocol.parsePing
import com.evcantools.protocol.parseStats
import com.evcantools.protocol.parseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class UiState(
    val status: StatusReply? = null,
    val lastPongOk: Boolean? = null,
    val busy: Boolean = false,
    /** Set on failure — shown in the error colour. */
    val message: String? = null,
    /** Set on success — shown neutrally, e.g. "Sent 2 frames". */
    val notice: String? = null,
    /** Id of the button currently being sent, for per-tile feedback. */
    val sendingButtonId: String? = null,
    val editing: Boolean = false,
    /** A read, valid pack waiting for the user to choose how to merge it. */
    val pendingImport: PendingImport? = null,
    val config: DeviceConfig? = null,
    val stats: StatsReply? = null,
    val showSettings: Boolean = false,
)

/** @param buttonCount how many buttons the file holds, shown in the merge prompt. */
data class PendingImport(val text: String, val buttonCount: Int)

class EvCanViewModel(app: Application) : AndroidViewModel(app) {

    private val client = EvCanBleClient(app.applicationContext)
    private val store = ButtonStore(app.applicationContext)

    val connectionState: StateFlow<ConnectionState> = client.state
    val buttons: StateFlow<List<CanButton>> = store.buttons

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    // ---- connection ------------------------------------------------------

    fun connect() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(message = null, notice = null)
            client.connect()
            if (client.state.value is ConnectionState.Ready) {
                refreshStatus()
            }
        }
    }

    fun disconnect() {
        client.close()
        _ui.value = UiState(editing = _ui.value.editing)
    }

    // ---- device commands -------------------------------------------------

    fun refreshStatus() = run("Reading status") {
        val reply = client.request(BleCommands.STATUS)
        _ui.value = _ui.value.copy(status = parseStatus(reply))
    }

    fun ping() = run("Pinging") {
        val reply = client.request(BleCommands.PING)
        _ui.value = _ui.value.copy(lastPongOk = parsePing(reply).pong)
    }

    /**
     * Flip the device's master injection switch. Offered in the app because the
     * dashboard that also offers it is unreachable in BLE mode — without this,
     * "gated — injection disabled" would be a dead end.
     */
    fun setInjection(on: Boolean) = run("Switching injection ${if (on) "on" else "off"}") {
        val ack = parseAck(client.request(BleCommands.setInjection(on)))
        if (ack.ok) {
            // Re-read rather than trusting the echo: the other gates may still be
            // closed, and the status card should show the real state.
            _ui.value = _ui.value.copy(status = parseStatus(client.request(BleCommands.STATUS)))
        } else {
            _ui.value = _ui.value.copy(message = "Injection switch rejected: ${ack.failureText}")
        }
    }

    // ---- settings --------------------------------------------------------

    fun openSettings() {
        _ui.value = _ui.value.copy(showSettings = true)
        loadSettings()
    }

    fun closeSettings() {
        _ui.value = _ui.value.copy(showSettings = false)
    }

    fun loadSettings() = run("Reading settings") {
        val config = parseConfig(client.request(READ_CONFIG_COMMAND))
        val stats = parseStats(client.request(STATS_COMMAND))
        _ui.value = _ui.value.copy(config = config.config ?: _ui.value.config, stats = stats)
    }

    /**
     * Write one setting. The device validates, clamps and may refuse — Nag Mode
     * C is blocked on HW4, the speed profile range depends on the hardware — and
     * echoes back what it stored, so the reply is taken as the new truth rather
     * than assuming the request went through as sent.
     */
    fun setConfig(key: ConfigKey, value: Any) = run("Changing ${key.wire}") {
        val reply = parseConfig(client.request(buildConfigCommand(mapOf(key to value))))
        if (reply.ok && reply.config != null) {
            _ui.value = _ui.value.copy(config = reply.config, message = null)
        } else {
            // Re-read, so the UI snaps back to what the device actually holds
            // instead of showing a control the device rejected.
            val current = parseConfig(client.request(READ_CONFIG_COMMAND))
            _ui.value = _ui.value.copy(
                config = current.config ?: _ui.value.config,
                message = reply.error ?: "Device rejected the change",
            )
        }
    }

    fun switchToWifi() = run("Switching to WiFi") {
        val ack = parseAck(client.request(BleCommands.WIFI_MODE))
        _ui.value = _ui.value.copy(
            notice = if (ack.reboot) "Device is rebooting into WiFi mode…" else null,
            message = if (ack.reboot) null else "WiFi switch rejected: ${ack.failureText ?: "unknown"}",
        )
    }

    /**
     * Send one button's frames. The device applies its own injection gates, so a
     * rejection here is normal (car not in park, injection switched off) and is
     * reported verbatim rather than swallowed.
     */
    fun send(button: CanButton) {
        val specs = button.frames.mapNotNull { it.toSpecOrNull() }
        if (specs.size != button.frames.size || specs.isEmpty()) {
            _ui.value = _ui.value.copy(message = "\"${button.label}\" has invalid frames — edit it first")
            return
        }
        val payload = try {
            buildSendCommand(specs)
        } catch (e: IllegalArgumentException) {
            _ui.value = _ui.value.copy(message = e.message ?: "Cannot send this button")
            return
        }
        run("Sending \"${button.label}\"", buttonId = button.id) {
            val ack = parseAck(client.request(payload))
            _ui.value = if (ack.ok) {
                _ui.value.copy(notice = "Sent ${ack.sent ?: specs.size} frame(s)", message = null)
            } else {
                _ui.value.copy(message = "\"${button.label}\": ${ack.failureText}", notice = null)
            }
        }
    }

    // ---- button editing --------------------------------------------------

    fun setEditing(editing: Boolean) {
        _ui.value = _ui.value.copy(editing = editing)
    }

    fun saveButton(button: CanButton) {
        viewModelScope.launch { store.upsert(button) }
    }

    fun deleteButton(buttonId: String) {
        viewModelScope.launch { store.delete(buttonId) }
    }

    /** A blank button with a fresh id, for the "add" flow. */
    fun newButton(): CanButton = CanButton(id = UUID.randomUUID().toString(), label = "")

    // ---- import / export -------------------------------------------------

    /** Name to offer in the system "save as" sheet. */
    fun suggestedExportFileName(): String = exportFileName(buttons.value.size)

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(encodeButtonPack(buttons.value).toByteArray())
                    } ?: error("could not open the file for writing")
                }
                _ui.value = _ui.value.copy(notice = "Exported ${buttons.value.size} button(s)", message = null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(message = "Export failed: ${e.message}")
            }
        }
    }

    /**
     * Write the pack to a cache file and hand back a shareable URI. Returns null
     * on failure, having already set the error message.
     */
    fun preparePackToShare(): Uri? = try {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        // One fixed name, overwritten each time: these are throwaway copies and
        // accumulating them would leak the user's buttons into the cache.
        val file = File(dir, suggestedExportFileName())
        dir.listFiles()?.forEach { if (it != file) it.delete() }
        file.writeText(encodeButtonPack(buttons.value))
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    } catch (e: Exception) {
        _ui.value = _ui.value.copy(message = "Could not prepare the pack: ${e.message}")
        null
    }

    /** Read [uri] and remember it until the user picks a merge mode. */
    fun stageImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("could not open the file")
                }
                // Parse now, before asking anything: no point offering a choice
                // between two ways of applying a file that cannot be read.
                when (val probe = importButtonPack(text, buttons.value, ImportMode.ADD)) {
                    is ImportResult.Failed ->
                        _ui.value = _ui.value.copy(message = probe.message, pendingImport = null)
                    is ImportResult.Ok ->
                        _ui.value = _ui.value.copy(
                            pendingImport = PendingImport(text, probe.imported),
                            message = null,
                        )
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(message = "Import failed: ${e.message}")
            }
        }
    }

    fun cancelImport() {
        _ui.value = _ui.value.copy(pendingImport = null)
    }

    fun applyImport(mode: ImportMode) {
        val pending = _ui.value.pendingImport ?: return
        viewModelScope.launch {
            when (val result = importButtonPack(pending.text, buttons.value, mode)) {
                is ImportResult.Failed ->
                    _ui.value = _ui.value.copy(message = result.message, pendingImport = null)

                is ImportResult.Ok -> {
                    store.replaceAll(result.buttons)
                    val warning = if (result.invalidFrames > 0) {
                        " — ${result.invalidFrames} of them have frames this app cannot send, edit them before use"
                    } else {
                        ""
                    }
                    _ui.value = _ui.value.copy(
                        pendingImport = null,
                        notice = "Imported ${result.imported} button(s)$warning",
                        message = null,
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _ui.value = _ui.value.copy(message = null, notice = null)
    }

    // ---- helpers ---------------------------------------------------------

    private fun run(label: String, buttonId: String? = null, block: suspend () -> Unit) {
        if (client.state.value !is ConnectionState.Ready) {
            _ui.value = _ui.value.copy(message = "Not connected")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null, notice = null, sendingButtonId = buttonId)
            try {
                block()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(message = "$label failed: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = false, sendingButtonId = null)
            }
        }
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
