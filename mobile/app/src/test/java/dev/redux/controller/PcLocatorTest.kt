// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcLocatorTest {
    @Test
    fun savedAddressComesFirstAndEachAddressOnce() {
        assertEquals(listOf("192.168.1.20", "192.168.1.31"), PcLocator.candidates("192.168.1.20", listOf("192.168.1.31", "192.168.1.20", "")))
    }

    @Test
    fun movesToTheAddressWhereThePairedPcAnswers() {
        val tried = mutableListOf<String>()
        // .31 is another Redux PC (its certificate is refused, so it yields null); .44 is ours.
        val found = PcLocator.relocate("192.168.1.20", listOf("192.168.1.31", "192.168.1.44")) { address ->
            tried += address
            if (address == "192.168.1.44") "session" else null
        }
        assertEquals("192.168.1.44" to "session", found)
        assertEquals(listOf("192.168.1.31", "192.168.1.44"), tried)
    }

    @Test
    fun theSavedAddressIsNotTriedAgainAndNothingFoundStaysPut() {
        val tried = mutableListOf<String>()
        assertNull(PcLocator.relocate("192.168.1.20", listOf("192.168.1.20")) { tried += it; "unexpected" })
        assertEquals(emptyList<String>(), tried)
        assertNull(PcLocator.relocate("192.168.1.20", emptyList<String>()) { "unexpected" })
    }
}
