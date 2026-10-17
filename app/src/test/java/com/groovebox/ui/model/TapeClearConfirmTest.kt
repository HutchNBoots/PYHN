package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeClearConfirmTest {
    private val c = TapeClearConfirm(windowMs = 3_000)

    @Test
    fun holdingALaneArmsItAndDoesNotClear() {
        assertTrue(c.hold(lane = 2, hasAudio = true, now = 1_000))
        assertEquals(2, c.armedLane(1_000))
    }

    @Test
    fun aTapOnThatLaneWithinThreeSecondsClears() {
        c.hold(2, true, 1_000)
        c.down(2, 3_500)
        assertTrue(c.tap(2, 3_600))
        assertNull(c.armedLane(3_600)) // done: a second tap does nothing
        assertFalse(c.tap(2, 3_700))
    }

    @Test
    fun aTapAfterTheWindowDoesNotClearAndTheArmLapses() {
        c.hold(2, true, 1_000)
        assertEquals(2, c.armedLane(4_000))
        assertNull(c.armedLane(4_001))
        assertFalse(c.tap(2, 4_001))
    }

    @Test
    fun touchingAnotherLaneCancels() {
        c.hold(2, true, 1_000)
        c.down(0, 1_200)
        assertNull(c.armedLane(1_200))
        assertFalse(c.tap(0, 1_300))
        assertFalse(c.tap(2, 1_400))
    }

    @Test
    fun aTouchOutsideTheLanesCancels() {
        c.hold(1, true, 1_000)
        c.cancel()
        assertNull(c.armedLane(1_100))
        assertFalse(c.tap(1, 1_200))
    }

    @Test
    fun aTapOnAnotherLaneDoesNotClearIt() {
        c.hold(1, true, 1_000)
        assertFalse(c.tap(3, 1_100))
        assertNull(c.armedLane(1_100))
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
        assertTrue(c.tap(1, 5_500))
    }

    @Test
    fun theHoldsOwnTouchIsNotTheConfirmingTap() {
        // The long press began with a down on the lane: that down never confirms.
        c.down(2, 1_000)
        c.hold(2, true, 1_500)
        assertEquals(2, c.armedLane(1_600))
    }
}
