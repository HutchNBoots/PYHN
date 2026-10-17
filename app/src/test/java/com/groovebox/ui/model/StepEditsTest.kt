package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-22: chord toggles from the pen keys, accent, relative multi-step drags, spread, chord names. */
class StepEditsTest {
    // --- chord toggle ---

    @Test
    fun theFirstKeyReplacesAndLaterKeysToggle() {
        var notes = listOf(57, 60, 64)
        notes = ChordEdit.press(notes, 55, first = true)
        assertEquals(listOf(55), notes)
        notes = ChordEdit.press(notes, 59, first = false)
        notes = ChordEdit.press(notes, 62, first = false)
        notes = ChordEdit.press(notes, 65, first = false)
        assertEquals("a 4-note chord with two thumbs", listOf(55, 59, 62, 65), notes)
        assertEquals("full: a fifth note is ignored", notes, ChordEdit.press(notes, 67, first = false))
        assertEquals("a held note toggles off", listOf(55, 62, 65), ChordEdit.press(notes, 59, first = false))
        assertEquals(emptyList<Int>(), ChordEdit.press(listOf(60), 60, first = false))
    }

    @Test
    fun withNotesKeepsEachKeptNotesHitAndGivesNewOnesTheSharedValues() {
        val step = Step(listOf(Hit(57, 0.4f, 2f, 0.5f, 0.1f), Hit(60, 0.9f)))
        val out = ChordEdit.withNotes(step, listOf(60, 64))
        assertEquals(Hit(60, 0.9f), out.hit(60))
        assertEquals(Hit(64, 0.4f, 2f, 0.5f, 0.1f), out.hit(64))
        assertEquals(Hit(62), ChordEdit.withNotes(Step(), listOf(62)).hits.single())
        assertFalse(ChordEdit.withNotes(step, emptyList()).on)
    }

    // --- accent ---

    @Test
    fun accentTogglesEveryHitBetweenFullAndTheDefault() {
        val s = Step(listOf(Hit(60, 0.5f), Hit(64, 0.9f)))
        val on = Accent.toggled(s)
        assertTrue(Accent.isOn(on))
        assertEquals(listOf(1f, 1f), on.hits.map { it.velocity })
        val off = Accent.toggled(on)
        assertFalse(Accent.isOn(off))
        assertEquals(listOf(SeqLimits.DEFAULT_VELOCITY, SeqLimits.DEFAULT_VELOCITY), off.hits.map { it.velocity })
        assertEquals(Step(), Accent.toggled(Step()))
    }

    // --- relative drags ---

    private val steps = mapOf(
        0 to Step(listOf(Hit(45, 1.0f, nudge = 0.1f))),
        4 to Step(listOf(Hit(48, 0.6f, length = 1f, nudge = -0.45f))),
        8 to Step(),
    )

    @Test
    fun velocityMovesEveryStepByTheDragAndKeepsTheAccents() {
        val d = RelativeDrag()
        d.start(1, 0, steps, lane = null, anchor = 0.6)
        val out = d.moveTo(0.4, HitField.Velocity, 1, 0)
        assertEquals(0.8f, out.getValue(0).hits[0].velocity, 1e-5f)
        assertEquals(0.4f, out.getValue(4).hits[0].velocity, 1e-5f)
        assertFalse("an empty step takes no part (no hit is created)", out.containsKey(8))
        val up = d.moveTo(1.0, HitField.Velocity, 1, 0)
        assertEquals("each clamps on its own", 1f, up.getValue(0).hits[0].velocity, 0f)
        assertEquals(1f, up.getValue(4).hits[0].velocity, 1e-5f)
        assertEquals("back to the start: the steps as they were", steps.filterKeys { it != 8 }, d.moveTo(0.6, HitField.Velocity, 1, 0))
    }

    @Test
    fun nudgeChanceAndHoldMoveRelatively() {
        val d = RelativeDrag()
        d.start(1, 0, steps, null, anchor = 0.0)
        val n = d.moveTo(0.1, HitField.Nudge, 1, 0)
        assertEquals(0.2f, n.getValue(0).hits[0].nudge, 1e-5f)
        assertEquals(-0.35f, n.getValue(4).hits[0].nudge, 1e-5f)
        d.start(1, 0, steps, null, anchor = 1.0)
        val c = d.moveTo(0.5, HitField.Chance, 1, 0)
        assertEquals(listOf(0.5f, 0.5f), c.values.map { it.hits[0].probability })
        d.start(1, 0, steps, null, anchor = 3.0)
        val h = d.moveTo(5.0, HitField.Hold, 1, 0)
        // Step 0 is on the pattern's gate (position 0) → position 2 (1/8); step 4 is 1 step (position 8) → 10 (2 steps).
        assertEquals(1f / 8, h.getValue(0).hits[0].length)
        assertEquals(2f, h.getValue(4).hits[0].length)
    }

