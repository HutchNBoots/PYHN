package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundHistoryTest {
    private val synth3 = SoundTarget.Synth(3)
    private val h = SoundHistory()

    @Test
    fun oneDragIsOneStepWhateverHappenedInBetween() {
        h.begin("flt.cutoff", synth3, mapOf("flt.cutoff" to 2400.0))
        // ... 40 moves later ...
        assertTrue(h.end("flt.cutoff", mapOf("flt.cutoff" to 800.0)))
        assertTrue(h.canUndo)
        val undo = h.undo() as SoundEdit.Params
        assertEquals(synth3, undo.target)
        assertEquals(listOf(ParamChange("flt.cutoff", 2400.0, 800.0)), undo.changes)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals(undo, h.redo())
    }

    @Test
    fun aGestureThatChangedNothingLeavesNoStep() {
        h.begin("flt.reso", synth3, mapOf("flt.reso" to 0.2))
        assertFalse(h.end("flt.reso", mapOf("flt.reso" to 0.2)))
        assertFalse(h.canUndo)
        assertFalse("an end without a begin does nothing", h.end("osc.pw", mapOf("osc.pw" to 0.4)))
    }

    @Test
    fun twoStripsDraggedAtOnceAreTwoSteps() {
        h.begin("a", synth3, mapOf("a" to 0.0))
        h.begin("b", synth3, mapOf("b" to 0.0))
        h.end("b", mapOf("b" to 1.0))
        h.end("a", mapOf("a" to 1.0))
        assertEquals("a", (h.undo() as SoundEdit.Params).changes.single().key)
        assertEquals("b", (h.undo() as SoundEdit.Params).changes.single().key)
        assertNull(h.undo())
    }

    @Test
    fun aStripDrivingTwoParamsRecordsOnlyWhatMoved() {
        h.begin("lfo.rate", synth3, mapOf("lfo.rate" to 2.0, "lfo.sync" to 0.0))
        h.end("lfo.rate", mapOf("lfo.rate" to 4.0, "lfo.sync" to 1.0))
        assertEquals(2, (h.undo() as SoundEdit.Params).changes.size)
        h.begin("lfo.rate", synth3, mapOf("lfo.rate" to 2.0, "lfo.sync" to 1.0))
        h.end("lfo.rate", mapOf("lfo.rate" to 3.0, "lfo.sync" to 1.0))
        assertEquals(listOf(ParamChange("lfo.rate", 2.0, 3.0)), (h.undo() as SoundEdit.Params).changes)
    }

    @Test
    fun aNewEditDropsTheRedo() {
        h.record(SoundEdit.Params(synth3, listOf(ParamChange("a", 0.0, 1.0))))
        h.undo()
        assertTrue(h.canRedo)
        h.record(SoundEdit.Params(SoundTarget.Drum(2), listOf(ParamChange("drum.decay", 1.0, 0.5))))
        assertFalse(h.canRedo)
        assertEquals(SoundTarget.Drum(2), h.undo()!!.target)
    }

    @Test
    fun synthAndDrumShareOneHistory() {
        h.record(SoundEdit.Params(synth3, listOf(ParamChange("a", 0.0, 1.0))))
        h.record(SoundEdit.Params(SoundTarget.Drum(0), listOf(ParamChange("drum.pitch", 0.0, 3.0))))
        assertEquals(SoundTarget.Drum(0), h.undo()!!.target)
        assertEquals(synth3, h.undo()!!.target)
    }

    @Test
    fun theOldestStepsFallOffAtTheDepth() {
        val small = SoundHistory(depth = 3)
        repeat(5) { i -> small.record(SoundEdit.Params(synth3, listOf(ParamChange("k$i", 0.0, 1.0)))) }
        val keys = generateSequence { (small.undo() as SoundEdit.Params?)?.changes?.single()?.key }.toList()
        assertEquals(listOf("k4", "k3", "k2"), keys)
    }

    @Test
    fun clearForgetsEverythingIncludingOpenGestures() {
        h.record(SoundEdit.Params(synth3, listOf(ParamChange("a", 0.0, 1.0))))
        h.begin("b", synth3, mapOf("b" to 0.0))
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.end("b", mapOf("b" to 1.0)))
    }

    @Test
    fun revisionMovesOnEveryChange() {
        val r0 = h.revision
        h.record(SoundEdit.Params(synth3, listOf(ParamChange("a", 0.0, 1.0))))
        val r1 = h.revision
        h.undo()
        assertTrue(r1 > r0 && h.revision > r1)
    }

    @Test
    fun anUndoDuringAnOpenGestureReBaselinesIt() {
        val drum = SoundTarget.Drum(2)
        h.record(SoundEdit.Params(drum, listOf(ParamChange("drum.decay", 1.0, 0.5))))
        // A finger rests on Decay (gesture open from 0.5), the other thumb taps Undo: Decay is 1.0 again.
        h.begin("drum:drum.decay", drum, mapOf("drum.decay" to 0.5))
        h.undo()
        // The drag then moves it to 0.8: one step from 1.0, so undoing it returns to 1.0, not 0.5.
        assertTrue(h.end("drum:drum.decay", mapOf("drum.decay" to 0.8)))
        assertEquals(listOf(ParamChange("drum.decay", 1.0, 0.8)), (h.undo() as SoundEdit.Params).changes)
    }

    @Test
    fun aRedoReBaselinesToTheAfterValuesAndOtherTargetsAreLeftAlone() {
        val synth = SoundTarget.Synth(2)
        h.record(SoundEdit.Params(synth, listOf(ParamChange("flt.reso", 0.2, 0.7))))
        h.undo()
        h.begin("s2", synth, mapOf("flt.reso" to 0.2, "flt.cutoff" to 900.0))
        h.begin("s3", SoundTarget.Synth(3), mapOf("flt.reso" to 0.2))
        h.redo()
        assertTrue(h.end("s2", mapOf("flt.reso" to 0.7, "flt.cutoff" to 900.0)).not())
        assertTrue(h.end("s3", mapOf("flt.reso" to 0.7)))
        assertEquals(ParamChange("flt.reso", 0.2, 0.7), (h.undo() as SoundEdit.Params).changes.single())
    }

    @Test
    fun aWholeSoundUndoReBaselinesEveryKeyOfThatSlot() {
        val synth = SoundTarget.Synth(1)
        val a = SynthSound(PresetRef("A", 0), mapOf("x" to 1.0, "y" to 2.0), emptyMap(), emptyMap())
        val b = SynthSound(PresetRef("B", 1), mapOf("x" to 5.0, "y" to 6.0), emptyMap(), emptyMap())
        h.record(SoundEdit.Whole(synth, a, b))
        h.begin("x", synth, mapOf("x" to 5.0))
        h.undo()
        assertFalse("x is back at 1.0 and stays: no step", h.end("x", mapOf("x" to 1.0)))
    }
}
