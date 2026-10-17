package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {
    private val t = TapTempo()

    private fun taps(vararg at: Long, current: Double = 120.0): Double? {
        var out: Double? = null
        for (ms in at) out = t.tap(ms, current)
        return out
    }

    @Test
    fun twoTapsSetNothing() {
        assertNull(t.tap(1_000, 120.0))
        assertNull(t.tap(1_500, 120.0))
        assertEquals(2, t.count)
    }

    @Test
    fun threeTapsSetTheTempoFromTheAverageInterval() {
        // 600 ms apart: 100 BPM.
        assertEquals(100.0, taps(1_000, 1_600, 2_200)!!, 1e-9)
    }

    @Test
    fun moreTapsAverageAllIntervals() {
        // 500, 520, 480, 500 ms: 2000 ms over 4 intervals = 500 ms = 120 BPM.
        assertEquals(120.0, taps(0, 500, 1_020, 1_500, 2_000, current = 90.0)!!, 1e-9)
    }

    @Test
    fun theResultIsRoundedToATenth() {
        // 470 ms: 127.659… BPM.
        assertEquals(127.7, taps(0, 470, 940, current = 90.0)!!, 1e-9)
    }

    @Test
    fun aLongGapStartsAgain() {
        taps(0, 600)
        assertNull(t.tap(600 + TapTempo.TIMEOUT_MS + 1, 120.0))
        assertEquals(1, t.count)
        assertNull(t.tap(600 + TapTempo.TIMEOUT_MS + 501, 120.0))
        assertEquals(120.0, t.tap(600 + TapTempo.TIMEOUT_MS + 1_001, 90.0)!!, 1e-9)
    }

    @Test
    fun aBounceTooSoonAfterATapIsIgnored() {
        taps(0, 600)
        assertNull(t.tap(650, 120.0))
        assertEquals(2, t.count)
        assertEquals(100.0, t.tap(1_200, 120.0)!!, 1e-9)
    }

    @Test
    fun nearDoubleOrHalfTheCurrentTempoSnapsToIt() {
        assertEquals(240.0, TapTempo.snap(236.0, 120.0), 1e-9)
        assertEquals(60.0, TapTempo.snap(61.5, 120.0), 1e-9)
        // Not near either: kept as tapped.
        assertEquals(100.0, TapTempo.snap(100.0, 120.0), 1e-9)
        // Near the current tempo itself: kept as tapped (a re-tap fine-tunes).
        assertEquals(118.4, TapTempo.snap(118.4, 120.0), 1e-9)
    }

    @Test
    fun outOfRangeTapsFoldInByOctaves() {
        // 30 BPM taps (too slow) double to 60; 300 halves to 150.
        assertEquals(60.0, TapTempo.snap(30.0, 100.0), 1e-9)
        assertEquals(150.0, TapTempo.snap(300.0, 100.0), 1e-9)
    }

    @Test
    fun snapNeverLeavesTheRange() {
        // Double of 130 is 260, past the maximum: not a snap target.
        assertEquals(255.0 / 2, TapTempo.snap(255.0, 130.0), 1e-9)
        assertEquals(SeqLimits.MAX_TEMPO, TapTempo.snap(SeqLimits.MAX_TEMPO, 200.0), 1e-9)
    }

    @Test
    fun resetForgetsTheTaps() {
        taps(0, 600)
        t.reset()
        assertEquals(0, t.count)
        assertNull(t.tap(1_200, 120.0))
    }

    @Test
    fun theLongPressTempoIs120() {
        assertEquals(120.0, TapTempo.LONG_PRESS_BPM, 0.0)
    }
}
