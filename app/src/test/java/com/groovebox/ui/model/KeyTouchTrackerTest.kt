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

/** F8.2, F8.6, F8.7: the key-and-scale gate on the keyboard's touches. */
class KeyTouchTrackerGateTest {
    private val events = mutableListOf<String>()
    private var keyScale = KeyScale(9, Scale.Minor) // A minor: no sharps
    private var drum = false
    private val tracker = KeyTouchTracker(
        onNoteOn = { note, velocity, tap -> events += "on $note $velocity $tap" },
        onNoteOff = { note -> events += "off $note" },
        playable = { note -> keyScale.playable(note, drum) },
    )

    @Test
    fun aTouchDownOnADeadKeySendsNothing() {
        tracker.down(0, 61, 1f, 5) // C#4
        tracker.up(0)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun inKeyKeysBehaveExactlyAsWithOff() {
        tracker.down(0, 60, 0.7f, 5)
        tracker.up(0)
        assertEquals(listOf("on 60 0.7 5", "off 60"), events)
    }

    @Test
    fun aSlideSkipsDeadKeys() {
        tracker.down(0, 60, 1f, 5) // C
        tracker.move(0, 61, 0.9f) // C# dead: C keeps sounding
        tracker.move(0, 62, 0.8f) // D
        tracker.move(0, 63, 0.8f) // D# dead
        tracker.move(0, 62, 0.8f) // back onto D: no retrigger
        tracker.move(0, 64, 0.6f) // E
        tracker.up(0)
        assertEquals(listOf("on 60 1.0 5", "off 60", "on 62 0.8 0", "off 62", "on 64 0.6 0", "off 64"), events)
    }

    @Test
    fun aFingerThatWentDownOnADeadKeyPlaysOnceItSlidesInKey() {
        tracker.down(0, 61, 1f, 5)
        tracker.move(0, 62, 0.8f)
        tracker.up(0)
        assertEquals(listOf("on 62 0.8 0", "off 62"), events)
    }

    @Test
    fun slidingOffTheKeyboardStillReleases() {
        tracker.down(0, 60, 1f, 5)
        tracker.move(0, 61, 1f)
        tracker.move(0, null, 1f)
        assertEquals(listOf("on 60 1.0 5", "off 60"), events)
    }

    @Test
    fun aHeldNoteSurvivesAScaleChangeAndReleasesNormally() {
        keyScale = KeyScale.OFF
        tracker.down(0, 61, 1f, 5) // C# plays with the scale off
        keyScale = KeyScale(9, Scale.Minor) // now C# is out of key
        tracker.move(0, 61, 0.5f) // still on the key: nothing
        tracker.down(1, 63, 1f, 6) // a new touch on a dead key: nothing
        tracker.up(0)
        tracker.up(1)
        assertEquals(listOf("on 61 1.0 5", "off 61"), events)
    }

    @Test
    fun aScaleChangeAppliesToTheNextTouch() {
        tracker.down(0, 61, 1f, 5)
        keyScale = KeyScale(1, Scale.Major) // C# major
        tracker.down(1, 61, 1f, 6)
        tracker.up(1)
        assertEquals(listOf("on 61 1.0 6", "off 61"), events)
    }

    @Test
    fun theDrumSlotIsNeverGated() {
        drum = true
        for (n in 48..71) {
            tracker.down(0, n, 1f, n.toLong())
            tracker.up(0)
        }
        assertEquals((48..71).flatMap { listOf("on $it 1.0 $it", "off $it") }, events)
    }
}
