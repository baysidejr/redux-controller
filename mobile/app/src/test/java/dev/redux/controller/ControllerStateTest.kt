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
    @Test fun dishMappingKeepsFaceButtonsAndDpadDistinct() {
        val packed = xusbToHid(0x1000 or 0x4000 or 1 or 8)
        assertEquals(5, hidButtonsOf(packed))
        assertEquals(2, hidHatOf(packed))
    }
}
