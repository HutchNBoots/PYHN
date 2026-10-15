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
        assertEquals(1500L, g.longPressDeadline)
        assertNull(g.timeout(1499))
        assertEquals(StripAction.Lock(true), g.timeout(1500))
        assertTrue(g.locked)
        assertNull(g.longPressDeadline)
        // Fires once; lifting afterwards is not a tap.
        assertNull(g.timeout(1700))
        assertNull(g.up(0, 1800))
    }

    @Test
    fun longPressIsAlsoCaughtOnReleaseIfTheTimerWasLate() {
        g.down(0, 0, 100f, height, 0.3)
        assertEquals(StripAction.Lock(true), g.up(0, 650))
    }

    @Test
    fun movingCancelsTheLongPress() {
        startDrag(0.3)
        assertNull(g.longPressDeadline)
        assertNull(g.timeout(600))
        assertFalse(g.locked)
    }

    @Test
    fun aSecondFingerCancelsTheLongPress() {
        g.down(0, 0, 100f, height, 0.3)
        g.down(1, 100, 100f, height, 0.3)
        assertNull(g.longPressDeadline)
        assertNull(g.timeout(600))
    }

    @Test
    fun lockedStripIgnoresDragsAndDoubleTapsUntilLongPressed() {
        g.down(0, 0, 100f, height, 0.3)
        g.timeout(500)
        g.up(0, 600)
        assertTrue(g.locked)

        startDrag(0.3, t = 1000)
        assertNull(g.move(0, 1030, 0f))
        g.up(0, 1040)

        g.down(0, 1100, 100f, height, 0.3); assertNull(g.up(0, 1150))
        g.down(0, 1200, 100f, height, 0.3); assertNull(g.up(0, 1250))

        g.down(0, 2000, 100f, height, 0.3)
        assertEquals(StripAction.Lock(false), g.timeout(2500))
        g.up(0, 2600)
        assertFalse(g.locked)
        startDrag(0.3, t = 3000)
        assertEquals(0.4, value(g.move(0, 3020, 285f - 45f)), 1e-9)
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
}
