package com.evcantools.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun writesOnlyTheGivenKeys() {
        // A settings screen toggles one switch; everything else must stay
        // untouched, which the firmware does by ignoring absent arguments.
        val cmd = buildConfigCommand(mapOf(ConfigKey.AP_GATE to false))
        assertEquals("{\"cmd\":\"config\",\"args\":{\"apg\":false}}", cmd)
    }

    @Test
    fun encodesBooleansAndNumbers() {
        val cmd = buildConfigCommand(
            linkedMapOf(
                ConfigKey.HARDWARE to 2,
                ConfigKey.SPEED_AUTO to true,
                ConfigKey.HW3_SLEW_RATE to 25,
            ),
        )
        assertEquals(
            "{\"cmd\":\"config\",\"args\":{\"hw\":2,\"spa\":true,\"hw3SlewRate\":25}}",
            cmd,
        )
    }

    @Test
    fun rejectsUnsupportedValueTypes() {
        val e = assertFailsWith<IllegalArgumentException> {
            buildConfigCommand(mapOf(ConfigKey.HARDWARE to "HW4"))
        }
        assertTrue(e.message!!.contains("hw"), e.message)
    }

    @Test
    fun rejectsAnEmptyWrite() {
        assertFailsWith<IllegalArgumentException> { buildConfigCommand(emptyMap()) }
    }

    @Test
    fun parsesTheConfigReply() {
        val json = """
            {"ok":true,"config":{"hw":1,"speedProfile":2,"speedAuto":false,
             "injectionArmed":true,"pluginReplay":1,"pluginReplayMax":5,
             "apGate":true,"summonOnly":false,"nagMode":0,
             "hw3OffsetSlew":true,"hw3SlewRate":20,"ledBrightness":128}}
        """.trimIndent()

        val config = parseConfig(json).config!!

        assertEquals("HW3", config.hardwareLabel)
        assertTrue(config.injectionArmed)
        assertTrue(config.hw3OffsetSlew)
        assertEquals(20, config.hw3SlewRate)
        assertEquals(5, config.pluginReplayMax)
    }

    @Test
    fun hardwareDecidesTheSelectableRanges() {
        // Mirrors ctrlApplyConfig: HW4 has five speed profiles, and Nag Mode C
        // (3) is refused there, so the app must not offer it either.
        val hw4 = DeviceConfig(hw = 2)
        assertEquals(0..4, hw4.speedProfileRange)
        assertEquals(0..2, hw4.nagModeRange)

        val hw3 = DeviceConfig(hw = 1)
        assertEquals(0..2, hw3.speedProfileRange)
        assertEquals(0..3, hw3.nagModeRange)
    }

    @Test
    fun aRefusedWriteSurfacesTheDeviceMessage() {
        val refused = parseConfig(
            "{\"ok\":false,\"error\":\"Nag Mode C is blocked on HW4 after reported control faults\"}",
        )
        assertFalse(refused.ok)
        assertTrue(refused.error!!.contains("Nag Mode C"))
        assertEquals(null, refused.config)
    }

    @Test
    fun parsesTheStatsReply() {
        val json = """
            {"ok":true,"uptimeS":3600,"canFrames":12345,"canAgeMs":12,"txOk":7,
             "txFail":0,"freeHeap":80000,"gateEnabled":true,"gateAllowed":false,
             "apActive":true,"parked":false,"summoning":false,
             "gateReason":"blocked: autopilot active"}
        """.trimIndent()

        val stats = parseStats(json)

        assertEquals(12345, stats.canFrames)
        assertTrue(stats.apActive)
        assertFalse(stats.parked)
        assertFalse(stats.gateAllowed)
        assertEquals("blocked: autopilot active", stats.gateReason)
    }

    @Test
    fun statsToleratesFieldsAddedLater() {
        val stats = parseStats("{\"ok\":true,\"canFrames\":5,\"somethingNew\":true}")
        assertTrue(stats.ok)
        assertEquals(5, stats.canFrames)
    }
}
