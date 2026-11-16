// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

/**
 * Finds the paired PC again after its address changed (DHCP gave it a new one, or the phone
 * is on another access point): the saved address first, then every PC that answered the
 * network search. [attempt] reconnects with the saved token and returns null for a PC that is
 * not the paired one; the caller's TLS check refuses any certificate other than the one
 * pinned at pairing, before the token is sent.
 */
object PcLocator {
    fun candidates(saved: String, discovered: List<String>): List<String> =
        (listOf(saved) + discovered).filter { it.isNotBlank() }.distinct()

    fun <T> relocate(saved: String, discovered: List<String>, attempt: (String) -> T?): Pair<String, T>? {
        for (address in candidates(saved, discovered).filter { it != saved }) {
            val reply = attempt(address) ?: continue
            return address to reply
        }
        return null
    }
}
