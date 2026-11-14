package dev.redux.controller

import org.junit.Assert.*
import org.junit.Test

class MouseMotionTest {
    @Test fun slowMovementIsNotLostToRounding() {
        val motion = MouseMotion(speed = 1)
        var total = 0
        repeat(20) { total += motion.counts(0.3f, 0f).first }
        // 20 × 0.3 dp × 0.8 gain ≈ 4.8 counts; truncating each event would have sent nothing.
        assertEquals(5, total)
    }
    @Test fun higherSpeedAndFasterSwipesTravelFurther() {
        val slow = MouseMotion(speed = 1).counts(10f, 0f).first
        val fast = MouseMotion(speed = 5).counts(10f, 0f).first
        assertTrue(fast > slow)
        val gentle = MouseMotion(speed = 3).counts(2f, 0f).first
        val quick = MouseMotion(speed = 3).counts(40f, 0f).first
        assertTrue(quick > 20 * gentle)
    }
    @Test fun speedIsClampedToTheFiveSteps() {
        val motion = MouseMotion(speed = 9)
        assertEquals(5, motion.speed)
        motion.speed = 0
        assertEquals(1, motion.speed)
        assertEquals("Speed 2", MouseMotion.label(2))
    }
    @Test fun scrollDetentsFollowWindowsTouchpadDirectionAndAccumulate() {
        val motion = MouseMotion()
        assertEquals(0, motion.detents(10f))
        assertEquals(-1, motion.detents(20f))
        assertEquals(2, motion.detents(-60f))
        motion.reset()
        assertEquals(0, motion.detents(27f))
    }
}
