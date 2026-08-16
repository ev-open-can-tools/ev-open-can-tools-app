package com.evcantools.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.evcantools.protocol.BleTransport
import com.evcantools.protocol.BleUuids
import com.evcantools.protocol.request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState

    /** The system pairing dialog is up — the user enters the dashboard passkey. */
    data object Pairing : ConnectionState
    data object Preparing : ConnectionState
    data object Ready : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Talks to an EV-CAN-Tool over BLE using the Android platform GATT API.
 *
 * The command/response characteristics require an encrypted, authenticated link,
 * so the device must be bonded first; we proactively create the bond, which
 * surfaces the system passkey dialog. The user reads that passkey from the
 * device's web dashboard. GATT operations are serialized through a mutex and
 * bridged to coroutines via per-operation deferreds.
 */
@SuppressLint("MissingPermission")
class EvCanBleClient(private val context: Context) : BleTransport {

    private val tag = "EvCanBleClient"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val PREPARE_TIMEOUT_MS = 20_000L

        /**
         * Cap on a single GATT read or write.
         *
         * Without one, a callback that never arrives -- a device that rebooted
         * mid-request, a stale handle after a reconnect -- suspends the caller
         * forever. The UI then sits at "Working…" with every control disabled and
         * no way out but killing the app. Ten seconds is far longer than a round
         * trip needs; it only ever fires when something is genuinely lost.
         */
        const val OPERATION_TIMEOUT_MS = 10_000L
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var respChar: BluetoothGattCharacteristic? = null

    // One in-flight GATT operation at a time.
    private val opMutex = Mutex()
    private val requestMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null

    // Connection lifecycle handshakes.
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var bondDeferred: CompletableDeferred<Unit>? = null
    private var mtuDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Unit>? = null

    // ---- Scanning --------------------------------------------------------

    private var scanCallback: ScanCallback? = null

    /** Scan for the first EV-CAN-Tool, connect, bond, and prepare its GATT. */
    suspend fun connect(
        scanTimeoutMs: Long = 30_000,
        bondTimeoutMs: Long = 120_000,
    ) {
        val adapter = adapter ?: run {
            fail("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is off")
            return
        }
        try {
            // Each phase gets its own timeout. Critically, bonding is NOT bounded
            // by the short scan/connect budget: the user is typing the passkey
            // into the system dialog, and a single overall timeout was tearing
            // the link down mid-entry (dialog vanished, "wrong passkey").
            val device = withTimeout(scanTimeoutMs) { scanForDevice() }
            withTimeout(CONNECT_TIMEOUT_MS) { connectGatt(device) }
            ensureBonded(device, bondTimeoutMs)
            withTimeout(PREPARE_TIMEOUT_MS) { requestMtu() }
            withTimeout(PREPARE_TIMEOUT_MS) { discoverServices() }
            _state.value = ConnectionState.Ready
        } catch (e: TimeoutCancellationException) {
            fail("Timed out while connecting")
            close()
        } catch (e: Exception) {
            fail(e.message ?: "Connection failed")
            close()
        }
    }

