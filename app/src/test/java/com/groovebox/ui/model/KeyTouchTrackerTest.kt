package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTouchTrackerTest {
    private val events = mutableListOf<String>()
    private val tracker = KeyTouchTracker(
        onNoteOn = { note, velocity, tap -> events += "on $note $velocity $tap" },
        onNoteOff = { note -> events += "off $note" },
    )

    @Test
    fun downAndUpPlayOneNote() {
        tracker.down(pointerId = 0, note = 60, velocity = 1f, tapTimeNs = 5)
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5", "off 60"), events)
    }

    @Test
    fun downOutsideAnyKeyDoesNothing() {
        tracker.down(0, null, 1f, 5)
        tracker.up(0)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun slidingToAnotherKeyMovesTheNoteWithoutMeasuring() {
        tracker.down(0, 60, 1f, 5)
        tracker.move(0, 62, 0.5f)
        tracker.move(0, 62, 0.5f)
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5", "off 60", "on 62 0.5 0", "off 62"), events)
    }

    @Test
    fun slidingOffTheKeyboardReleases() {
        tracker.down(0, 60, 1f, 5)
        tracker.move(0, null, 0.5f)
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5", "off 60"), events)
    }

    @Test
    fun twoFingersOnSameKeyReleaseOnlyWhenBothLift() {
        tracker.down(0, 60, 1f, 5)
        tracker.down(1, 60, 1f, 7)
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5"), events)
        tracker.up(1)
        assertEquals(listOf("on 60 1.0 5", "off 60"), events)
    }

    @Test
    fun cancelReleasesEverything() {
        tracker.down(0, 60, 1f, 5)
        tracker.down(1, 64, 1f, 6)
        tracker.cancel()
        assertEquals(setOf("on 60 1.0 5", "on 64 1.0 6", "off 60", "off 64"), events.toSet())
        assertEquals(4, events.size)
    }

    @Test
    fun upForUnknownPointerIsIgnored() {
        tracker.up(3)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun velocityIsPassedOnDownAndOnSlides() {
        tracker.down(0, 60, 0.6f, 9)
        tracker.move(0, 62, 0.9f)
        assertEquals(listOf("on 60 0.6 9", "off 60", "on 62 0.9 0"), events)
    }

    @Test
    fun movingWithinAKeyDoesNotRetrigger() {
        tracker.down(0, 60, 0.6f, 9)
        tracker.move(0, 60, 0.9f)
        assertEquals(listOf("on 60 0.6 9"), events)
    }

    @Test
    fun pointersThatDidNotGoDownOnAKeyAreIgnored() {
        // pointerInteropFilter passes the whole MotionEvent, so a finger dragging a strip
        // shows up in the keyboard's moves; sliding onto the keys must not play notes.
        tracker.down(0, 60, 1f, 5)
        tracker.down(1, null, 1f, 6)
        tracker.move(1, 64, 0.5f)
        tracker.move(2, 65, 0.5f)
        tracker.up(1)
        assertEquals(listOf("on 60 1.0 5"), events)
    }

    @Test
    fun aKeyFingerThatSlidesOffAndBackOnPlaysAgain() {
        tracker.down(0, 60, 1f, 5)
        tracker.move(0, null, 0.5f)
        tracker.move(0, 62, 0.7f)
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5", "off 60", "on 62 0.7 0", "off 62"), events)
    }
}
