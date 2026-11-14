// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One complete HID state. Buttons 17–28 belong to the Jaguar keypad.
 *
 * The phone mouse (receiver 0.4) rides in six bytes after the controller report: relative
 * dx/dy counts and wheel detents accumulated since the last packet, plus held mouse buttons.
 * Motion is consumed by the report that carries it; buttons are state like every other control.
 */
class ControllerState {
    var buttons = 0
    var lx = 0; var ly = 0; var rx = 0; var ry = 0
    var lt = 0; var rt = 0
    var hat = 0
    var keypad = 0
    var mouseDx = 0; var mouseDy = 0; var mouseWheel = 0
    /** Bit 0 left, 1 right, 2 middle. */
    var mouseButtons = 0

    fun report(): ByteArray = report(false)

    /** The 17-byte controller report, or 23 bytes with the mouse section; including the mouse consumes its motion. */
    fun report(mouse: Boolean): ByteArray {
        val packet = ByteBuffer.allocate(if (mouse) 23 else 17).order(ByteOrder.LITTLE_ENDIAN)
        packet.putShort(lx.toShort()); packet.putShort(ly.toShort()); packet.putShort(rx.toShort()); packet.putShort(ry.toShort())
        packet.putShort(lt.toShort()); packet.putShort(rt.toShort())
        packet.putInt(buttons or ((keypad and 0xfff) shl 16)); packet.put(hat.toByte())
        if (mouse) {
            val dx = mouseDx.coerceIn(-32767, 32767); val dy = mouseDy.coerceIn(-32767, 32767); val wheel = mouseWheel.coerceIn(-127, 127)
            mouseDx -= dx; mouseDy -= dy; mouseWheel -= wheel
            packet.putShort(dx.toShort()); packet.putShort(dy.toShort()); packet.put(wheel.toByte()); packet.put((mouseButtons and MOUSE_BUTTONS).toByte())
        }
        return packet.array()
    }

    fun addMouseMotion(dx: Int, dy: Int, wheel: Int = 0) { mouseDx += dx; mouseDy += dy; mouseWheel += wheel }
    /** Drop pending motion without sending it, e.g. when the receiver cannot take a mouse. */
    fun clearMouseMotion() { mouseDx = 0; mouseDy = 0; mouseWheel = 0 }
    fun releaseMouse() { clearMouseMotion(); mouseButtons = 0 }

    fun release() { buttons = 0; lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0; hat = 0; keypad = 0; releaseMouse() }

    companion object {
        const val MOUSE_LEFT = 1; const val MOUSE_RIGHT = 2; const val MOUSE_MIDDLE = 4
        const val MOUSE_BUTTONS = 7
    }
}
