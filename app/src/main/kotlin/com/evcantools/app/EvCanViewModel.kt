package com.evcantools.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evcantools.app.ble.ConnectionState
import com.evcantools.app.ble.EvCanBleClient
import com.evcantools.app.data.ButtonStore
import com.evcantools.app.data.CanButton
import com.evcantools.protocol.BleCommands
import com.evcantools.protocol.StatusReply
import com.evcantools.protocol.buildSendCommand
import com.evcantools.protocol.parseAck
import com.evcantools.protocol.parsePing
import com.evcantools.protocol.parseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
)

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
