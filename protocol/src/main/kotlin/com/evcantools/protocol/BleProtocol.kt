package com.evcantools.protocol

/**
 * Wire framing for the EV-CAN-Tool BLE GATT service.
 *
 * The device firmware (see `include/ble/ble_service.h` in ev-open-can-tools)
 * exposes a Nordic-UART-style service:
 *
 *   Service   e7c10001-9a3f-4bd2-b1a7-0c0ffee00001
 *   Command   e7c10002-...  WRITE   app -> device, newline-framed JSON
 *   Response  e7c10003-...  READ    device -> app, paged (header + slice)
 *
 * A command is a JSON object `{"cmd":"...","args":{...}}` terminated by '\n'.
 *
 * A reply can exceed the 512-byte GATT attribute cap, so it is fetched in pages.
 * Every READ of the Response characteristic returns an 8-byte little-endian
 * header — `uint32 total`, `uint32 offset` — followed by up to [READ_PAGE_BYTES]
 * bytes of the reply. The client reads a page, and to advance the device-side
 * cursor to the next page it writes `{"cmd":"next"}` before reading again. A
 * fresh command resets the cursor to 0.
 *
 * This module is deliberately free of Android/BLE dependencies so the framing is
 * unit-testable on a plain JVM against a simulated firmware.
 */
object BleUuids {
    const val SERVICE = "e7c10001-9a3f-4bd2-b1a7-0c0ffee00001"
    const val COMMAND = "e7c10002-9a3f-4bd2-b1a7-0c0ffee00001"
    const val RESPONSE = "e7c10003-9a3f-4bd2-b1a7-0c0ffee00001"

    /** Advertised GAP device name. */
    const val DEVICE_NAME = "EVCANTool"
}

/** Must match `kBleReadPage` in the firmware. */
const val READ_PAGE_BYTES = 400

/** Header prefixing every Response READ: total reply length + this page's offset. */
const val PAGE_HEADER_BYTES = 8

/**
 * Largest single write to the Command characteristic.
 *
 * The firmware flattens one ATT write into a 256-byte stack buffer and rejects
 * anything longer outright, so a long command (a multi-frame `send`) must be
 * split. That is safe because the firmware appends every write to an accumulator
 * and only dispatches on the newline — splitting is purely a client concern.
 * 180 keeps a chunk inside one ATT packet at the negotiated MTU of 247.
 */
const val MAX_COMMAND_WRITE_BYTES = 180

/** Canonical command payloads (without the trailing newline). */
object BleCommands {
    const val STATUS = "{\"cmd\":\"status\"}"
    const val PING = "{\"cmd\":\"ping\"}"
    const val WIFI_MODE = "{\"cmd\":\"wifi_mode\"}"
    const val NEXT = "{\"cmd\":\"next\"}"

    /**
     * Flip the device's master injection switch. Without this the app cannot
     * clear its own most common rejection ("gated — injection disabled"), since
     * the web dashboard that also offers it is unreachable while in BLE mode.
     */
    fun setInjection(on: Boolean) = "{\"cmd\":\"inject\",\"args\":{\"on\":$on}}"
}

/** Frame a command payload for the Command characteristic (append newline, UTF-8). */
fun frameCommand(payload: String): ByteArray = (payload + "\n").toByteArray(Charsets.UTF_8)

/**
 * Frame [payload] and split it into writes of at most [MAX_COMMAND_WRITE_BYTES].
 * The device reassembles them, so only the last chunk carries the newline that
 * triggers dispatch.
 */
fun frameCommandChunks(payload: String): List<ByteArray> {
    val framed = frameCommand(payload)
    if (framed.size <= MAX_COMMAND_WRITE_BYTES) return listOf(framed)
    return framed.asList()
        .chunked(MAX_COMMAND_WRITE_BYTES) { it.toByteArray() }
}

/** Parsed 8-byte page header from a Response READ. */
data class PageHeader(val total: Long, val offset: Long)

private fun readU32Le(bytes: ByteArray, at: Int): Long {
    require(at + 4 <= bytes.size) { "buffer too short for uint32 at $at" }
    return (bytes[at].toLong() and 0xFF) or
        ((bytes[at + 1].toLong() and 0xFF) shl 8) or
        ((bytes[at + 2].toLong() and 0xFF) shl 16) or
        ((bytes[at + 3].toLong() and 0xFF) shl 24)
}

/** Split a Response READ into its header and payload slice. */
fun parsePage(page: ByteArray): Pair<PageHeader, ByteArray> {
    require(page.size >= PAGE_HEADER_BYTES) {
        "response page shorter than header (${page.size} < $PAGE_HEADER_BYTES)"
    }
    val header = PageHeader(total = readU32Le(page, 0), offset = readU32Le(page, 4))
    val slice = page.copyOfRange(PAGE_HEADER_BYTES, page.size)
    return header to slice
}

/**
 * Reassembles a full reply from successive [parsePage] results. Tolerates the
 * device returning the same page twice (Android may re-issue an ATT read); the
 * assembler is keyed on the absolute offset carried in each header.
 */
class ResponseAssembler {
    private val buffer = StringBuilder()
    private var total: Long = -1

    val isComplete: Boolean
        get() = total >= 0 && buffer.length.toLong() >= total

    /** Feed one page; returns true once the whole reply has arrived. */
    fun accept(page: ByteArray): Boolean {
        val (header, slice) = parsePage(page)
        if (total < 0) total = header.total
        // Only append the page that starts exactly where we are; ignore repeats
        // or out-of-order duplicates. The firmware only ever advances forward.
        if (header.offset == buffer.length.toLong()) {
            buffer.append(String(slice, Charsets.UTF_8))
        }
        return isComplete
    }

    fun result(): String {
        check(isComplete) { "reply not fully assembled ($total bytes expected, ${buffer.length} received)" }
        return buffer.toString()
    }
}

/**
 * Transport abstraction over the two GATT characteristics. Implemented on
 * Android with the platform BluetoothGatt; faked in tests with a simulated
 * firmware. Both calls suspend until the GATT operation acknowledges.
 */
interface BleTransport {
    /** Write bytes to the Command characteristic (write-with-response). */
    suspend fun writeCommand(bytes: ByteArray)

    /** Read one page from the Response characteristic (header + slice). */
    suspend fun readResponsePage(): ByteArray
}

/**
 * Run one request/response exchange: write [payload], then page through the
 * Response characteristic — reading a page and, while more remains, writing
 * `{"cmd":"next"}` to advance the cursor — until the whole reply is assembled.
 *
 * @return the reply JSON as a string.
 */
suspend fun BleTransport.request(payload: String): String {
    frameCommandChunks(payload).forEach { writeCommand(it) }
    val assembler = ResponseAssembler()
    while (true) {
        val page = readResponsePage()
        if (assembler.accept(page)) break
        writeCommand(frameCommand(BleCommands.NEXT))
    }
    return assembler.result()
}
