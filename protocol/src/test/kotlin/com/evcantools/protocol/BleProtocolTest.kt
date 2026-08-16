package com.evcantools.protocol

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A byte-for-byte simulation of the firmware's Response paging (mirrors
 * `bleCmdWriteCb` / `bleRespAccessCb` in `include/ble/ble_service.h`): a written
 * command replaces the response buffer and resets the cursor; `{"cmd":"next"}`
 * advances the cursor by one page; a READ returns `header(total,cursor) + slice`
 * without advancing.
 */
private class FakeFirmware(private val dispatch: (String) -> String) : BleTransport {
    private var response: ByteArray = "{}".toByteArray(Charsets.UTF_8)
    private var cursor: Int = 0

    /** Mirrors `bleCmdBuf`: writes accumulate, dispatch happens on the newline. */
    private val accumulator = StringBuilder()

    val commands = mutableListOf<String>()
    var writes: Int = 0
        private set
    var reads: Int = 0
        private set

    override suspend fun writeCommand(bytes: ByteArray) {
        writes++
        // The device rejects any single write that overflows its flat buffer.
        check(bytes.size <= 255) { "write of ${bytes.size} bytes exceeds the firmware's 255-byte limit" }
        accumulator.append(String(bytes, Charsets.UTF_8))
        while (true) {
            val nl = accumulator.indexOf("\n")
            if (nl < 0) break
            val line = accumulator.substring(0, nl).trimEnd('\r')
            accumulator.delete(0, nl + 1)
            if (line.isEmpty()) continue
            commands += line
            if (line == BleCommands.NEXT) {
                val total = response.size
                cursor = if (cursor + READ_PAGE_BYTES < total) cursor + READ_PAGE_BYTES else total
            } else {
                response = dispatch(line).toByteArray(Charsets.UTF_8)
                cursor = 0
            }
        }
    }

    override suspend fun readResponsePage(): ByteArray {
        reads++
        val total = response.size
        val off = cursor.coerceAtMost(total)
        val n = (total - off).coerceAtMost(READ_PAGE_BYTES)
        val out = ByteArray(PAGE_HEADER_BYTES + n)
        writeU32Le(out, 0, total)
        writeU32Le(out, 4, off)
        response.copyInto(out, PAGE_HEADER_BYTES, off, off + n)
        return out
    }

