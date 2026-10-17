package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-21 item 2: the Seq display's pitch roll of the current page (synth slots). */
class PitchRollTest {
    private val gate = 0.5f

    private fun pattern(vararg steps: Pair<Int, Step>): (Int) -> Step {
        val map = steps.toMap()
        return { map[it] ?: Step() }
    }

    private fun hit(note: Int, length: Float = SeqLimits.PATTERN_GATE, velocity: Float = 0.8f, nudge: Float = 0f) =
        Hit(note, velocity, length, nudge = nudge)

    private fun settings(length: Int = 16) = PatternSettings(length = length, gate = gate)

    // --- rows ---

    @Test
    fun rowsFitTheNotesPaddedByTwoAndAtLeastAnOctave() {
        // A2..E3 padded: G2..F#3 is 11 semitones, widened to an octave.
        val r = PitchRoll.rows(listOf(45, 52))
        assertEquals(12, r.last - r.first)
        assertTrue(43 in r && 54 in r)
        assertEquals(43..55, r)
        // Wide patterns keep their padding and no more.
        assertEquals(34..74, PitchRoll.rows(listOf(36, 72, 50)))
        // One note: centred in an octave.
        assertEquals(54..66, PitchRoll.rows(listOf(60)))
    }

    @Test
    fun rowsOfAnEmptyPatternAndAtTheEdges() {
        assertEquals(48..60, PitchRoll.rows(emptyList()))
        assertEquals(0..12, PitchRoll.rows(listOf(1)))
        assertEquals(115..127, PitchRoll.rows(listOf(126)))
    }

    // --- bars ---

    @Test
    fun aBarIsAsLongAsItsNote() {
        val p = pattern(
            2 to Step(listOf(hit(45))), // the pattern's gate: half a step
            6 to Step(listOf(hit(52, length = 2f))),
            9 to Step(listOf(hit(48, length = 0.25f, nudge = 0.25f))),
        )
        val bars = PitchRoll.bars(p, page = 0, settings = settings())
        assertEquals(3, bars.size)
        val a = bars.single { it.step == 2 }
        assertEquals(45, a.note); assertEquals(2f, a.start, 1e-6f); assertEquals(0.5f, a.length, 1e-6f)
        assertTrue(a.head); assertFalse(a.runsOff); assertFalse(a.outside)
        val e = bars.single { it.step == 6 }
        assertEquals(6f, e.start, 1e-6f); assertEquals(2f, e.length, 1e-6f)
        val nudged = bars.single { it.step == 9 }
        assertEquals(9.25f, nudged.start, 1e-6f); assertEquals(0.25f, nudged.length, 1e-6f)
    }

    @Test
    fun velocityIsCarried() {
        val bars = PitchRoll.bars(pattern(0 to Step(listOf(hit(50, velocity = 0.3f)))), 0, settings())
        assertEquals(0.3f, bars.single().velocity, 1e-6f)
    }

    @Test
    fun chordsStack() {
        val bars = PitchRoll.bars(pattern(12 to Step.of(listOf(48, 52, 57))), 0, settings())
        assertEquals(listOf(48, 52, 57), bars.map { it.note }.sorted())
        assertTrue(bars.all { it.step == 12 && it.start == 12f && it.length == gate })
    }

    @Test
    fun aTailRunsOffThePageAndContinuesOnTheNext() {
        val p = pattern(15 to Step(listOf(hit(50, length = 3f))))
        val page0 = PitchRoll.bars(p, 0, settings(length = 32)).single()
        assertEquals(15f, page0.start, 1e-6f); assertEquals(1f, page0.length, 1e-6f)
        assertTrue(page0.head); assertTrue(page0.runsOff)
        val page1 = PitchRoll.bars(p, 1, settings(length = 32)).single()
        assertEquals(15, page1.step)
        assertEquals(0f, page1.start, 1e-6f); assertEquals(2f, page1.length, 1e-6f)
        assertFalse(page1.head); assertFalse(page1.runsOff)
    }

