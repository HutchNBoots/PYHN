package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripGestureTest {
    private val height = 300f // full range = 1.5 * 300 = 450 px
    private val slop = 10f
    private val g = StripGesture(touchSlop = slop)

    private fun value(a: StripAction?): Double = (a as StripAction.Set).normalized

    /** Starts a single-finger drag at y=300 and moves past the slop, so later moves map 1:1. */
    private fun startDrag(start: Double, t: Long = 0L, id: Int = 0) {
        assertNull(g.down(id, t, 300f, height, start))
        assertNull(g.move(id, t + 10, 300f - slop - 5f)) // crosses the slop, no jump
    }

    @Test
    fun dragUpIncreasesAndDownDecreases() {
        startDrag(0.5)
        assertEquals(0.6, value(g.move(0, 20, 285f - 45f)), 1e-9)
        assertEquals(0.5, value(g.move(0, 30, 285f)), 1e-9)
        assertEquals(0.4, value(g.move(0, 40, 285f + 45f)), 1e-9)
    }

    @Test
    fun oneAndAHalfHeightsCoverTheFullRangeAndClamp() {
        startDrag(0.0)
        assertEquals(1.0, value(g.move(0, 20, 285f - 450f)), 1e-9)
        assertEquals(1.0, value(g.move(0, 30, 285f - 600f)), 1e-9)
        // Overshoot is not stored: coming back down moves off the end at once.
        assertEquals(0.9, value(g.move(0, 40, 285f - 600f + 45f)), 1e-9)
        assertEquals(0.0, value(g.move(0, 50, 2000f)), 1e-9)
    }

    @Test
    fun movementInsideTheSlopChangesNothing() {
        assertNull(g.down(0, 0, 300f, height, 0.5))
        assertNull(g.move(0, 10, 300f - slop))
        assertNull(g.move(0, 20, 300f + slop))
        assertNull(g.up(0, 30))
    }

    @Test
    fun secondFingerOnTheStripGivesQuarterSpeed() {
        startDrag(0.5)
        assertNull(g.down(1, 15, 100f, height, 0.5))
        assertEquals(0.525, value(g.move(0, 20, 285f - 45f)), 1e-9)
        // The fine finger's own movement is ignored.
        assertNull(g.move(1, 25, 0f))
        assertNull(g.up(1, 30))
        assertEquals(0.625, value(g.move(0, 40, 285f - 90f)), 1e-9)
    }

    @Test
    fun whenTheDrivingFingerLiftsTheOtherOneTakesOver() {
        startDrag(0.5)
        assertNull(g.down(1, 15, 200f, height, 0.5))
        assertNull(g.up(0, 20))
        assertEquals(0.6, value(g.move(1, 30, 200f - 45f)), 1e-9)
        assertNull(g.up(1, 40))
    }

    @Test
    fun doubleTapResets() {
        assertNull(g.down(0, 0, 100f, height, 0.3))
        assertNull(g.up(0, 80))
        assertNull(g.down(0, 300, 100f, height, 0.3))
        assertEquals(StripAction.Reset, g.up(0, 360))
    }

    @Test
    fun slowTapsDoNotReset() {
        assertNull(g.down(0, 0, 100f, height, 0.3))
        assertNull(g.up(0, 80))
        assertNull(g.down(0, 400, 100f, height, 0.3))
        assertNull(g.up(0, 450))
    }

    @Test
    fun aTripleTapResetsOnceThenStartsOver() {
        g.down(0, 0, 100f, height, 0.3); g.up(0, 50)
        g.down(0, 100, 100f, height, 0.3)
        assertEquals(StripAction.Reset, g.up(0, 150))
        g.down(0, 200, 100f, height, 0.3)
        assertNull(g.up(0, 250))
    }

    @Test
    fun aDragIsNotATap() {
        assertNull(g.down(0, 0, 100f, height, 0.3))
        assertNull(g.up(0, 80))
        startDrag(0.3, t = 150)
        g.up(0, 200)
        assertNull(g.down(0, 250, 100f, height, 0.3))
        assertNull(g.up(0, 300))
    }

    @Test
    fun liftingASecondFingerIsNotATap() {
        assertNull(g.down(0, 0, 100f, height, 0.3))
        assertNull(g.up(0, 50))
        assertNull(g.down(0, 100, 100f, height, 0.3))
        assertNull(g.down(1, 120, 50f, height, 0.3))
        assertNull(g.up(1, 140))
        assertNull(g.up(0, 160))
    }

    @Test
    fun longPressTogglesLockAtTheDeadline() {
        assertNull(g.down(0, 1000, 100f, height, 0.3))
        assertEquals(2000L, g.longPressDeadline)
        assertNull(g.timeout(1999))
        assertEquals(StripAction.Lock(true), g.timeout(2000))
        assertTrue(g.locked)
        assertNull(g.longPressDeadline)
        // Fires once; lifting afterwards is not a tap.
        assertNull(g.timeout(2200))
        assertNull(g.up(0, 2300))
    }

    @Test
    fun longPressIsAlsoCaughtOnReleaseIfTheTimerWasLate() {
        g.down(0, 0, 100f, height, 0.3)
        assertNull(g.up(0, 650).also { g.cancel() }) // half a second is no longer a lock (build-22)
        g.down(0, 1000, 100f, height, 0.3)
        assertEquals(StripAction.Lock(true), g.up(0, 2150))
    }

    @Test
    fun movingCancelsTheLongPress() {
        startDrag(0.3)
        assertNull(g.longPressDeadline)
        assertNull(g.timeout(1100))
        assertFalse(g.locked)
    }

    @Test
    fun aSecondFingerCancelsTheLongPress() {
        g.down(0, 0, 100f, height, 0.3)
        g.down(1, 100, 100f, height, 0.3)
        assertNull(g.longPressDeadline)
        assertNull(g.timeout(1100))
    }

    @Test
    fun lockedStripIgnoresDragsAndDoubleTapsUntilLongPressed() {
        g.down(0, 0, 100f, height, 0.3)
        g.timeout(1000)
        g.up(0, 1100)
        assertTrue(g.locked)

        startDrag(0.3, t = 1500)
        assertNull(g.move(0, 1530, 0f))
        g.up(0, 1540)

        g.down(0, 1600, 100f, height, 0.3); assertNull(g.up(0, 1650))
        g.down(0, 1700, 100f, height, 0.3); assertNull(g.up(0, 1750))

        g.down(0, 2000, 100f, height, 0.3)
        assertEquals(StripAction.Lock(false), g.timeout(3000))
        g.up(0, 3100)
        assertFalse(g.locked)
        startDrag(0.3, t = 4000)
        assertEquals(0.4, value(g.move(0, 4020, 285f - 45f)), 1e-9)
    }

    @Test
    fun lockCanBeSetFromOutside() {
        g.locked = true
        startDrag(0.3)
        assertNull(g.move(0, 20, 0f))
    }

    @Test
    fun unknownPointersAreIgnored() {
        assertNull(g.move(7, 0, 10f))
        assertNull(g.up(7, 0))
        assertNull(g.timeout(10_000))
    }

    @Test
    fun cancelForgetsTheGestureAndTapHistory() {
        g.down(0, 0, 100f, height, 0.3); g.up(0, 50)
        g.down(0, 100, 100f, height, 0.3)
        g.cancel()
        assertNull(g.longPressDeadline)
        assertNull(g.up(0, 150))
        g.down(0, 200, 100f, height, 0.3)
        assertNull(g.up(0, 250))
    }

    // --- build-22: the 1 s still hold, its ring and its cancel (owner decision 1-2c) ---

    @Test
    fun aLockNeedsAFullSecondStill() {
        g.down(0, 0, 100f, height, 0.3)
        assertEquals(1000L, g.longPressDeadline)
        assertNull(g.timeout(999))
        assertFalse(g.locked)
        assertEquals(StripAction.Lock(true), g.timeout(1000))
    }

    @Test
    fun aRestingThumbThatThenDragsNeverLocks() {
        // Half a second at rest (the build-21 lock time), then a drag: the drag works.
        g.down(0, 0, 300f, height, 0.5)
        assertNull(g.timeout(600))
        assertNull(g.move(0, 700, 300f - slop - 5f))
        assertEquals(0.6, value(g.move(0, 720, 285f - 45f)), 1e-9)
        assertNull(g.timeout(1500))
        assertFalse(g.locked)
    }

    @Test
    fun movementWithinTheSlopKeepsTheHold() {
        g.down(0, 0, 300f, height, 0.5)
        assertNull(g.move(0, 400, 300f + slop))
        assertEquals(StripAction.Lock(true), g.timeout(1000))
    }

    @Test
    fun theRingWaitsThenFillsToTheLock() {
        g.down(0, 0, 100f, height, 0.3)
        assertNull("no ring for a tap or a drag's first moments", g.lockProgress(0))
        assertNull(g.lockProgress(StripGesture.RING_DELAY_MS - 1))
        assertEquals(0f, g.lockProgress(StripGesture.RING_DELAY_MS)!!, 1e-6f)
        val mid = (StripGesture.RING_DELAY_MS + StripGesture.LOCK_HOLD_MS) / 2
        assertEquals(0.5f, g.lockProgress(mid)!!, 0.01f)
        assertEquals(1f, g.lockProgress(999)!!, 0.01f)
        g.timeout(1000)
        assertNull("the ring goes once locked", g.lockProgress(1001))
    }

    @Test
    fun theRingStopsWhenTheFingerMovesLiftsOrASecondFingerLands() {
        g.down(0, 0, 300f, height, 0.3)
        assertTrue(g.lockProgress(600)!! > 0f)
        g.move(0, 610, 300f - slop - 1f)
        assertNull(g.lockProgress(620))

        g.cancel()
        g.down(0, 2000, 300f, height, 0.3)
        g.down(1, 2600, 200f, height, 0.3)
        assertNull(g.lockProgress(2700))

        g.cancel()
        g.down(0, 4000, 300f, height, 0.3)
        g.up(0, 4600)
        assertNull(g.lockProgress(4700))
    }

    @Test
    fun theUiWakesForTheRingFramesAndTheDeadline() {
        assertNull(g.nextWake(0))
        g.down(0, 0, 100f, height, 0.3)
        // Before the ring shows, sleep until it does; then a frame at a time, never past the deadline.
        assertEquals(StripGesture.RING_DELAY_MS, g.nextWake(10))
        assertEquals(400L + StripGesture.FRAME_MS, g.nextWake(400))
        assertEquals(1000L, g.nextWake(995))
        g.timeout(1000)
        assertNull(g.nextWake(1000))
    }

    @Test
    fun aDoubleTapStillResets() {
        g.down(0, 0, 100f, height, 0.3); assertNull(g.up(0, 80))
        g.down(0, 200, 100f, height, 0.3)
        assertEquals(StripAction.Reset, g.up(0, 260))
    }
}
