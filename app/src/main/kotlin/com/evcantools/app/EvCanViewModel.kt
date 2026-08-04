package com.evcantools.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evcantools.app.ble.ConnectionState
import com.evcantools.app.ble.EvCanBleClient
import com.evcantools.protocol.BleCommands
import com.evcantools.protocol.StatusReply
import com.evcantools.protocol.parseAck
import com.evcantools.protocol.parsePing
import com.evcantools.protocol.parseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the P0 smoke screen. */
data class UiState(
    val status: StatusReply? = null,
    val lastPongOk: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class EvCanViewModel(app: Application) : AndroidViewModel(app) {

    private val client = EvCanBleClient(app.applicationContext)

    val connectionState: StateFlow<ConnectionState> = client.state

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun connect() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(message = null)
            client.connect()
            if (client.state.value is ConnectionState.Ready) {
                refreshStatus()
            }
        }
    }

    fun refreshStatus() = run("Reading status") {
        val reply = client.request(BleCommands.STATUS)
        _ui.value = _ui.value.copy(status = parseStatus(reply))
    }

    fun ping() = run("Pinging") {
        val reply = client.request(BleCommands.PING)
        _ui.value = _ui.value.copy(lastPongOk = parsePing(reply).pong)
    }

    fun switchToWifi() = run("Switching to WiFi") {
        val ack = parseAck(client.request(BleCommands.WIFI_MODE))
        _ui.value = _ui.value.copy(
            message = if (ack.reboot) "Device is rebooting into WiFi mode…" else "WiFi switch: ok=${ack.ok}",
        )
    }

    fun disconnect() {
        client.close()
        _ui.value = UiState()
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (client.state.value !is ConnectionState.Ready) {
            _ui.value = _ui.value.copy(message = "Not connected")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            try {
                block()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(message = "$label failed: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