    @Test
    fun aTailPastThePatternEndWrapsToItsStart() {
        // 16-step pattern: a 3-step note on step 15 sounds on into steps 1 and 2 of the next loop.
        val bars = PitchRoll.bars(pattern(15 to Step(listOf(hit(50, length = 3f)))), 0, settings(length = 16))
        assertEquals(2, bars.size)
        val head = bars.single { it.head }
        assertEquals(15f, head.start, 1e-6f); assertEquals(1f, head.length, 1e-6f); assertTrue(head.runsOff)
        val tail = bars.single { !it.head }
        assertEquals(15, tail.step); assertEquals(0f, tail.start, 1e-6f); assertEquals(2f, tail.length, 1e-6f)
        // A 12-step pattern: the tail past step 12 wraps too, and nothing is drawn past the end.
        val short = PitchRoll.bars(pattern(10 to Step(listOf(hit(50, length = 4f)))), 0, settings(length = 12))
        assertEquals(listOf(0f to 2f, 10f to 2f), short.map { it.start to it.length }.sortedBy { it.first })
    }

    @Test
    fun aHitPastThePatternEndIsShownButMarkedOutside() {
        val bars = PitchRoll.bars(pattern(10 to Step(listOf(hit(50, length = 8f)))), 0, settings(length = 8))
        val b = bars.single()
        assertTrue(b.outside)
        assertEquals(10f, b.start, 1e-6f); assertEquals(6f, b.length, 1e-6f); assertTrue(b.runsOff)
    }

    @Test
    fun otherPagesAreLeftOut() {
        val p = pattern(3 to Step.of(listOf(50)), 20 to Step.of(listOf(52)))
        assertEquals(listOf(20), PitchRoll.bars(p, 1, settings(length = 32)).map { it.step })
        assertEquals(4f, PitchRoll.bars(p, 1, settings(length = 32)).single().start, 1e-6f)
    }

    @Test
    fun pagesWithNotes() {
        val p = pattern(3 to Step.of(listOf(50)), 40 to Step.of(listOf(52)))
        assertEquals(listOf(true, false, true, false), PitchRoll.pagesWithNotes(p))
    }

    // --- taps ---

    @Test
    fun aTapOnABarFindsItsStep() {
        val p = pattern(
            2 to Step(listOf(hit(45))),
            6 to Step(listOf(hit(52, length = 2f))),
            7 to Step(listOf(hit(57))),
            12 to Step.of(listOf(48, 52, 57)),
        )
        val bars = PitchRoll.bars(p, 0, settings())
        assertEquals(2, PitchRoll.stepAt(bars, column = 2.2f, pitch = 45f, maxRows = 1f))
        // A short bar can be hit anywhere in its step's column.
        assertEquals(2, PitchRoll.stepAt(bars, column = 2.9f, pitch = 45.4f, maxRows = 1f))
        // Too far from the row: nothing.
        assertNull(PitchRoll.stepAt(bars, column = 2.2f, pitch = 48f, maxRows = 1f))
        // On a tail: the step it belongs to; with another bar in the column, the nearer row wins.
        assertEquals(6, PitchRoll.stepAt(bars, column = 7.5f, pitch = 52f, maxRows = 2f))
        assertEquals(7, PitchRoll.stepAt(bars, column = 7.5f, pitch = 56f, maxRows = 2f))
        // Any note of a chord picks the chord's step.
        assertEquals(12, PitchRoll.stepAt(bars, column = 12.3f, pitch = 52.2f, maxRows = 1f))
        // Empty roll.
        assertNull(PitchRoll.stepAt(bars, column = 10.5f, pitch = 50f, maxRows = 1f))
        assertNull(PitchRoll.stepAt(emptyList(), column = 2f, pitch = 45f, maxRows = 1f))
    }

    // --- pad labels ---

    @Test
    fun padLabels() {
        assertEquals("", NoteNames.padLabel(emptyList()))
        assertEquals("A2", NoteNames.padLabel(listOf(45)))
        assertEquals("C3·3", NoteNames.padLabel(listOf(55, 48, 50)))
        assertEquals("C3·2", NoteNames.padLabel(listOf(48, 60)))
        // Simple major and minor triads are named, in any voicing.
        assertEquals("Am", NoteNames.padLabel(listOf(57, 60, 64)))
        assertEquals("C", NoteNames.padLabel(listOf(52, 48, 55)))
        assertEquals("G", NoteNames.padLabel(listOf(50, 55, 59)))
        assertEquals("C", NoteNames.padLabel(listOf(48, 52, 55, 60)))
        assertEquals("C#m", NoteNames.padLabel(listOf(61, 64, 68)))
        // A name that does not fit falls back to lowest note and count.
        assertEquals("C#4·3", NoteNames.padLabel(listOf(61, 64, 68), maxChars = 2))
        // Not a simple triad.
        assertEquals("C3·3", NoteNames.padLabel(listOf(48, 51, 54)))
    }
}
