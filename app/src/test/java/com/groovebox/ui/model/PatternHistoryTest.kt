package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Seq screen's Undo / Redo (build-20): 32 steps, drags and record takes coalesced. */
class PatternHistoryTest {
    private val sink = RecordingSink()
    private val store = PatternStore(sink)
    private val history = PatternHistory(store)

    private fun notes(slot: Int, pattern: Int, step: Int) = store.step(slot, pattern, step).notes

    @Test
    fun startsEmpty() {
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.undo())
        assertFalse(history.redo())
    }

    @Test
    fun undoBringsBackARemovedStepAndResendsThePattern() {
        // Owner's acceptance 3.
        history.edit(0, 0) { store.toggleHit(0, 0, 4, 48, 1f) }
        history.edit(0, 0) { store.toggleHit(0, 0, 4, 48, 1f) }
        assertFalse(store.step(0, 0, 4).on)
        sink.calls.clear()
        assertTrue(history.undo())
        assertEquals(listOf(48), notes(0, 0, 4))
        assertEquals("only the step that differs is sent", listOf("step 0 0 4 [48 1.0 -1.0 1.0 0.0]"), sink.calls)
        assertTrue(history.undo())
        assertFalse(store.step(0, 0, 4).on)
        assertFalse(history.canUndo)
    }

    @Test
    fun redoReappliesAndANewEditDropsTheRedo() {
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.undo()
        assertTrue(history.canRedo)
        assertTrue(history.redo())
        assertEquals(listOf(60), notes(1, 0, 0))
        history.undo()
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(62), 1f) }
        assertFalse(history.canRedo)
        assertFalse(history.redo())
    }

    @Test
    fun editsThatChangeNothingAreNotRecorded() {
        history.edit(1, 0) { store.updateSettings(1, 0) { it } }
        history.edit(0, 0) { store.clearLane(0, 0, 48) }
        assertFalse(history.canUndo)
    }

    @Test
    fun aGroupIsOneUndoStep() {
        // One drag: many strip values, one step.
        history.edit(1, 0) { store.toggleStep(1, 0, 3, listOf(60), 1f) }
        val g = history.begin()
        for (v in 1..20) history.edit(1, 0) { store.editHits(1, 0, 3, null) { it.copy(velocity = v / 40f) } }
        history.end(g)
        assertEquals(0.5f, store.step(1, 0, 3).hits[0].velocity, 0f)
        history.undo()
        assertEquals(Step(listOf(Hit(60, 1f))), store.step(1, 0, 3))
        history.undo()
        assertFalse(store.step(1, 0, 3).on)
    }

    @Test
    fun aGroupCanTouchSeveralPatterns() {
        // A record take across a pattern switch, or two slots.
        val g = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.edit(1, 1) { store.toggleStep(1, 1, 0, listOf(62), 1f) }
        history.edit(1, 0) { store.toggleStep(1, 0, 2, listOf(64), 1f) }
        history.end(g)
        history.undo()
        assertFalse(store.step(1, 0, 0).on)
        assertFalse(store.step(1, 0, 2).on)
        assertFalse(store.step(1, 1, 0).on)
        history.redo()
        assertEquals(listOf(60), notes(1, 0, 0))
        assertEquals(listOf(62), notes(1, 1, 0))
    }

    @Test
    fun anEmptyGroupLeavesNoStep() {
        val g = history.begin()
        history.edit(1, 0) { store.updateSettings(1, 0) { it } }
        history.end(g)
        assertFalse(history.canUndo)
    }

    @Test
    fun groupsNest() {
        val outer = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        val inner = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.end(inner)
        history.edit(1, 0) { store.toggleStep(1, 0, 2, listOf(60), 1f) }
        history.end(outer)
        history.undo()
        assertTrue((0..2).none { store.step(1, 0, it).on })
        assertFalse(history.canUndo)
    }

    @Test
    fun anExtraEndIsHarmless() {
        history.end(12345) // no group open, a token that never was
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.undo()
        assertTrue(store.step(1, 0, 0).on)
    }

    @Test
    fun undoDuringAGroupClosesItFirst() {
        val g = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        assertTrue(history.canUndo)
        assertTrue(history.undo())
        assertFalse(store.step(1, 0, 0).on)
        // Undo ended the group: the gesture's later edits are single steps; its end() does nothing.
        history.edit(1, 0) { store.toggleStep(1, 0, 5, listOf(60), 1f) }
        history.end(g)
        assertTrue(history.undo())
        assertFalse(store.step(1, 0, 5).on)
    }

    @Test
    fun recordedWritesInsideAGroupAreCapturedBeforeTheyLand() {
        // A take: Record on .. off. The mirror applies the engine's writes.
        val g = history.begin()
        history.edit(1, 0) { store.applyRecorded(com.groovebox.engine.RecordedEvent(com.groovebox.engine.RecordedEvent.Kind.Note, 1, 0, 3, 60, 0.9f)) }
        history.edit(1, 0) { store.applyRecorded(com.groovebox.engine.RecordedEvent(com.groovebox.engine.RecordedEvent.Kind.Note, 1, 0, 7, 62, 0.9f)) }
        history.end(g)
        sink.calls.clear()
        history.undo()
        assertFalse(store.step(1, 0, 3).on)
        assertFalse(store.step(1, 0, 7).on)
        assertEquals("the engine gets the restored steps", listOf("step 1 0 3", "step 1 0 7"), sink.calls)
    }

    @Test
    fun theHistoryIs32StepsDeep() {
        for (i in 0 until 40) history.edit(1, 0) { store.toggleStep(1, 0, i, listOf(60), 1f) }
        var n = 0
        while (history.undo()) n++
        assertEquals(32, n)
        assertEquals("the oldest 8 edits are kept", (0 until 8).toList(), (0 until 64).filter { store.step(1, 0, it).on })
    }

    @Test
    fun clearIsUndoable() {
        store.setSettings(1, 0, PatternSettings(length = 24, shuffle = 0.2f))
        store.toggleStep(1, 0, 3, listOf(60), 1f)
        store.setMotionValue(1, 0, 8, 3, 1000f)
        history.edit(1, 0) { store.clearPattern(1, 0) }
        history.undo()
        assertEquals(24, store.settings(1, 0).length)
        assertTrue(store.step(1, 0, 3).on)
        assertEquals(1000f, store.lane(1, 0, 8)!!.valueAt(3), 0f)
    }

    @Test
    fun undoKeepsTheEditsResultAndReturnsItsValue() {
        assertEquals(true, history.edit(0, 0) { store.toggleHit(0, 0, 0, 48, 1f) })
    }

    @Test
    fun clearDropsEverything() {
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.undo()
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    // --- review fixes ---

    @Test
    fun undoNeverClearsThePatternAndSendsOnlyWhatDiffers() {
        // A 32-step pattern playing: a clear would reset the engine's length (and playhead).
        store.setSettings(1, 0, PatternSettings(length = 32, gate = 0.3f))
        for (i in listOf(0, 8, 20, 31)) store.toggleStep(1, 0, i, listOf(60), 1f)
        store.setMotionValue(1, 0, 8, 20, 900f)
        history.edit(1, 0) { store.toggleStep(1, 0, 8, listOf(60), 1f) }
        sink.calls.clear()
        history.undo()
        assertEquals(listOf("step 1 0 8 [60 1.0 -1.0 1.0 0.0]"), sink.calls)
        sink.calls.clear()
        history.redo()
        assertEquals("a step becoming empty is sent empty", listOf("step 1 0 8"), sink.calls)
    }

    @Test
    fun undoOfAClearResendsOnlyWhatTheClearChanged() {
        store.setSettings(1, 0, PatternSettings(length = 24))
        store.toggleStep(1, 0, 3, listOf(60), 1f)
        store.setMotionValue(1, 0, 8, 3, 1000f)
        history.edit(1, 0) { store.clearPattern(1, 0) }
        sink.calls.clear()
        history.undo()
        assertTrue(sink.calls.none { it.startsWith("clear") })
        assertEquals(
            listOf("settings 1 0 24 1 0.0 0.5", "step 1 0 3 [60 1.0 -1.0 1.0 0.0]", "motion 1 0 8 3 1000.0"),
            sink.calls,
        )
    }

    @Test
    fun undoRestoresMotionLaneByLane() {
        store.setMotionValue(1, 0, 8, 0, 100f)
        store.setMotionValue(1, 0, 8, 1, 200f)
        history.begin().let { g ->
            history.edit(1, 0) { store.setMotionValue(1, 0, 8, 1, 300f) } // changed value
            history.edit(1, 0) { store.setMotionValue(1, 0, 8, 2, 400f) } // new value
            history.edit(1, 0) { store.setMotionValue(1, 0, 9, 0, 0.5f) } // new lane
            history.end(g)
        }
        sink.calls.clear()
        history.undo()
        assertEquals(listOf("clearLane 1 0 9", "motion 1 0 8 1 200.0", "motion 1 0 8 2 NaN"), sink.calls)
        assertEquals(listOf(8), store.lanes(1, 0).map { it.target })
        assertEquals(200f, store.lane(1, 0, 8)!!.valueAt(1), 0f)
        // Redo brings the lane back with its value.
        sink.calls.clear()
        history.redo()
        assertEquals(listOf("motion 1 0 8 1 300.0", "motion 1 0 8 2 400.0", "motion 1 0 9 0 0.5"), sink.calls)
        assertEquals(listOf(8, 9), store.lanes(1, 0).map { it.target })
    }

    @Test
    fun undoInsideAGestureKeepsTheRedoWhenTheGestureEnds() {
        val g = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.undo()
        assertTrue(history.canRedo)
        history.end(g) // the drag lifts: nothing to add
        assertTrue(history.canRedo)
        assertFalse(history.canUndo)
        // Nested ends of the old gesture do nothing either; a new gesture works as usual.
        val h = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.edit(1, 0) { store.toggleStep(1, 0, 2, listOf(60), 1f) }
        history.end(h)
        history.undo()
        assertFalse(store.step(1, 0, 1).on)
        assertFalse(store.step(1, 0, 2).on)
    }

    @Test
    fun undoEndsNestedGroupsCompletely() {
        val outer = history.begin()
        val inner = history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.redo() // nothing to redo, but it still ends the groups
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.end(inner)
        history.end(outer)
        history.undo()
        assertFalse(store.step(1, 0, 1).on)
        assertTrue("the first edit is its own step", store.step(1, 0, 0).on)
        history.undo()
        assertFalse(store.step(1, 0, 0).on)
    }
}
