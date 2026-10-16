package com.groovebox.ui.model

import com.groovebox.engine.RecordedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternSnapshotTest {
    private fun edited(): PatternStore {
        val s = PatternStore(RecordingSink())
        DemoContent.write(s)
        s.setSettings(3, 5, PatternSettings(length = 48, rate = 3, shuffle = 0.3f, gate = 0.7f))
        s.setStep(3, 5, 40, Step(true, listOf(60, 64, 67), 0.4f, 0.2f, 0.6f))
        s.setMotionValue(1, 0, 8, 2, 1200f)
        s.setMotionValue(1, 0, 9, 2, 0.5f) // later stamp: the latest lane at step 2
        s.setMotionValue(1, 0, 8, 4, 900f)
        s.applyRecorded(RecordedEvent(RecordedEvent.Kind.Motion, 1, 0, 6, 12, 0.25f))
        s.setMotionValue(2, 7, 3, 0, 1f)
        s.setMotionValue(2, 7, 3, 0, Float.NaN) // a lane left empty
        return s
    }

    @Test
    fun snapshotHoldsOnlyNonEmptyPatterns() {
        val snap = edited().snapshot()
        assertEquals(setOf(0 to 0, 1 to 0, 3 to 5), snap.map { it.slot to it.pattern }.toSet())
        assertTrue(snap.first { it.slot == 1 }.lanes.map { it.target } == listOf(8, 9, 12))
    }

    @Test
    fun restoreRebuildsTheMirrorAndTellsAFreshEngineExactlyThat() {
        val a = edited()
        val sink = RecordingSink()
        val b = PatternStore(sink)
        b.restore(a.snapshot())
        assertEquals(a.snapshot().map { it.copy(lanes = it.lanes.map { l -> l.copy(points = l.points.map { p -> p.copy(stamp = 0) }) }) },
            b.snapshot().map { it.copy(lanes = it.lanes.map { l -> l.copy(points = l.points.map { p -> p.copy(stamp = 0) }) }) })
        for (slot in 0 until SeqLimits.SLOTS) for (p in 0 until SeqLimits.PATTERNS) {
            assertEquals(a.settings(slot, p), b.settings(slot, p))
            for (i in 0 until SeqLimits.STEPS) assertEquals(a.step(slot, p, i), b.step(slot, p, i))
            for (i in 0 until SeqLimits.STEPS) assertEquals(a.latestLaneAt(slot, p, i)?.target, b.latestLaneAt(slot, p, i)?.target)
        }
        // A fresh engine needs no clears; it gets settings, steps and motion values only.
        assertTrue(sink.calls.none { it.startsWith("clear") })
        assertTrue("settings 3 5 48 3 0.3 0.7" in sink.calls)
        assertTrue("step 3 5 40 true [60, 64, 67] 0.4 0.2 0.6" in sink.calls)
        assertTrue("motion 1 0 8 2 1200.0" in sink.calls)
        assertTrue("motion 1 0 12 6 0.25" in sink.calls)
        assertEquals(DemoContent.BEAT.size + DemoContent.BASS.size + 1, sink.calls.count { it.startsWith("step") })
    }

    @Test
    fun restoreKeepsStampOrderSoNewEditsAreLatest() {
        val b = PatternStore(RecordingSink())
        b.restore(edited().snapshot())
        assertEquals(9, b.latestLaneAt(1, 0, 2)?.target)
        b.setMotionValue(1, 0, 8, 2, 100f)
        assertEquals(8, b.latestLaneAt(1, 0, 2)?.target)
    }

    @Test
    fun restoreClearsWhatTheSaveDoesNotHave() {
        val sink = RecordingSink()
        val s = PatternStore(sink)
        DemoContent.write(s)
        sink.calls.clear()
        s.restore(emptyList())
        assertEquals(listOf("clear 0 0", "clear 1 0"), sink.calls)
        assertEquals(emptyList<PatternSnapshot>(), s.snapshot())
    }

    @Test
    fun restoreClampsAndIgnoresBadData() {
        val sink = RecordingSink()
        val s = PatternStore(sink)
        s.restore(
            listOf(
                PatternSnapshot(9, 0, PatternSettings(), mapOf(0 to Step(true)), emptyList()), // no such slot
                PatternSnapshot(
                    1, 2, PatternSettings(length = 500, rate = 9, shuffle = Float.NaN, gate = 0f),
                    mapOf(-1 to Step(true), 64 to Step(true), 3 to Step(true, listOf(200, 1, 2, 3, 4), 5f, 9f, -1f)),
                    List(10) { t -> LaneSnapshot(t, listOf(LanePoint(0, t.toFloat(), 1), LanePoint(70, 1f, 2))) } +
                        LaneSnapshot(99, listOf(LanePoint(1, Float.NaN, 3))),
                ),
                PatternSnapshot(1, 2, PatternSettings(length = 3), emptyMap(), emptyList()), // a duplicate
            ),
        )
        assertEquals(PatternSettings(64, 3, 0f, SeqLimits.MIN_GATE), s.settings(1, 2))
        assertEquals(Step(true, listOf(127, 1, 2, 3), 1f, 1f, 0f), s.step(1, 2, 3))
        assertEquals(8, s.lanes(1, 2).size)
        assertEquals(1, s.snapshot().size)
    }
}
