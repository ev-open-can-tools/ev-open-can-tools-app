package com.evcantools.app.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ButtonTransferTest {

    private fun button(id: String, label: String, canId: Int = 0x3E1, data: String = "48A600") =
        CanButton(id = id, label = label, frames = listOf(ButtonFrame(id = canId, data = data)))

    // ---- round trip ------------------------------------------------------

    @Test
    fun exportedPackImportsBackUnchanged() {
        val original = listOf(button("a", "Unlock"), button("b", "Lock", 0x123, "AABBCC"))

        val result = importButtonPack(encodeButtonPack(original), emptyList(), ImportMode.ADD)

        val ok = result as ImportResult.Ok
        assertEquals(2, ok.imported)
        assertEquals(listOf("Unlock", "Lock"), ok.buttons.map { it.label })
        assertEquals(original.map { it.frames }, ok.buttons.map { it.frames })
        assertEquals(0, ok.invalidFrames)
    }

    // ---- merge modes -----------------------------------------------------

    @Test
    fun addKeepsExistingButtons() {
        val current = listOf(button("mine", "Mine"))
        val pack = encodeButtonPack(listOf(button("theirs", "Theirs")))

        val ok = importButtonPack(pack, current, ImportMode.ADD) as ImportResult.Ok

        assertEquals(listOf("Mine", "Theirs"), ok.buttons.map { it.label })
        assertEquals(1, ok.imported, "count reports the pack, not the total")
    }

    @Test
    fun replaceDropsExistingButtons() {
        val current = listOf(button("mine", "Mine"))
        val pack = encodeButtonPack(listOf(button("theirs", "Theirs")))

        val ok = importButtonPack(pack, current, ImportMode.REPLACE) as ImportResult.Ok

        assertEquals(listOf("Theirs"), ok.buttons.map { it.label })
    }

    // ---- ids -------------------------------------------------------------

    @Test
    fun importedButtonsNeverReuseAnExistingId() {
        // A pack exported from this very phone: every id already exists locally.
        val current = listOf(button("a", "Mine"), button("b", "Also mine"))
        val pack = encodeButtonPack(current)

        val ok = importButtonPack(pack, current, ImportMode.ADD) as ImportResult.Ok

        assertEquals(4, ok.buttons.size, "re-importing your own pack must duplicate, not overwrite")
        assertEquals(4, ok.buttons.map { it.id }.toSet().size, "ids must stay unique")
        current.forEach { existing ->
            assertEquals(1, ok.buttons.count { it.id == existing.id }, "existing id survived exactly once")
        }
    }

    @Test
    fun idsStayUniqueWhenTheImportedNamesAreAlreadyTaken() {
        // Importing twice: the second pass meets ids it handed out on the first.
        val first = importButtonPack(
            encodeButtonPack(listOf(button("x", "One"), button("y", "Two"))),
            emptyList(),
            ImportMode.ADD,
        ) as ImportResult.Ok

        val second = importButtonPack(
            encodeButtonPack(listOf(button("x", "One"), button("y", "Two"))),
            first.buttons,
            ImportMode.ADD,
        ) as ImportResult.Ok

        assertEquals(4, second.buttons.size)
        assertEquals(4, second.buttons.map { it.id }.toSet().size, "no id collision on a second import")
    }

    @Test
    fun importIsDeterministic() {
        val pack = encodeButtonPack(listOf(button("a", "One")))
        val one = importButtonPack(pack, emptyList(), ImportMode.ADD) as ImportResult.Ok
        val two = importButtonPack(pack, emptyList(), ImportMode.ADD) as ImportResult.Ok
        assertEquals(one.buttons.map { it.id }, two.buttons.map { it.id })
    }

    // ---- rejection -------------------------------------------------------

    @Test
    fun garbageIsRejectedWithAReadableMessage() {
        val failed = importButtonPack("not json at all", emptyList(), ImportMode.ADD)
        assertTrue(failed is ImportResult.Failed)
        assertTrue(
            (failed as ImportResult.Failed).message.startsWith("Not a valid button pack"),
            "message was: ${failed.message}",
        )
    }

    @Test
    fun emptyPackIsRejectedRatherThanSilentlyWipingButtons() {
        val current = listOf(button("mine", "Mine"))
        val empty = encodeButtonPack(emptyList())

        // Matters most in REPLACE mode, where accepting it would delete everything.
        assertTrue(importButtonPack(empty, current, ImportMode.REPLACE) is ImportResult.Failed)
        assertTrue(importButtonPack(empty, current, ImportMode.ADD) is ImportResult.Failed)
    }

    @Test
    fun packFromANewerFormatIsRefused() {
        val future = """{"version":${CURRENT_BUTTON_BOOK_VERSION + 1},"buttons":[]}"""
        val failed = importButtonPack(future, emptyList(), ImportMode.ADD) as ImportResult.Failed
        assertTrue(failed.message.contains("newer version"), failed.message)
    }

    @Test
    fun unknownFieldsAreToleratedSoOlderAppsCanReadNewerPacks() {
        val withExtras = """
            {"version":1,"buttons":[
              {"id":"a","label":"Unlock","colour":"red",
               "frames":[{"id":993,"data":"48A600","comment":"whatever"}]}
            ]}
        """.trimIndent()

        val ok = importButtonPack(withExtras, emptyList(), ImportMode.ADD) as ImportResult.Ok

        assertEquals("Unlock", ok.buttons.single().label)
    }

    // ---- broken frames ---------------------------------------------------

    @Test
    fun buttonsWithUnusableFramesAreKeptAndCounted() {
        // Hand-edited pack: the id is above the 11-bit range the drivers accept.
        val broken = """
            {"version":1,"buttons":[
              {"id":"a","label":"Broken","frames":[{"id":4096,"data":"48A600"}]},
              {"id":"b","label":"Fine","frames":[{"id":993,"data":"48A600"}]}
            ]}
        """.trimIndent()

        val ok = importButtonPack(broken, emptyList(), ImportMode.ADD) as ImportResult.Ok

        assertEquals(2, ok.imported, "a broken button is kept, so the user can repair it")
        assertEquals(1, ok.invalidFrames)
        assertTrue(ok.buttons.single { it.label == "Broken" }.isSendable.not())
        assertTrue(ok.buttons.single { it.label == "Fine" }.isSendable)
    }

    // ---- filename --------------------------------------------------------

    @Test
    fun exportFileNameCarriesTheCount() {
        assertEquals("ev-can-buttons-3.json", exportFileName(3))
        assertNotEquals(exportFileName(3), exportFileName(4))
    }
}
