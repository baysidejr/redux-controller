package dev.redux.controller

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProfilesTest {
    private val json = File("src/main/assets/profiles.json").readText()

    @Test fun shippedProfilesParseWithTwelveLabelsEachAndTheDefaultFirst() {
        val profiles = Profiles.parse(json)
        assertEquals(Profiles.DEFAULT, profiles.first().id)
        assertTrue(profiles.size >= 14)
        assertEquals(3, profiles.first { it.id == "jaguar-avp" }.plates.size)
        for (profile in profiles) for (plate in profile.plates) {
            assertEquals(12, plate.labels.size)
            if (plate.image != null) assertTrue("${plate.image} missing", File("src/main/assets/${plate.image}").isFile)
            assertTrue(plate.template in setOf("drawn", "render", "scan"))
        }
        assertEquals(profiles.map { it.id }.toSet().size, profiles.size)
    }

    @Test fun keyIndexFollowsRowsOfThreeOnBothTemplates() {
        for (template in listOf(KeypadGeometry.RENDER, KeypadGeometry.SCAN)) {
            for (key in 0 until 12) {
                val fx = template.columns[key % 3]; val fy = template.rows[key / 3]
                assertEquals("centre of key $key", key, KeypadGeometry.hit(template, fx, fy))
            }
            assertEquals(-1, KeypadGeometry.hit(template, 0.01f, 0.5f))
            assertEquals(-1, KeypadGeometry.hit(template, 0.5f, 0.995f))
            assertEquals(9, KeypadGeometry.hit(template, template.columns[0], template.rows[3]))
            assertEquals(11, KeypadGeometry.hit(template, template.columns[2], template.rows[3]))
        }
    }

    @Test fun keypadMaskPacksIntoButtonsSeventeenToTwentyEight() {
        val state = ControllerState().apply { keypad = (1 shl 0) or (1 shl 11) }
        val buttons = java.nio.ByteBuffer.wrap(state.report()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        assertEquals((1 shl 16) or (1 shl 27), buttons)
    }
}
