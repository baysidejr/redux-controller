package dev.redux.controller

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.redux.controller.dish.*

class ControllerStateTest {
    @Test fun physicalAndKeypadButtonsRemainIndependent() {
        val state = ControllerState().apply { buttons = 1; keypad = (1 shl 0) or (1 shl 11); lx = -32767; rt = 65535; hat = 8 }
        val packet = ByteBuffer.wrap(state.report()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(17, packet.capacity())
        assertEquals(-32767, packet.getShort(0).toInt())
        assertEquals(65535, packet.getShort(10).toInt() and 65535)
        assertEquals(1 or (1 shl 16) or (1 shl 27), packet.getInt(12))
        assertEquals(8, packet.get(16).toInt())
    }
    @Test fun releasingTouchDoesNotReleasePhysicalHold() {
        val state = ControllerState().apply { buttons = 5; keypad = 3 }
        state.keypad = state.keypad and 1.inv()
        assertEquals(5 or (2 shl 16), ByteBuffer.wrap(state.report()).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
    }
    @Test fun disconnectReleasesEveryControl() {
        val state = ControllerState().apply { buttons = 65535; keypad = 4095; lx = 3000; ly = -1000; rt = 65535; hat = 2 }
        state.release()
        assertArrayEquals(ByteArray(17), state.report())
    }
    @Test fun mouseSectionFollowsTheControllerReportAndConsumesItsMotion() {
        val state = ControllerState().apply { buttons = 1; keypad = 1; addMouseMotion(5, -3, 2); mouseButtons = ControllerState.MOUSE_LEFT or ControllerState.MOUSE_RIGHT }
        val plain = state.report()
        assertEquals(17, plain.size)
        val packet = ByteBuffer.wrap(state.report(true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(23, packet.capacity())
        assertEquals(1 or (1 shl 16), packet.getInt(12))
        assertEquals(5, packet.getShort(17).toInt()); assertEquals(-3, packet.getShort(19).toInt())
        assertEquals(2, packet.get(21).toInt()); assertEquals(3, packet.get(22).toInt())
        // Motion was delivered once; buttons stay held until released.
        val next = ByteBuffer.wrap(state.report(true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, next.getShort(17).toInt()); assertEquals(0, next.getShort(19).toInt()); assertEquals(0, next.get(21).toInt()); assertEquals(3, next.get(22).toInt())
    }
    @Test fun oversizedMotionIsSplitAcrossPacketsAndUndeclaredButtonsNeverLeave() {
        val state = ControllerState().apply { addMouseMotion(40000, -40000, 300); mouseButtons = 0xff }
        val first = ByteBuffer.wrap(state.report(true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, first.getShort(17).toInt()); assertEquals(-32767, first.getShort(19).toInt()); assertEquals(127, first.get(21).toInt()); assertEquals(7, first.get(22).toInt())
        val second = ByteBuffer.wrap(state.report(true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40000 - 32767, second.getShort(17).toInt()); assertEquals(-(40000 - 32767), second.getShort(19).toInt()); assertEquals(127, second.get(21).toInt())
        assertEquals(300 - 2 * 127, ByteBuffer.wrap(state.report(true)).order(ByteOrder.LITTLE_ENDIAN).get(21).toInt())
    }
    @Test fun releasingMouseKeepsGamepadAndClearingMotionKeepsButtons() {
        val state = ControllerState().apply { buttons = 5; addMouseMotion(9, 9, 1); mouseButtons = ControllerState.MOUSE_MIDDLE }
        state.clearMouseMotion()
        assertEquals(ControllerState.MOUSE_MIDDLE, state.mouseButtons); assertEquals(0, state.mouseDx)
        state.releaseMouse()
        assertEquals(0, state.mouseButtons); assertEquals(5, state.buttons)
        state.addMouseMotion(1, 1); state.mouseButtons = 1; state.release()
        assertArrayEquals(ByteArray(23), state.report(true))
    }
    @Test fun dishMappingKeepsFaceButtonsAndDpadDistinct() {
        val packed = xusbToHid(0x1000 or 0x4000 or 1 or 8)
        assertEquals(5, hidButtonsOf(packed))
        assertEquals(2, hidHatOf(packed))
    }
}