    private fun writeU32Le(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

class BleProtocolTest {

    @Test
    fun frameCommandAppendsNewline() {
        assertContentEquals(
            "{\"cmd\":\"ping\"}\n".toByteArray(Charsets.UTF_8),
            frameCommand(BleCommands.PING),
        )
    }

    @Test
    fun parsePageReadsLittleEndianHeader() {
        // total = 0x01020304, offset = 0x00000190 (400)
        val page = byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x90.toByte(), 0x01, 0x00, 0x00, 'h'.code.toByte())
        val (header, slice) = parsePage(page)
        assertEquals(0x01020304L, header.total)
        assertEquals(400L, header.offset)
        assertContentEquals(byteArrayOf('h'.code.toByte()), slice)
    }

    @Test
    fun singlePageReplyNeedsNoNext() = runBlocking {
        val status = "{\"ok\":true,\"dev\":false,\"ble\":true,\"hw\":2,\"inject\":false,\"injectActive\":false,\"uptimeS\":42}"
        val fw = FakeFirmware { cmd -> if (cmd == BleCommands.STATUS) status else "{\"ok\":false}" }

        val reply = fw.request(BleCommands.STATUS)

        assertEquals(status, reply)
        assertEquals(1, fw.reads, "small reply fits one page")
        assertEquals(listOf(BleCommands.STATUS), fw.commands, "no {\"cmd\":\"next\"} for a one-page reply")

        val parsed = parseStatus(reply)
        assertTrue(parsed.ok)
        assertTrue(parsed.ble)
        assertEquals(2, parsed.hw)
        assertEquals("HW4", parsed.hardwareLabel)
        assertEquals(42L, parsed.uptimeS)
    }

    @Test
    fun multiPageReplyIsReassembledExactly() = runBlocking {
        // 950 bytes -> pages of 400 + 400 + 150, so two "next" writes.
        val big = buildString { repeat(950) { append(('a' + (it % 26))) } }
        val fw = FakeFirmware { big }

        val reply = fw.request("{\"cmd\":\"dump\"}")

        assertEquals(big, reply)
        assertEquals(3, fw.reads)
        assertEquals(
            listOf("{\"cmd\":\"dump\"}", BleCommands.NEXT, BleCommands.NEXT),
            fw.commands,
        )
    }

    @Test
    fun exactPageBoundaryReplyTerminates() = runBlocking {
        // Exactly 400 bytes: the first page carries all of it and total==offset+len,
        // so the assembler completes without an extra empty page.
        val exact = "x".repeat(READ_PAGE_BYTES)
        val fw = FakeFirmware { exact }

        val reply = fw.request("{\"cmd\":\"dump\"}")

        assertEquals(exact, reply)
        assertEquals(1, fw.reads)
    }

    @Test
    fun pingRoundTrips() = runBlocking {
        val fw = FakeFirmware { "{\"ok\":true,\"pong\":true}" }
        val ping = parsePing(fw.request(BleCommands.PING))
        assertTrue(ping.ok)
        assertTrue(ping.pong)
    }

    @Test
    fun shortCommandTakesASingleWrite() = runBlocking {
        val fw = FakeFirmware { "{\"ok\":true,\"pong\":true}" }
        fw.request(BleCommands.PING)
        assertEquals(1, fw.writes)
    }

    @Test
    fun longSendCommandIsSplitIntoWritesTheDeviceAccepts() = runBlocking {
        val frames = List(MAX_SEND_FRAMES) { i ->
            CanFrameSpec(id = 0x100 + i, data = ByteArray(8) { b -> (i + b).toByte() })
        }
        val payload = buildSendCommand(frames)
        assertTrue(
            payload.length > MAX_COMMAND_WRITE_BYTES,
            "a full burst should need splitting, but is only ${payload.length} bytes",
        )

        // FakeFirmware asserts the 255-byte per-write cap and reassembles on '\n',
        // exactly as bleCmdWriteCb does.
        val fw = FakeFirmware { "{\"ok\":true,\"sent\":${frames.size}}" }
        val ack = parseAck(fw.request(payload))

        assertEquals(listOf(payload), fw.commands, "device must see one command, byte-identical")
        assertTrue(fw.writes > 1, "long command should span several writes, took ${fw.writes}")
        assertEquals(frames.size, ack.sent)
    }

    @Test
    fun assemblerIgnoresDuplicatedPage() {
        val assembler = ResponseAssembler()
        val first = pageOf(total = 6, offset = 0, "abc")
        assertFalse(assembler.accept(first))
        // A re-issued identical read must not double-append.
        assertFalse(assembler.accept(first))
        assertTrue(assembler.accept(pageOf(total = 6, offset = 3, "def")))
        assertEquals("abcdef", assembler.result())
    }

    @Test
    fun statusToleratesUnknownFields() {
        val json = "{\"ok\":true,\"dev\":true,\"hw\":1,\"inject\":true,\"injectActive\":true,\"newFutureField\":123}"
        val parsed = parseStatus(json)
        assertTrue(parsed.dev)
        assertEquals("HW3", parsed.hardwareLabel)
        assertTrue(parsed.injectActive)
    }

    private fun pageOf(total: Int, offset: Int, payload: String): ByteArray {
        val body = payload.toByteArray(Charsets.UTF_8)
        val out = ByteArray(PAGE_HEADER_BYTES + body.size)
        out[0] = (total and 0xFF).toByte()
        out[1] = ((total ushr 8) and 0xFF).toByte()
        out[4] = (offset and 0xFF).toByte()
        out[5] = ((offset ushr 8) and 0xFF).toByte()
        body.copyInto(out, PAGE_HEADER_BYTES)
        return out
    }
}
