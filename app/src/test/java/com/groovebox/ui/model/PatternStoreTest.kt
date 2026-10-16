package com.groovebox.ui.model

import com.groovebox.engine.RecordedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every engine call the store makes, as text. */
class RecordingSink : PatternSink {
    val calls = mutableListOf<String>()

    override fun setPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float) {
        calls += "settings $slot $pattern $length $rate $shuffle $gate"
    }

    override fun setStep(slot: Int, pattern: Int, step: Int, on: Boolean, notes: IntArray, velocity: Float, gate: Float, probability: Float) {
        calls += "step $slot $pattern $step $on ${notes.toList()} $velocity $gate $probability"
    }

    override fun setMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float) {
        calls += "motion $slot $pattern $target $step $value"
    }

    override fun clearMotionLane(slot: Int, pattern: Int, target: Int) {
        calls += "clearLane $slot $pattern $target"
    }

    override fun clearPattern(slot: Int, pattern: Int) {
        calls += "clear $slot $pattern"
    }

    override fun setTempo(bpm: Double) {
        calls += "tempo $bpm"
    }
}

class PatternStoreTest {
    private val sink = RecordingSink()
    private val store = PatternStore(sink)

    private fun note(slot: Int, pattern: Int, step: Int, note: Int, velocity: Float) =
        RecordedEvent(RecordedEvent.Kind.Note, slot, pattern, step, note, velocity)

    private fun motion(slot: Int, pattern: Int, step: Int, target: Int, value: Float) =
        RecordedEvent(RecordedEvent.Kind.Motion, slot, pattern, step, target, value)

    // --- defaults ---

    @Test
    fun startsEmptyWithTheEngineDefaults() {
        for (slot in 0 until SeqLimits.SLOTS) for (p in 0 until SeqLimits.PATTERNS) {
            assertEquals(PatternSettings(16, 1, 0f, 0.5f), store.settings(slot, p))
            assertFalse(store.step(slot, p, 63).on)
            assertTrue(store.lanes(slot, p).isEmpty())
        }
        assertEquals(120.0, store.tempo, 0.0)
        assertTrue(sink.calls.isEmpty())
    }

    // --- step edits ---

    @Test
    fun toggleTurnsAStepOnWithTheGivenNotesAndSendsIt() {
        store.toggleStep(1, 2, 5, listOf(60, 64), 0.9f)
        assertEquals(Step(on = true, notes = listOf(60, 64), velocity = 0.9f), store.step(1, 2, 5))
        assertEquals(listOf("step 1 2 5 true [60, 64] 0.9 -1.0 1.0"), sink.calls)
    }

    @Test
    fun toggleOffKeepsTheNotes() {
        store.toggleStep(1, 0, 3, listOf(60), 0.8f)
        store.toggleStep(1, 0, 3, listOf(72), 0.5f)
        assertEquals(Step(on = false, notes = listOf(60), velocity = 0.8f), store.step(1, 0, 3))
        assertEquals("step 1 0 3 false [60] 0.8 -1.0 1.0", sink.calls.last())
    }

    @Test
    fun setStepNotesTurnsTheStepOn() {
        store.setStepNotes(2, 0, 0, listOf(48, 51, 55), 0.7f)
        assertEquals(Step(true, listOf(48, 51, 55), 0.7f), store.step(2, 0, 0))
    }

    @Test
    fun stepsAreClamped() {
        store.setStep(1, 0, 0, Step(true, listOf(-5, 60, 200, 61, 62), velocity = 2f, gate = 3f, probability = -1f))
        assertEquals(Step(true, listOf(0, 60, 127, 61), velocity = 1f, gate = 1f, probability = 0f), store.step(1, 0, 0))
        store.setStep(1, 0, 1, Step(true, listOf(60), gate = 0.01f, velocity = Float.NaN, probability = Float.NaN))
        assertEquals(Step(true, listOf(60), velocity = 0.8f, gate = 0.05f, probability = 1f), store.step(1, 0, 1))
        store.setStep(1, 0, 2, Step(true, listOf(60), gate = -0.3f))
        assertEquals(-1f, store.step(1, 0, 2).gate, 0f)
    }

