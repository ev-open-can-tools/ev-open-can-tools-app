package com.evcantools.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendCommandTest {

    // ---- encoding --------------------------------------------------------

    @Test
    fun singleFrameEncodesAsFirmwareExpects() {
        val cmd = buildSendCommand(
            listOf(CanFrameSpec(id = 0x3E1, data = byteArrayOf(0x48, 0xA6.toByte(), 0x00))),
        )
        assertEquals("{\"cmd\":\"send\",\"args\":{\"frames\":[{\"id\":\"0x3E1\",\"data\":\"48A600\"}]}}", cmd)
    }

    @Test
    fun busIsOmittedUnlessSet() {
        val withBus = buildSendCommand(listOf(CanFrameSpec(0x123, byteArrayOf(0x01), bus = 2)))
        assertTrue(withBus.contains("\"bus\":2"))

        val withoutBus = buildSendCommand(listOf(CanFrameSpec(0x123, byteArrayOf(0x01))))
        assertTrue(!withoutBus.contains("bus"), "default bus must not be sent")
    }

    @Test
    fun highBytesEncodeUnsigned() {
        // 0xFF must not come out as "FFFFFFFF" via signed Byte formatting.
        val cmd = buildSendCommand(listOf(CanFrameSpec(0x001, ByteArray(8) { 0xFF.toByte() })))
        assertTrue(cmd.contains("\"data\":\"FFFFFFFFFFFFFFFF\""), cmd)
    }

    @Test
    fun emptyBurstIsRejectedBeforeTheWire() {
        assertFailsWith<IllegalArgumentException> { buildSendCommand(emptyList()) }
    }

    @Test
    fun burstLongerThanFirmwareAcceptsIsRejected() {
        val frames = List(MAX_SEND_FRAMES + 1) { CanFrameSpec(0x100, byteArrayOf(0x00)) }
        assertFailsWith<IllegalArgumentException> { buildSendCommand(frames) }
    }

    @Test
    fun frameSpecRejectsOutOfRangeIdAndPayload() {
        assertFailsWith<IllegalArgumentException> { CanFrameSpec(0x800, byteArrayOf(0x00)) }
        assertFailsWith<IllegalArgumentException> { CanFrameSpec(-1, byteArrayOf(0x00)) }
        assertFailsWith<IllegalArgumentException> { CanFrameSpec(0x100, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { CanFrameSpec(0x100, ByteArray(9)) }
    }

    // ---- user input parsing ---------------------------------------------

    @Test
    fun canIdAcceptsTheFormsAUserTypes() {
        assertEquals(0x3E1, parseCanId("0x3E1"))
        assertEquals(0x3E1, parseCanId("3e1"))
        assertEquals(0x3E1, parseCanId("  0X3E1 "))
        assertEquals(993, parseCanId("#993"))
    }

    @Test
    fun canIdRejectsGarbageWithAReadableMessage() {
        val e = assertFailsWith<IllegalArgumentException> { parseCanId("zzz") }
        assertTrue(e.message!!.contains("0x3E1"), "message should show the expected form: ${e.message}")
        assertFailsWith<IllegalArgumentException> { parseCanId("") }
        assertFailsWith<IllegalArgumentException> { parseCanId("0x800") }
    }

    @Test
    fun canDataAcceptsSeparators() {
        val expected = byteArrayOf(0x48, 0xA6.toByte(), 0x00)
        assertContentEquals(expected, parseCanData("48A600"))
        assertContentEquals(expected, parseCanData("48 a6 00"))
        assertContentEquals(expected, parseCanData("48-A6-00"))
    }

    @Test
    fun canDataRejectsOddLengthNonHexAndOverlongPayloads() {
        assertFailsWith<IllegalArgumentException> { parseCanData("48A") }
        assertFailsWith<IllegalArgumentException> { parseCanData("48ZZ") }
        assertFailsWith<IllegalArgumentException> { parseCanData("00112233445566778899") }
        assertFailsWith<IllegalArgumentException> { parseCanData("   ") }
    }

    // ---- replies ---------------------------------------------------------

    @Test
    fun successfulSendReportsFrameCount() {
        val ack = parseAck("{\"ok\":true,\"sent\":2}")
        assertTrue(ack.ok)
        assertEquals(2, ack.sent)
        assertNull(ack.failureText)
    }

    @Test
    fun gatedSendExplainsWhichGateIsClosed() {
        val ack = parseAck("{\"ok\":false,\"error\":\"gated\",\"reason\":\"injection disabled\"}")
        assertEquals("gated — injection disabled", ack.failureText)
    }

    @Test
    fun badFrameRejectionPointsAtTheFrame() {
        val ack = parseAck(
            "{\"ok\":false,\"error\":\"bad frame\",\"reason\":\"id above 11-bit range\",\"index\":1}",
        )
        assertEquals("bad frame — id above 11-bit range (frame 2)", ack.failureText)
    }

    @Test
    fun partialBurstReportsHowManyMadeIt() {
        val ack = parseAck("{\"ok\":false,\"sent\":1,\"error\":\"tx failed\"}")
        assertEquals(1, ack.sent)
        assertEquals("tx failed", ack.failureText)
    }
}
