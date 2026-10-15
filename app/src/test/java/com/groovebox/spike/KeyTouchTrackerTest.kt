package com.groovebox.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTouchTrackerTest {
    private val events = mutableListOf<String>()
    private val tracker = KeyTouchTracker(
        onNoteOn = { note, tap -> events += "on $note $tap" },
        onNoteOff = { note -> events += "off $note" },
    )

    @Test
    fun downAndUpPlayOneNote() {
        tracker.down(pointerId = 0, note = 60, tapTimeNs = 5)
        tracker.up(0)
        assertEquals(listOf("on 60 5", "off 60"), events)
    }

    @Test
    fun downOutsideAnyKeyDoesNothing() {
        tracker.down(0, null, 5)
        tracker.up(0)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun slidingToAnotherKeyMovesTheNoteWithoutMeasuring() {
        tracker.down(0, 60, 5)
        tracker.move(0, 62)
        tracker.move(0, 62)
        tracker.up(0)
        assertEquals(listOf("on 60 5", "off 60", "on 62 0", "off 62"), events)
    }

    @Test
    fun slidingOffTheKeyboardReleases() {
        tracker.down(0, 60, 5)
        tracker.move(0, null)
        tracker.up(0)
        assertEquals(listOf("on 60 5", "off 60"), events)
    }

    @Test
    fun twoFingersOnSameKeyReleaseOnlyWhenBothLift() {
        tracker.down(0, 60, 5)
        tracker.down(1, 60, 7)
        tracker.up(0)
        assertEquals(listOf("on 60 5"), events)
        tracker.up(1)
        assertEquals(listOf("on 60 5", "off 60"), events)
    }

    @Test
    fun cancelReleasesEverything() {
        tracker.down(0, 60, 5)
        tracker.down(1, 64, 6)
        tracker.cancel()
        assertEquals(setOf("on 60 5", "on 64 6", "off 60", "off 64"), events.toSet())
        assertEquals(4, events.size)
    }

    @Test
    fun upForUnknownPointerIsIgnored() {
        tracker.up(3)
        assertEquals(emptyList<String>(), events)
    }
}
