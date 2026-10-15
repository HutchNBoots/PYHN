package com.groovebox.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStateTest {
    private val shift = ShiftState()

    @Test
    fun startsOff() {
        assertFalse(shift.active)
    }

    @Test
    fun tapLatchesOnAndOff() {
        shift.press(0); assertTrue(shift.active); shift.release(100)
        assertTrue(shift.active)
        shift.press(1000); shift.release(1100)
        assertFalse(shift.active)
    }

    @Test
    fun holdIsMomentary() {
        shift.press(0)
        assertTrue(shift.active)
        shift.release(400)
        assertFalse(shift.active)
    }

    @Test
    fun holdAtExactlyTheThresholdIsMomentary() {
        shift.press(0); shift.release(300)
        assertFalse(shift.active)
        shift.press(1000); shift.release(1299)
        assertTrue(shift.active)
    }

    @Test
    fun holdWhileLatchedReturnsToLatched() {
        shift.press(0); shift.release(50)
        shift.press(100)
        assertTrue(shift.active)
        shift.release(900)
        assertTrue(shift.active)
    }

    @Test
    fun releaseWithoutPressIsIgnored() {
        shift.release(100)
        assertFalse(shift.active)
        shift.press(200); shift.release(250); shift.release(300)
        assertTrue(shift.active)
    }

    @Test
    fun cancelReturnsToThePreviousState() {
        shift.press(0)
        shift.cancel()
        assertFalse(shift.active)
    }
}