    @Test
    fun aDrumDragMovesOnlyTheLanesHitAndSkipsStepsWithoutIt() {
        val drum = mapOf(0 to Step(listOf(Hit(48, 0.8f), Hit(50, 0.5f))), 2 to Step(listOf(Hit(48, 0.8f))))
        val d = RelativeDrag()
        d.start(0, 0, drum, lane = 50, anchor = 0.5)
        val out = d.moveTo(0.7, HitField.Velocity, 0, 0)
        assertEquals(setOf(0), out.keys)
        assertEquals(0.8f, out.getValue(0).hit(48)!!.velocity)
        assertEquals(0.7f, out.getValue(0).hit(50)!!.velocity, 1e-5f)
    }

    @Test
    fun anotherPatternEndsTheDrag() {
        val d = RelativeDrag()
        d.start(1, 0, steps, null, 0.5)
        assertTrue(d.holds(1, 0, 4))
        assertTrue(d.moveTo(0.6, HitField.Velocity, 2, 0).isEmpty())
        assertFalse(d.active)
    }

    // --- spread ---

    @Test
    fun spreadIsDeterministicPerSeedAndZeroGivesTheStepsBack() {
        val s = (0 until 8).associateWith { Step(listOf(Hit(60, 0.7f))) }
        val a = Spread.apply(s, null, 0.5f, seed = 42)
        assertEquals(a, Spread.apply(s, null, 0.5f, seed = 42))
        assertNotEquals(a, Spread.apply(s, null, 0.5f, seed = 43))
        assertEquals(s, Spread.apply(s, null, 0f, seed = 42))
        // Different steps get different offsets, inside the ranges.
        assertTrue(a.values.map { it.hits[0].velocity }.toSet().size > 4)
        for (st in a.values) {
            assertTrue(kotlin.math.abs(st.hits[0].velocity - 0.7f) <= 0.5f * Spread.VELOCITY_RANGE + 1e-5f)
            assertTrue(kotlin.math.abs(st.hits[0].nudge) <= 0.5f * Spread.NUDGE_RANGE + 1e-5f)
        }
    }

    @Test
    fun spreadOnADrumLaneLeavesTheOtherSounds() {
        val s = mapOf(3 to Step(listOf(Hit(48, 0.8f), Hit(50, 0.8f))))
        val out = Spread.apply(s, 50, 1f, 7).getValue(3)
        assertEquals(Hit(48, 0.8f), out.hit(48))
        assertNotEquals(Hit(50, 0.8f), out.hit(50))
    }

    @Test
    fun spreadUnitIsInRangeAndFixed() {
        for (i in 0 until 100) {
            val u = Spread.unit(9, i, 60, 0)
            assertTrue(u in -1f..1f)
            assertEquals(u, Spread.unit(9, i, 60, 0))
        }
    }

    // --- chord names ---

    @Test
    fun namesSortLowToHighAndNameSimpleChords() {
        assertEquals("C maj · C4 E4 G4", ChordName.describe(listOf(67, 60, 64)))
        assertEquals("A min · A3 C4 E4", ChordName.describe(listOf(60, 57, 64)))
        assertEquals("A m7 · A3 C4 E4 G4", ChordName.describe(listOf(57, 60, 64, 67)))
        assertEquals("F maj/A · A3 C4 F4", ChordName.describe(listOf(57, 60, 65)))
        assertEquals("G 7 · G3 B3 D4 F4", ChordName.describe(listOf(55, 59, 62, 65)))
        assertEquals("E 5 · E2 B2", ChordName.describe(listOf(40, 47)))
        assertEquals("A2", ChordName.describe(listOf(45)))
        assertEquals("C4 C#4 D4", ChordName.describe(listOf(62, 61, 60)))
        assertNull(ChordName.name(listOf(60)))
    }
}