    private suspend fun scanForDevice(): BluetoothDevice {
        _state.value = ConnectionState.Scanning
        val scanner = adapter?.bluetoothLeScanner ?: error("No BLE scanner")
        val found = CompletableDeferred<BluetoothDevice>()
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(BleUuids.DEVICE_NAME).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!found.isCompleted) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) found.completeExceptionally(RuntimeException("Scan failed ($errorCode)"))
            }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        try {
            return found.await()
        } finally {
            scanner.stopScan(cb)
            scanCallback = null
        }
    }

    // ---- Connect / bond / prepare ---------------------------------------

    private suspend fun connectGatt(device: BluetoothDevice) {
        _state.value = ConnectionState.Connecting
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        deferred.await()
    }

    private suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        _state.value = ConnectionState.Pairing
        val deferred = CompletableDeferred<Unit>()
        bondDeferred = deferred
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDING) {
                device.createBond()
            }
            // Generous window: the user is reading the passkey off the dashboard
            // and typing it into the system dialog. The BLE stack enforces its own
            // ~30s SMP timeout per attempt; we simply must not give up sooner.
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            runCatching { context.unregisterReceiver(bondReceiver) }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            when (newState) {
                BluetoothDevice.BOND_BONDED -> bondDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
                BluetoothDevice.BOND_NONE ->
                    bondDeferred?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(RuntimeException("Pairing failed or was rejected"))
            }
        }
    }

    private suspend fun requestMtu() {
        _state.value = ConnectionState.Preparing
        val deferred = CompletableDeferred<Unit>()
        mtuDeferred = deferred
        if (gatt?.requestMtu(247) != true) {
            // Older stacks may reject; carry on with the default MTU.
            deferred.complete(Unit)
        }
        deferred.await()
    }

    private suspend fun discoverServices() {
        val deferred = CompletableDeferred<Unit>()
        servicesDeferred = deferred
        gatt?.discoverServices() ?: error("Not connected")
        deferred.await()
        val service = gatt?.getService(UUID.fromString(BleUuids.SERVICE))
            ?: error("EV-CAN-Tool service not found")
        cmdChar = service.getCharacteristic(UUID.fromString(BleUuids.COMMAND))
            ?: error("Command characteristic not found")
        respChar = service.getCharacteristic(UUID.fromString(BleUuids.RESPONSE))
            ?: error("Response characteristic not found")
    }

    // ---- BleTransport (serialized GATT ops) -----------------------------

    override suspend fun writeCommand(bytes: ByteArray) = opMutex.withLock {
        val g = gatt ?: error("Not connected")
        val ch = cmdChar ?: error("Command characteristic missing")
        val deferred = CompletableDeferred<Unit>()
        pendingWrite = deferred
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            pendingWrite = null
            error("Failed to start command write")
        }
        awaitOperation(deferred) { pendingWrite = null }
    }

    override suspend fun readResponsePage(): ByteArray = opMutex.withLock {
        val g = gatt ?: error("Not connected")
        val ch = respChar ?: error("Response characteristic missing")
        val deferred = CompletableDeferred<ByteArray>()
        pendingRead = deferred
        if (!g.readCharacteristic(ch)) {
            pendingRead = null
            error("Failed to start response read")
        }
        awaitOperation(deferred) { pendingRead = null }
    }

    /**
     * Await one GATT callback, giving up rather than hanging. On expiry the
     * pending slot is cleared via [onTimeout], so a callback arriving later
     * cannot complete an operation whose caller has already moved on.
     */
    private suspend fun <T> awaitOperation(
        deferred: CompletableDeferred<T>,
        onTimeout: () -> Unit,
    ): T = try {
        withTimeout(OPERATION_TIMEOUT_MS) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
        onTimeout()
        throw RuntimeException("The device did not answer within ${OPERATION_TIMEOUT_MS / 1000}s")
    }

    // ---- High-level commands --------------------------------------------

    suspend fun request(payload: String): String = requestMutex.withLock {
        (this as BleTransport).request(payload)
    }

    // ---- GATT callback ---------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    connectDeferred?.takeIf { !it.isCompleted }?.complete(Unit)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = RuntimeException("Disconnected (status $status)")
                    connectDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(err)
                    failPending(err)
                    if (_state.value is ConnectionState.Ready) {
                        _state.value = ConnectionState.Error("Disconnected")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
            } else {
                servicesDeferred?.takeIf { !it.isCompleted }
                    ?.completeExceptionally(RuntimeException("Service discovery failed ($status)"))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val d = pendingWrite ?: return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) d.complete(Unit)
            else d.completeExceptionally(RuntimeException("Write failed ($status)"))
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = completeRead(value, status)

        // Pre-33 path: read the value off the characteristic.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(characteristic.value ?: ByteArray(0), status)
            }
        }
    }

    private fun completeRead(value: ByteArray, status: Int) {
        val d = pendingRead ?: return
        pendingRead = null
        if (status == BluetoothGatt.GATT_SUCCESS) d.complete(value.copyOf())
        else d.completeExceptionally(RuntimeException("Read failed ($status)"))
    }

    // ---- Teardown --------------------------------------------------------

    private fun failPending(e: Throwable) {
        pendingWrite?.takeIf { !it.isCompleted }?.completeExceptionally(e)
        pendingRead?.takeIf { !it.isCompleted }?.completeExceptionally(e)
        pendingWrite = null
        pendingRead = null
    }

    private fun fail(message: String) {
        Log.w(tag, message)
        _state.value = ConnectionState.Error(message)
    }

    fun close() {
        // Fail anything in flight first. Tearing the GATT down normally triggers
        // the disconnect callback which would do this, but on an already-dead
        // link that callback may never come -- and then Disconnect, the one
        // control still enabled while the UI is stuck, would not unstick it.
        failPending(RuntimeException("Disconnected"))
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        cmdChar = null
        respChar = null
        if (_state.value is ConnectionState.Ready) _state.value = ConnectionState.Idle
    }
}