    @Test
    fun anUnchangedEditSendsNothing() {
        store.setStep(1, 0, 0, Step(true, listOf(60)))
        store.setStep(1, 0, 0, Step(true, listOf(60)))
        store.updateSettings(1, 0) { it }
        assertEquals(1, sink.calls.size)
    }

    @Test
    fun outOfRangeIndexesAreIgnored() {
        store.setStep(8, 0, 0, Step(true))
        store.setStep(0, 16, 0, Step(true))
        store.setStep(0, 0, 64, Step(true))
        store.setStep(-1, 0, 0, Step(true))
        store.setSettings(0, -1, PatternSettings(length = 8))
        assertTrue(store.setMotionValue(0, 0, 3, 64, 0.5f).not())
        assertTrue(sink.calls.isEmpty())
        assertEquals(Step(), store.step(0, 0, 64))
    }

    @Test
    fun slotsAndPatternsAreIndependent() {
        store.toggleStep(0, 0, 0, listOf(48), 1f)
        assertFalse(store.step(0, 1, 0).on)
        assertFalse(store.step(1, 0, 0).on)
        assertTrue(store.step(0, 0, 0).on)
    }

    // --- settings ---

    @Test
    fun settingsAreClampedBeforeSending() {
        store.setSettings(3, 4, PatternSettings(length = 0, rate = 7, shuffle = 1.5f, gate = 0f))
        assertEquals(PatternSettings(1, 3, 1f, 0.05f), store.settings(3, 4))
        assertEquals("settings 3 4 1 3 1.0 0.05", sink.calls.last())
        store.setSettings(3, 4, PatternSettings(length = 99, rate = -1, shuffle = -2f, gate = 9f))
        assertEquals(PatternSettings(64, 0, 0f, 1f), store.settings(3, 4))
        store.setSettings(3, 4, PatternSettings(length = 12, shuffle = Float.NaN, gate = Float.NaN))
        assertEquals(PatternSettings(12, 1, 0f, 0.5f), store.settings(3, 4))
    }

    @Test
    fun tempoIsClampedAndRoundedToATenth() {
        store.setTempo(133.37)
        assertEquals(133.4, store.tempo, 1e-9)
        store.setTempo(10.0)
        assertEquals(40.0, store.tempo, 0.0)
        store.setTempo(999.0)
        assertEquals(240.0, store.tempo, 0.0)
        assertEquals(listOf("tempo 133.4", "tempo 40.0", "tempo 240.0"), sink.calls)
    }

    @Test
    fun tempoIsOnlyResentWhenForced() {
        store.setTempo(120.0)
        assertTrue(sink.calls.isEmpty())
        store.setTempo(120.0, force = true)
        assertEquals(listOf("tempo 120.0"), sink.calls)
    }

    // --- motion ---

    @Test
    fun motionValuesCreateLanesAndNaNClearsAValue() {
        assertTrue(store.setMotionValue(1, 0, 8, 4, 900f))
        val lane = store.lane(1, 0, 8)!!
        assertEquals(900f, lane.valueAt(4), 0f)
        assertTrue(lane.valueAt(5).isNaN())
        assertTrue(store.setMotionValue(1, 0, 8, 4, Float.NaN))
        assertFalse(lane.hasValueAt(4))
        assertEquals(listOf("motion 1 0 8 4 900.0", "motion 1 0 8 4 NaN"), sink.calls)
        // The lane stays (the engine keeps it too) until it is cleared.
        assertEquals(1, store.lanes(1, 0).size)
    }

