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

    override fun setStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int) {
        require(hitCount in 0..SeqLimits.HITS && hits.size >= hitCount * SeqLimits.HIT_FIELDS) { "bad hit buffer" }
        val text = (0 until hitCount).joinToString(" ") { h ->
            val o = h * SeqLimits.HIT_FIELDS
            "[${hits[o].toInt()} ${hits[o + 1]} ${hits[o + 2]} ${hits[o + 3]} ${hits[o + 4]}]"
        }
        calls += "step $slot $pattern $step $text".trimEnd()
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

    private fun hits(vararg notes: Int, velocity: Float = 0.8f) = Step(notes.map { Hit(it, velocity) })

    @Test
    fun toggleTurnsAnEmptyStepOnWithTheGivenNotesAndSendsEveryHit() {
        store.toggleStep(1, 2, 5, listOf(60, 64), 0.9f)
        assertEquals(hits(60, 64, velocity = 0.9f), store.step(1, 2, 5))
        assertTrue(store.step(1, 2, 5).on)
        assertEquals(listOf("step 1 2 5 [60 0.9 -1.0 1.0 0.0] [64 0.9 -1.0 1.0 0.0]"), sink.calls)
    }

    @Test
    fun toggleOffEmptiesTheStep() {
        store.toggleStep(1, 0, 3, listOf(60), 0.8f)
        store.toggleStep(1, 0, 3, listOf(72), 0.5f)
        assertEquals(Step(), store.step(1, 0, 3))
        assertEquals("step 1 0 3", sink.calls.last())
    }

    @Test
    fun aLaneToggleAddsOrRemovesOnlyThatSound() {
        // Owner's acceptance 1: kick on 1 5 9 13, snare on 5 13; both play on 5 and 13.
        for (i in listOf(0, 4, 8, 12)) assertTrue(store.toggleHit(0, 0, i, 48, 0.8f))
        for (i in listOf(4, 12)) assertTrue(store.toggleHit(0, 0, i, 50, 0.8f))
        assertEquals(listOf(48, 50), store.step(0, 0, 4).notes)
        assertEquals(listOf(48), store.step(0, 0, 8).notes)
        assertEquals(listOf(48, 50), store.step(0, 0, 12).notes)
        // Taking the kick off step 5 keeps the snare.
        assertTrue(store.toggleHit(0, 0, 4, 48, 0.8f))
        assertEquals(listOf(50), store.step(0, 0, 4).notes)
        assertEquals("step 0 0 4 [50 0.8 -1.0 1.0 0.0]", sink.calls.last())
    }

    @Test
    fun aLaneToggleKeepsTheOtherHitsFields() {
        store.setStep(0, 0, 2, Step(listOf(Hit(54, 0.5f, 2f, 0.4f, -0.25f))))
        store.toggleHit(0, 0, 2, 48, 1f)
        assertEquals(Step(listOf(Hit(54, 0.5f, 2f, 0.4f, -0.25f), Hit(48, 1f))), store.step(0, 0, 2))
    }

    @Test
    fun aStepHoldsAtMostEightSounds() {
        for (n in 48 until 56) assertTrue(store.toggleHit(0, 0, 0, n, 0.8f))
        val calls = sink.calls.size
        assertFalse("a ninth sound is refused", store.toggleHit(0, 0, 0, 60, 0.8f))
        assertEquals(8, store.step(0, 0, 0).hits.size)
        assertEquals(calls, sink.calls.size)
        // Removing still works when full.
        assertTrue(store.toggleHit(0, 0, 0, 50, 0.8f))
        assertEquals(7, store.step(0, 0, 0).hits.size)
    }

    @Test
    fun setStepNotesReplacesTheChordAndKeepsTheStepsSharedFields() {
        store.setStep(2, 0, 0, Step(listOf(Hit(40, 0.5f, 3f, 0.6f, 0.1f))))
        store.setStepNotes(2, 0, 0, listOf(48, 51, 55), 0.7f)
        assertEquals(Step(listOf(48, 51, 55).map { Hit(it, 0.7f, 3f, 0.6f, 0.1f) }), store.step(2, 0, 0))
        store.setStepNotes(2, 0, 1, listOf(60), 0.9f)
        assertEquals(Step(listOf(Hit(60, 0.9f))), store.step(2, 0, 1))
    }

    @Test
    fun editHitsOnALaneEditsOnlyThatHitAndNeverCreatesOne() {
        store.setStep(0, 0, 4, Step(listOf(Hit(48, 1f), Hit(50, 0.9f))))
        store.editHits(0, 0, 4, lane = 50) { it.copy(nudge = 0.25f) }
        assertEquals(Step(listOf(Hit(48, 1f), Hit(50, 0.9f, nudge = 0.25f))), store.step(0, 0, 4))
        // Build-22: the lane has no hit here, so the strip changes nothing (it stays dimmed).
        val n = sink.calls.size
        store.editHits(0, 0, 4, lane = 54) { it.copy(length = 2f) }
        store.editHits(0, 0, 5, lane = 54) { it.copy(length = 2f) }
        assertFalse(store.step(0, 0, 5).on)
        assertEquals(n, sink.calls.size)
    }

    @Test
    fun editHitsWithoutALaneEditsEveryHitAndLeavesAnEmptyStepEmpty() {
        store.setStep(1, 0, 0, Step(listOf(Hit(60, 0.5f), Hit(64, 0.6f))))
        store.editHits(1, 0, 0, lane = null) { it.copy(probability = 0.5f) }
        assertEquals(Step(listOf(Hit(60, 0.5f, probability = 0.5f), Hit(64, 0.6f, probability = 0.5f))), store.step(1, 0, 0))
        // Build-22: no chord is written into an empty selected step.
        store.editHits(1, 0, 1, lane = null) { it.copy(velocity = 0.3f) }
        assertFalse(store.step(1, 0, 1).on)
    }

    @Test
    fun hasContentSeesStepsAndMotionButNotSettings() {
        assertFalse(store.hasContent(1, 2))
        store.updateSettings(1, 2) { it.copy(length = 32) }
        assertFalse(store.hasContent(1, 2))
        store.setMotionValue(1, 2, 8, 3, 0.5f)
        assertTrue(store.hasContent(1, 2))
        store.toggleStep(1, 3, 0, listOf(60), 1f)
        assertTrue(store.hasContent(1, 3))
    }

    @Test
    fun clearLaneRemovesOneSoundFromEveryStep() {
        for (i in listOf(0, 4, 8)) store.toggleHit(0, 3, i, 48, 1f)
        for (i in listOf(4, 12)) store.toggleHit(0, 3, i, 50, 1f)
        store.setSettings(0, 3, PatternSettings(length = 32))
        sink.calls.clear()
        store.clearLane(0, 3, 50)
        assertEquals(listOf("step 0 3 4 [48 1.0 -1.0 1.0 0.0]", "step 0 3 12"), sink.calls)
        assertEquals(listOf(48), store.step(0, 3, 4).notes)
        assertEquals(32, store.settings(0, 3).length)
    }

    @Test
    fun hitsAreClamped() {
        store.setStep(
            1, 0, 0,
            Step(
                listOf(
                    Hit(-5, velocity = 2f, length = 99f, probability = -1f, nudge = 3f),
                    Hit(200, velocity = 0f, length = 0.001f, probability = 2f, nudge = -3f),
                    Hit(60, velocity = Float.NaN, length = Float.NaN, probability = Float.NaN, nudge = Float.NaN),
                    Hit(61, length = -0.3f),
                ),
            ),
        )
        assertEquals(
            Step(
                listOf(
                    Hit(0, 1f, 16f, 0f, 0.5f),
                    Hit(127, 0.01f, 1f / 16, 1f, -0.5f),
                    Hit(60, SeqLimits.DEFAULT_VELOCITY, SeqLimits.PATTERN_GATE, 1f, 0f),
                    Hit(61, length = SeqLimits.PATTERN_GATE),
                ),
            ),
            store.step(1, 0, 0),
        )
    }

    @Test
    fun aStepKeepsAtMostEightHitsAndOneHitPerNote() {
        store.setStep(0, 0, 0, Step((0 until 10).map { Hit(48 + it) }))
        assertEquals((48 until 56).toList(), store.step(0, 0, 0).notes)
        store.setStep(0, 0, 1, Step(listOf(Hit(48, 1f), Hit(48, 0.2f), Hit(50))))
        assertEquals(Step(listOf(Hit(48, 1f), Hit(50))), store.step(0, 0, 1))
    }

    @Test
    fun anUnchangedEditSendsNothing() {
        store.setStep(1, 0, 0, hits(60))
        store.setStep(1, 0, 0, hits(60))
        store.updateSettings(1, 0) { it }
        assertEquals(1, sink.calls.size)
    }

    @Test
    fun outOfRangeIndexesAreIgnored() {
        store.setStep(8, 0, 0, hits(60))
        store.setStep(0, 16, 0, hits(60))
        store.setStep(0, 0, 64, hits(60))
        store.setStep(-1, 0, 0, hits(60))
        store.toggleHit(0, 0, 64, 48, 1f)
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

    // --- one pattern's copy (undo) ---

    @Test
    fun restorePatternPutsBackOnePatternAndSendsWhatDiffers() {
        store.setSettings(1, 2, PatternSettings(length = 12, gate = 0.3f))
        store.toggleStep(1, 2, 0, listOf(60), 1f)
        store.setMotionValue(1, 2, 8, 0, 900f)
        store.toggleStep(1, 3, 0, listOf(62), 1f)
        val saved = store.patternSnapshot(1, 2)
        store.toggleStep(1, 2, 0, listOf(60), 1f)
        store.toggleStep(1, 2, 5, listOf(67), 1f)
        store.clearMotionLane(1, 2, 8)
        sink.calls.clear()
        store.restorePattern(saved)
        assertEquals(saved, store.patternSnapshot(1, 2))
        assertEquals(
            // Only what differs, never a clear (it would reset a playing pattern's length and position).
            listOf("step 1 2 0 [60 1.0 -1.0 1.0 0.0]", "step 1 2 5", "motion 1 2 8 0 900.0"),
            sink.calls,
        )
        assertTrue("other patterns untouched", store.step(1, 3, 0).on)
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
    fun aRecordedNoteAddsAHitWithThePlayedVelocityAndThePatternGate() {
        store.applyRecorded(note(1, 0, 6, 60, 0.7f))
        assertEquals(Step(listOf(Hit(60, 0.7f, SeqLimits.PATTERN_GATE, 1f, 0f))), store.step(1, 0, 6))
        store.applyRecorded(note(1, 0, 6, 64, 0.9f))
        assertEquals(Step(listOf(Hit(60, 0.7f), Hit(64, 0.9f))), store.step(1, 0, 6))
        assertTrue("recording never calls the engine back", sink.calls.isEmpty())
    }

    @Test
    fun aRecordedNoteAlreadyThereChangesNothing() {
        store.setStep(1, 0, 6, Step(listOf(Hit(60, 0.7f, 2f, 0.5f, 0.1f))))
        val rev = store.revision
        store.applyRecorded(note(1, 0, 6, 60, 0.4f))
        assertEquals(Step(listOf(Hit(60, 0.7f, 2f, 0.5f, 0.1f))), store.step(1, 0, 6))
        assertEquals(rev, store.revision)
    }

    @Test
    fun aNinthRecordedNoteReplacesTheLastHit() {
        for (n in 60 until 68) store.applyRecorded(note(1, 0, 0, n, 0.5f))
        assertEquals((60 until 68).toList(), store.step(1, 0, 0).notes)
        store.applyRecorded(note(1, 0, 0, 70, 0.6f))
        assertEquals((60 until 67).toList() + 70, store.step(1, 0, 0).notes)
        assertEquals(Hit(70, 0.6f), store.step(1, 0, 0).hits.last())
        store.applyRecorded(note(1, 0, 0, 62, 0.6f))
        assertEquals((60 until 67).toList() + 70, store.step(1, 0, 0).notes)
    }

    @Test
    fun aRecordedNoteKeepsTheOtherHits() {
        store.setStep(1, 0, 2, Step(listOf(Hit(50, 0.3f, 0.5f, 0.4f, -0.1f))))
        store.applyRecorded(note(1, 0, 2, 52, 1f))
        assertEquals(Step(listOf(Hit(50, 0.3f, 0.5f, 0.4f, -0.1f), Hit(52, 1f))), store.step(1, 0, 2))
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
        store.toggleHit(1, 0, 9, 60, 1f)
        store.clearLane(1, 0, 61) // nothing to clear
        assertEquals(6, calls)
        assertEquals(6L, store.revision)
    }

    @Test
    fun lanesReturnsACopy() {
        store.setMotionValue(1, 0, 3, 0, 0.5f)
        val lanes = store.lanes(1, 0)
        store.clearMotionLane(1, 0, 3)
        assertEquals(1, lanes.size)
        assertSame(lanes[0], lanes.first())
    }

    // --- what a pad shows ---

    @Test
    fun aPadShowsTheLaneBrightOtherSoundsAsADotAndEmptyDark() {
        val step = Step(listOf(Hit(48), Hit(54)))
        assertEquals(PadMark.Lane, PadMark.of(step, lane = 48))
        assertEquals(PadMark.Other, PadMark.of(step, lane = 50))
        assertEquals(PadMark.Empty, PadMark.of(Step(), lane = 48))
        // A synth slot has no lane: any hit lights the pad.
        assertEquals(PadMark.Lane, PadMark.of(step, lane = null))
        assertEquals(PadMark.Empty, PadMark.of(Step(), lane = null))
    }
}
