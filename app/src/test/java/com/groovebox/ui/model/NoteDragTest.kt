package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-21 item 1: the Shift-layer Note strip moves the selected steps by an interval. */
class NoteDragTest {
    private val cMajor = KeyScale(0, Scale.Major)
    private val aMinor = KeyScale(9, Scale.Minor)

    private fun steps(vararg chords: List<Int>): Map<Int, Step> =
        chords.withIndex().associate { (i, c) -> i to Step(c.map { Hit(it, velocity = 0.5f + i * 0.1f, length = i.toFloat()) }) }

    private fun notes(moved: Map<Int, Step>): List<List<Int>> = moved.toSortedMap().values.map { it.notes }

    @Test
    fun aMultiStepMoveKeepsTheLine() {
        // A bassline A2 C3 E3 A2: +2 moves every step by 2 (the old strip flattened them all to one pitch).
        val drag = NoteDrag()
        drag.start(steps(listOf(45), listOf(48), listOf(52), listOf(45)))
        assertEquals(listOf(listOf(47), listOf(50), listOf(54), listOf(47)), notes(drag.moveTo(2, KeyScale.OFF)))
        assertEquals(2, drag.offset)
        assertEquals(listOf(listOf(40), listOf(43), listOf(47), listOf(40)), notes(drag.moveTo(-5, KeyScale.OFF)))
        assertEquals(-5, drag.offset)
    }

    @Test
    fun theMoveIsRelativeToWhereTheDragStartedNotCumulative() {
        val drag = NoteDrag()
        drag.start(steps(listOf(60)))
        drag.moveTo(3, KeyScale.OFF)
        assertEquals(listOf(listOf(62)), notes(drag.moveTo(2, KeyScale.OFF)))
    }

    @Test
    fun chordsKeepTheirShapeAndHitsKeepTheirValues() {
        val drag = NoteDrag()
        val before = steps(listOf(60, 64, 67), listOf(57, 60, 64))
        drag.start(before)
        val moved = drag.moveTo(-5, KeyScale.OFF)
        assertEquals(listOf(listOf(55, 59, 62), listOf(52, 55, 59)), notes(moved))
        for ((i, s) in moved) {
            assertEquals(before.getValue(i).hits.map { it.copy(note = 0) }, s.hits.map { it.copy(note = 0) })
        }
    }

    @Test
    fun withAScaleOnTheMoveIsInScaleDegrees() {
        val drag = NoteDrag()
        drag.start(steps(listOf(57), listOf(60), listOf(64), listOf(57, 60, 64)))
        // A minor, +2 degrees: A→C, C→E, E→G; the chord A C E → C E G.
        assertEquals(
            listOf(listOf(60), listOf(64), listOf(67), listOf(60, 64, 67)),
            notes(drag.moveTo(2, aMinor)),
        )
        // −1 degree: A C E → G B D.
        assertEquals(listOf(55, 59, 62), notes(drag.moveTo(-1, aMinor))[3])
    }

    @Test
    fun aScaleMoveNeverDropsANote() {
        // D D♯ E in C major (D♯ entered before the scale was on), up a degree: D→E and E→F; D♯ would land
        // on F too, so it keeps a pitch of its own. Three notes in, three distinct notes out.
        val drag = NoteDrag()
        drag.start(steps(listOf(62, 63, 64)))
        for (offset in -8..8) {
            val out = notes(drag.moveTo(offset, cMajor))[0]
            assertEquals("offset $offset: $out", 3, out.size)
            assertEquals("offset $offset: $out", 3, out.toSet().size)
            assertEquals(cMajor.moveByDegrees(62, offset), out[0])
            assertEquals(cMajor.moveByDegrees(64, offset), out[2])
        }
        // Out-of-key notes keep their distance above the in-key note below them.
        assertEquals(listOf(64, 66, 65), notes(drag.moveTo(1, cMajor))[0])
    }

    @Test
    fun movingByDegrees() {
        assertEquals(62, cMajor.moveByDegrees(60, 1))
        assertEquals(59, cMajor.moveByDegrees(60, -1))
        assertEquals(72, cMajor.moveByDegrees(60, 7))
        // An out-of-key note moves with the in-key note below it, one semitone above it as before: C♯ → D♯.
        assertEquals(63, cMajor.moveByDegrees(61, 1))
        assertEquals(61, cMajor.moveByDegrees(61, 0))
        assertEquals(57, aMinor.moveByDegrees(57, 0))
        assertEquals(69, aMinor.moveByDegrees(57, 7))
    }

    @Test
    fun theRangeClampStopsEveryNote() {
        val drag = NoteDrag()
        drag.start(steps(listOf(90), listOf(60, 64)))
        // +10 would take 90 past C7 (96): the whole move stops at +6.
        assertEquals(listOf(listOf(96), listOf(66, 70)), notes(drag.moveTo(10, KeyScale.OFF)))
        assertEquals(6, drag.offset)
        drag.start(steps(listOf(30, 34), listOf(60)))
        assertEquals(listOf(listOf(24, 28), listOf(54)), notes(drag.moveTo(-10, KeyScale.OFF)))
        assertEquals(-6, drag.offset)
        // In degrees too: B6 (95) can go up one degree (C7), not two.
        drag.start(steps(listOf(95), listOf(60)))
        assertEquals(listOf(listOf(96), listOf(62)), notes(drag.moveTo(2, cMajor)))
        assertEquals(1, drag.offset)
    }

    @Test
    fun aDoubleTapIsNoChange() {
        val drag = NoteDrag()
        val before = steps(listOf(45), listOf(52, 55))
        drag.start(before)
        assertEquals(before, drag.reset(KeyScale.OFF))
        assertEquals(0, drag.offset)
        // Also after a move in the same gesture: back to where it started.
        drag.moveTo(4, KeyScale.OFF)
        assertEquals(before, drag.reset(KeyScale.OFF))
        // Offset 0 is the identity, out-of-key notes included.
        drag.start(steps(listOf(61, 63, 66)))
        assertEquals(listOf(listOf(61, 63, 66)), notes(drag.moveTo(0, cMajor)))
    }

    @Test
    fun emptyStepsAreLeftOutAndAnEmptySelectionDoesNothing() {
        val drag = NoteDrag()
        drag.start(mapOf(0 to Step(), 1 to Step.of(listOf(48))))
        assertTrue(drag.canMove)
        assertEquals(setOf(1), drag.moveTo(3, KeyScale.OFF).keys)
        drag.start(mapOf(0 to Step(), 5 to Step()))
        assertFalse(drag.canMove)
        assertTrue(drag.moveTo(3, KeyScale.OFF).isEmpty())
        assertEquals(0, drag.offset)
    }

    @Test
    fun endForgetsTheGesture() {
        val drag = NoteDrag()
        drag.start(steps(listOf(48)))
        drag.moveTo(2, KeyScale.OFF)
        assertTrue(drag.active)
        drag.end()
        assertFalse(drag.active)
        assertEquals(0, drag.offset)
        assertTrue(drag.moveTo(2, KeyScale.OFF).isEmpty())
    }
}
