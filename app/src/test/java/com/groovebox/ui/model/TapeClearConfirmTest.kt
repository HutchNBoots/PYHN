package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeClearConfirmTest {
    private val c = TapeClearConfirm(windowMs = 3_000)

    /** A single tap as the lanes report it: the touch-down at [downAt], then (after the double-tap wait) the tap. */
    private fun tapAt(lane: Int, downAt: Long): Boolean {
        c.down(lane, downAt)
        return c.tap(lane)
    }

    @Test
    fun holdingALaneArmsItAndDoesNotClear() {
        assertTrue(c.hold(lane = 2, hasAudio = true, now = 1_000))
        assertEquals(2, c.armedLane(1_000))
    }

    @Test
    fun aTapOnThatLaneWithinThreeSecondsClears() {
        c.hold(2, true, 1_000)
        assertTrue(tapAt(2, 3_500))
        assertNull(c.armedLane(3_800)) // done: a second tap does nothing
        assertFalse(tapAt(2, 3_900))
    }

    @Test
    fun theWindowIsJudgedAtTouchDownNotWhenTheTapArrives() {
        // onTap comes after the double-tap timeout: a touch-down at 2.9 s still confirms.
        c.hold(2, true, 1_000)
        assertTrue(tapAt(2, downAt = 3_900)) // the tap lands ~300 ms later, past 3 s: still confirms
    }

    @Test
    fun aTouchDownAfterTheWindowDoesNotClearAndTheArmLapses() {
        c.hold(2, true, 1_000)
        assertEquals(2, c.armedLane(4_000))
        assertNull(c.armedLane(4_001))
        assertFalse(tapAt(2, 4_001))
    }

    @Test
    fun aTapWithoutItsTouchDownDoesNothing() {
        c.hold(2, true, 1_000)
        assertFalse(c.tap(2))
    }

    @Test
    fun touchingAnotherLaneCancels() {
        c.hold(2, true, 1_000)
        c.down(0, 1_200)
        assertNull(c.armedLane(1_200))
        assertFalse(c.tap(0))
        assertFalse(tapAt(2, 1_600))
    }

    @Test
    fun aTouchOutsideTheLanesCancels() {
        c.hold(1, true, 1_000)
        c.cancel()
        assertNull(c.armedLane(1_100))
        assertFalse(tapAt(1, 1_200))
    }

    @Test
    fun aDoubleTapOnTheAskingLaneCancelsWithoutClearing() {
        c.hold(1, true, 1_000)
        c.down(1, 1_200)
        c.down(1, 1_350) // the second touch of the double-tap
        assertTrue(c.doubleTap(1)) // swallowed: no record either
        assertNull(c.armedLane(1_400))
        assertFalse(tapAt(1, 1_500))
    }

    @Test
    fun aDoubleTapWhenNothingAsksIsNotSwallowed() {
        c.down(1, 1_000)
        c.down(1, 1_150)
        assertFalse(c.doubleTap(1))
        // Nor after the ask ran out before the touch-down.
        c.hold(2, true, 2_000)
        c.down(2, 5_500)
        assertFalse(c.doubleTap(2))
    }

    @Test
    fun holdingAnEmptyLaneArmsNothingAndCancelsAnArm() {
        assertFalse(c.hold(0, hasAudio = false, now = 1_000))
        assertNull(c.armedLane(1_000))
        c.hold(1, true, 1_000)
        assertFalse(c.hold(0, false, 1_100))
        assertNull(c.armedLane(1_100))
    }

    @Test
    fun holdingAgainRestartsTheWindow() {
        c.hold(1, true, 1_000)
        c.hold(1, true, 3_000)
        assertTrue(tapAt(1, 5_500))
    }

    @Test
    fun theHoldsOwnTouchIsNotTheConfirmingTap() {
        // The long press began with a down on the lane: that down never confirms.
        c.down(2, 1_000)
        c.hold(2, true, 1_500)
        assertEquals(2, c.armedLane(1_600))
        assertFalse(c.tap(2)) // no fresh touch-down since the hold
    }
}
