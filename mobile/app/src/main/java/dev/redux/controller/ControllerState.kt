// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One complete HID state. Buttons 17–28 belong to the Jaguar keypad. */
class ControllerState {
    var buttons = 0
    var lx = 0; var ly = 0; var rx = 0; var ry = 0
    var lt = 0; var rt = 0
    var hat = 0
    var keypad = 0

    fun report(): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(lx.toShort()); putShort(ly.toShort()); putShort(rx.toShort()); putShort(ry.toShort())
        putShort(lt.toShort()); putShort(rt.toShort())
        putInt(buttons or ((keypad and 0xfff) shl 16)); put(hat.toByte())
    }.array()

    fun release() { buttons = 0; lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0; hat = 0; keypad = 0 }
}
