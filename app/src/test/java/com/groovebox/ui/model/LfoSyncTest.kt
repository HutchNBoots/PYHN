package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

/** Mirrors engine/tests/LfoTest.cpp's tempo-sync cases. */
class LfoSyncTest {
    @Test
    fun theEnginesExamplesAt120Bpm() {
        assertEquals(2.0f, LfoSync.divisionHz(LfoSync.nearestDivision(2.0f, 120f), 120f), 0f)
        assertEquals("1/4", LfoSync.label(2.0, 120.0))
        assertEquals("1/16", LfoSync.label(8.0, 120.0))
        assertEquals("4 bars", LfoSync.label(0.05, 120.0))
        assertEquals("1/32T", LfoSync.label(30.0, 120.0))
    }

    @Test
    fun tableMatchesTheEngine() {
        assertEquals(13, LfoSync.DIVISION_BEATS.size)
        assertEquals(13, LfoSync.DIVISION_NAMES.size)
        assertEquals("1 bar", LfoSync.DIVISION_NAMES[2])
        assertEquals(4f, LfoSync.DIVISION_BEATS[2], 0f)
        assertEquals(1f / 12f, LfoSync.DIVISION_BEATS[12], 0f)
    }

    @Test
    fun theDivisionFollowsTheTempoLikeTheEngine() {
        // SynthEngine snaps at the sequencer's tempo: at 60 BPM, 2 Hz is an eighth note.
        assertEquals("1/8", LfoSync.label(2.0, 60.0))
        assertEquals("1/2", LfoSync.label(2.0, 240.0))
    }

    @Test
    fun alwaysTheNearestInOctaves() {
        // Same property as LfoTest: within half the largest gap between neighbours.
        var r = 0.05f
        while (r <= 30f) {
            val hz = LfoSync.divisionHz(LfoSync.nearestDivision(r, 120f), 120f)
            assertTrue("rate $r -> $hz", abs(log2(hz) - log2(r)) <= 0.5f + 1e-4f || r < 0.125f || r > 24f)
            r *= 1.07f
        }
    }

    @Test
    fun aTieGoesToTheLongerDivision() {
        // Halfway in octaves between 1/4 (2 Hz) and 1/4T (3 Hz) at 120 BPM: sqrt(6) Hz.
        val mid = kotlin.math.sqrt(6.0f) - 1e-6f
        assertEquals("1/4", LfoSync.DIVISION_NAMES[LfoSync.nearestDivision(mid, 120f)])
    }

    @Test
    fun oddTempoInputsAreSafe() {
        assertEquals(LfoSync.label(2.0, 120.0), LfoSync.label(2.0, Double.NaN))
        LfoSync.label(0.0, 0.0)
        LfoSync.label(-5.0, 5000.0)
    }
}
