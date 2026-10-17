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
        assertEquals(listOf("clear 0 0", "step 0 0 4 [48 1.0 -1.0 1.0 0.0]"), sink.calls)
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
        history.begin()
        for (v in 1..20) history.edit(1, 0) { store.editHits(1, 0, 3, null, { emptyList() }) { it.copy(velocity = v / 40f) } }
        history.end()
        assertEquals(0.5f, store.step(1, 0, 3).hits[0].velocity, 0f)
        history.undo()
        assertEquals(Step(listOf(Hit(60, 1f))), store.step(1, 0, 3))
        history.undo()
        assertFalse(store.step(1, 0, 3).on)
    }

    @Test
    fun aGroupCanTouchSeveralPatterns() {
        // A record take across a pattern switch, or two slots.
        history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.edit(1, 1) { store.toggleStep(1, 1, 0, listOf(62), 1f) }
        history.edit(1, 0) { store.toggleStep(1, 0, 2, listOf(64), 1f) }
        history.end()
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
        history.begin()
        history.edit(1, 0) { store.updateSettings(1, 0) { it } }
        history.end()
        assertFalse(history.canUndo)
    }

    @Test
    fun groupsNest() {
        history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.end()
        history.edit(1, 0) { store.toggleStep(1, 0, 2, listOf(60), 1f) }
        history.end()
        history.undo()
        assertTrue((0..2).none { store.step(1, 0, it).on })
        assertFalse(history.canUndo)
    }

    @Test
    fun anExtraEndIsHarmless() {
        history.end()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        history.edit(1, 0) { store.toggleStep(1, 0, 1, listOf(60), 1f) }
        history.undo()
        assertTrue(store.step(1, 0, 0).on)
    }

    @Test
    fun undoDuringAGroupClosesItFirst() {
        history.begin()
        history.edit(1, 0) { store.toggleStep(1, 0, 0, listOf(60), 1f) }
        assertTrue(history.canUndo)
        assertTrue(history.undo())
        assertFalse(store.step(1, 0, 0).on)
        // Later edits of the still-open gesture start a fresh step, closed by the end().
        history.edit(1, 0) { store.toggleStep(1, 0, 5, listOf(60), 1f) }
        history.end()
        assertTrue(history.undo())
        assertFalse(store.step(1, 0, 5).on)
    }

    @Test
    fun recordedWritesInsideAGroupAreCapturedBeforeTheyLand() {
        // A take: Record on .. off. The mirror applies the engine's writes.
        history.begin()
        history.edit(1, 0) { store.applyRecorded(com.groovebox.engine.RecordedEvent(com.groovebox.engine.RecordedEvent.Kind.Note, 1, 0, 3, 60, 0.9f)) }
        history.edit(1, 0) { store.applyRecorded(com.groovebox.engine.RecordedEvent(com.groovebox.engine.RecordedEvent.Kind.Note, 1, 0, 7, 62, 0.9f)) }
        history.end()
        sink.calls.clear()
        history.undo()
        assertFalse(store.step(1, 0, 3).on)
        assertFalse(store.step(1, 0, 7).on)
        assertEquals("the engine gets the restored pattern", listOf("clear 1 0"), sink.calls)
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
}