    @Test
    fun clearingAValueOnAMissingLaneDoesNotCreateOne() {
        assertFalse(store.setMotionValue(1, 0, 8, 4, Float.NaN))
        assertTrue(store.lanes(1, 0).isEmpty())
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun aPatternHoldsAtMostEightLanes() {
        for (t in 0 until 8) assertTrue(store.setMotionValue(1, 0, t, 0, 0.5f))
        assertFalse(store.setMotionValue(1, 0, 8, 0, 0.5f))
        assertEquals(8, store.lanes(1, 0).size)
        assertNull(store.lane(1, 0, 8))
        assertEquals(8, sink.calls.size)
        // Existing lanes still take values.
        assertTrue(store.setMotionValue(1, 0, 3, 7, 0.1f))
        // Another pattern has its own eight.
        assertTrue(store.setMotionValue(1, 1, 8, 0, 0.5f))
    }

    @Test
    fun clearMotionLaneFreesTheLane() {
        for (t in 0 until 8) store.setMotionValue(1, 0, t, 0, 0.5f)
        store.clearMotionLane(1, 0, 2)
        assertEquals("clearLane 1 0 2", sink.calls.last())
        assertEquals(7, store.lanes(1, 0).size)
        assertTrue(store.setMotionValue(1, 0, 9, 0, 0.5f))
        val n = sink.calls.size
        store.clearMotionLane(1, 0, 42) // no such lane
        assertEquals(n, sink.calls.size)
    }

    @Test
    fun latestLaneAtIsTheLaneWrittenLastAtThatStep() {
        store.setMotionValue(1, 0, 8, 3, 1000f)
        store.setMotionValue(1, 0, 9, 3, 0.5f)
        store.setMotionValue(1, 0, 8, 4, 2000f)
        assertEquals(9, store.latestLaneAt(1, 0, 3)!!.target)
        assertEquals(8, store.latestLaneAt(1, 0, 4)!!.target)
        store.setMotionValue(1, 0, 8, 3, 1100f)
        assertEquals(8, store.latestLaneAt(1, 0, 3)!!.target)
        store.setMotionValue(1, 0, 8, 3, Float.NaN)
        assertEquals(9, store.latestLaneAt(1, 0, 3)!!.target)
        assertNull(store.latestLaneAt(1, 0, 5))
        assertNull(store.latestLaneAt(1, 0, 64))
    }

    // --- clear ---

    @Test
    fun clearPatternResetsStepsMotionAndSettings() {
        store.setSettings(2, 3, PatternSettings(32, 2, 0.3f, 0.8f))
        store.toggleStep(2, 3, 10, listOf(60), 1f)
        store.setMotionValue(2, 3, 1, 10, 0.4f)
        store.toggleStep(2, 4, 10, listOf(60), 1f)
        store.clearPattern(2, 3)
        assertEquals("clear 2 3", sink.calls.last())
        assertEquals(PatternSettings(), store.settings(2, 3))
        assertEquals(Step(), store.step(2, 3, 10))
        assertTrue(store.lanes(2, 3).isEmpty())
        assertTrue("other patterns untouched", store.step(2, 4, 10).on)
    }

    // --- recorded writes (RecordedEvent rules) ---

    @Test
    fun aRecordedNoteTurnsTheStepOnAddsTheNoteAndSetsVelocity() {
        store.applyRecorded(note(1, 0, 6, 60, 0.7f))
        assertEquals(Step(on = true, notes = listOf(60), velocity = 0.7f), store.step(1, 0, 6))
        store.applyRecorded(note(1, 0, 6, 64, 0.9f))
        assertEquals(Step(on = true, notes = listOf(60, 64), velocity = 0.9f), store.step(1, 0, 6))
        assertTrue("recording never calls the engine back", sink.calls.isEmpty())
    }

    @Test
    fun aRecordedNoteAlreadyThereOnlyChangesVelocity() {
        store.applyRecorded(note(1, 0, 6, 60, 0.7f))
        store.applyRecorded(note(1, 0, 6, 64, 0.7f))
        store.applyRecorded(note(1, 0, 6, 60, 0.4f))
        assertEquals(listOf(60, 64), store.step(1, 0, 6).notes)
        assertEquals(0.4f, store.step(1, 0, 6).velocity, 0f)
    }

    @Test
    fun aFifthRecordedNoteReplacesTheLastOne() {
        for (n in listOf(60, 62, 64, 65)) store.applyRecorded(note(1, 0, 0, n, 0.5f))
        assertEquals(listOf(60, 62, 64, 65), store.step(1, 0, 0).notes)
        store.applyRecorded(note(1, 0, 0, 67, 0.6f))
        assertEquals(listOf(60, 62, 64, 67), store.step(1, 0, 0).notes)
        store.applyRecorded(note(1, 0, 0, 69, 0.6f))
        assertEquals(listOf(60, 62, 64, 69), store.step(1, 0, 0).notes)
        // A note already among the four does not replace anything.
        store.applyRecorded(note(1, 0, 0, 62, 0.6f))
        assertEquals(listOf(60, 62, 64, 69), store.step(1, 0, 0).notes)
    }

    @Test
    fun aRecordedNoteOnAnOffStepKeepsItsOtherFields() {
        store.setStep(1, 0, 2, Step(on = false, notes = listOf(50), gate = 0.3f, probability = 0.4f))
        store.applyRecorded(note(1, 0, 2, 52, 1f))
        assertEquals(Step(on = true, notes = listOf(50, 52), velocity = 1f, gate = 0.3f, probability = 0.4f), store.step(1, 0, 2))
    }

    @Test
    fun recordedMotionWritesTheLaneAndCreatesItIfNeeded() {
        store.applyRecorded(motion(1, 2, 7, 8, 1234f))
        assertEquals(1234f, store.lane(1, 2, 8)!!.valueAt(7), 0f)
        store.applyRecorded(motion(1, 2, 8, 8, 99f))
        assertEquals(1, store.lanes(1, 2).size)
        assertEquals(8, store.latestLaneAt(1, 2, 8)!!.target)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun recordedMotionForANinthTargetIsIgnored() {
        for (t in 0 until 8) store.applyRecorded(motion(1, 0, 0, t, 0.5f))
        store.applyRecorded(motion(1, 0, 0, 20, 0.5f))
        assertEquals(8, store.lanes(1, 0).size)
        assertNull(store.lane(1, 0, 20))
    }

    @Test
    fun recordedDrumMotionUsesTheKeyTimesParamTarget() {
        val target = 2 * 8 + 1 // key 2, Decay
        store.applyRecorded(motion(0, 0, 4, target, 0.25f))
        assertEquals(0.25f, store.lane(0, 0, target)!!.valueAt(4), 0f)
    }

    @Test
    fun recordedEventsOutOfRangeAreIgnored() {
        val before = store.revision
        store.applyRecorded(note(9, 0, 0, 60, 1f))
        store.applyRecorded(note(1, 16, 0, 60, 1f))
        store.applyRecorded(note(1, 0, 64, 60, 1f))
        store.applyRecorded(motion(1, 0, -1, 3, 1f))
        assertEquals(before, store.revision)
    }

    // --- change notification ---

    @Test
    fun everyChangeBumpsTheRevisionAndCallsOnChange() {
        var calls = 0
        store.onChange = { calls++ }
        store.toggleStep(1, 0, 0, listOf(60), 1f)
        store.applyRecorded(note(1, 0, 1, 60, 1f))
        store.setMotionValue(1, 0, 3, 0, 0.5f)
        store.setTempo(100.0)
        store.toggleStep(1, 0, 0, listOf(60), 1f)
        store.setStep(1, 0, 0, store.step(1, 0, 0)) // no change
        assertEquals(5, calls)
        assertEquals(5L, store.revision)
    }

    @Test
    fun lanesReturnsACopy() {
        store.setMotionValue(1, 0, 3, 0, 0.5f)
        val lanes = store.lanes(1, 0)
        store.clearMotionLane(1, 0, 3)
        assertEquals(1, lanes.size)
        assertSame(lanes[0], lanes.first())
    }
}
