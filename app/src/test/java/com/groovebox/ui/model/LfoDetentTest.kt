package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LfoDetentTest {
    private val rate = ParamSpec(20, "lfo.rate", "Rate", 0.05, 30.0, 2.0, Curve.Exp, "Hz")
    private val bpm = 120.0

    @Test
    fun belowTheDetentTheStripSnapsToBeatDivisionsAndSyncs() {
        val r = LfoDetent.fromPosition(0.0, rate, bpm)
        assertTrue(r.synced)
        assertEquals(0, r.division) // 4 bars, the slowest, at the bottom
        assertEquals(LfoSync.divisionHz(0, 120f).toDouble(), r.hz, 1e-9)
        val top = LfoDetent.fromPosition(LfoDetent.SYNC_TOP - 1e-9, rate, bpm)
        assertTrue(top.synced)
        assertEquals(LfoSync.DIVISION_BEATS.size - 1, top.division) // 1/32T
    }

    @Test
    fun theRateHzPicksTheDivisionTheEnginePlays() {
        // The engine plays the division nearest the Rate value at the tempo (LfoSync), so the
        // Hz sent for a division must come back as that division at any tempo.
        for (tempo in listOf(60.0, 97.5, 120.0, 174.0)) for (d in LfoDetent.divisions(rate, tempo)) {
            val n = LfoDetent.position(true, LfoSync.divisionHz(d, tempo.toFloat()).toDouble(), rate, tempo)
            val r = LfoDetent.fromPosition(n, rate, tempo)
            assertEquals("division $d at $tempo", d, r.division)
            assertEquals(d, LfoSync.nearestDivision(r.hz.toFloat(), tempo.toFloat()))
        }
    }

    @Test
    fun theDetentHoldsBeforeTheFreeRangeStarts() {
        // Through the detent the value does not move: the top division, then the free minimum.
        val a = LfoDetent.fromPosition(LfoDetent.SYNC_TOP + 0.005, rate, bpm)
        assertTrue(a.synced); assertEquals(12, a.division)
        val b = LfoDetent.fromPosition(LfoDetent.FREE_BOTTOM - 0.001, rate, bpm)
        assertFalse(b.synced); assertEquals(rate.min, b.hz, 1e-9)
        val c = LfoDetent.fromPosition(LfoDetent.FREE_BOTTOM, rate, bpm)
        assertEquals(rate.min, c.hz, 1e-9)
    }

    @Test
    fun aboveTheDetentRateIsFreeHzOnTheSpecsCurve() {
        val top = LfoDetent.fromPosition(1.0, rate, bpm)
        assertFalse(top.synced)
        assertEquals(30.0, top.hz, 1e-9)
        val n = LfoDetent.position(false, 2.0, rate, bpm)
        assertTrue(n > LfoDetent.FREE_BOTTOM)
        assertEquals(2.0, LfoDetent.fromPosition(n, rate, bpm).hz, 1e-9)
    }

    @Test
    fun positionsRoundTrip() {
        for (i in 0..200) {
            val n = i / 200.0
            val r = LfoDetent.fromPosition(n, rate, bpm)
            val back = LfoDetent.fromPosition(LfoDetent.position(r.synced, r.hz, rate, bpm), rate, bpm)
            assertEquals(r.synced, back.synced)
            if (r.synced) assertEquals(r.division, back.division) else assertEquals(r.hz, back.hz, 1e-9 * r.hz)
        }
    }

    @Test
    fun labelsSayWhichSide() {
        assertEquals("1/8", LfoDetent.label(LfoDetent.position(true, 4.0, rate, bpm), rate, bpm))
        assertEquals("2.0 Hz", LfoDetent.label(LfoDetent.position(false, 2.0, rate, bpm), rate, bpm))
    }

    // --- review 5 and 6 ---

    @Test
    fun onlyDivisionsInsideTheRateRangeAreOffered() {
        // At 190 BPM 1/32T is 38 Hz, over Rate's 30 Hz top: the strip's sync zone stops at 1/32.
        val fast = LfoDetent.divisions(rate, 190.0)
        assertEquals(LfoSync.DIVISION_BEATS.size - 1, fast.size)
        assertEquals(11, fast.last())
        val top = LfoDetent.fromPosition(LfoDetent.SYNC_TOP - 1e-9, rate, 190.0)
        assertEquals(11, top.division)
        assertTrue(top.hz <= rate.max)
        // At 40 BPM 4 bars is 0.042 Hz, under 0.05: it starts at 2 bars.
        assertEquals(1, LfoDetent.divisions(rate, 40.0).first())
        for (tempo in listOf(30.0, 40.0, 120.0, 190.0, 300.0)) for (d in LfoDetent.divisions(rate, tempo)) {
            val r = LfoDetent.fromPosition(LfoDetent.position(true, LfoSync.divisionHz(d, tempo.toFloat()).toDouble(), rate, tempo), rate, tempo)
            assertEquals("$d at $tempo", d, r.division)
            assertTrue(r.hz in rate.min..rate.max)
        }
    }

    @Test
    fun aTempoChangeKeepsTheChosenDivision() {
        // 1/4 at 120 is 2 Hz; at 90 BPM the engine would hear 2 Hz as 1/4T without the retime.
        val hz120 = LfoSync.divisionHz(5, 120f).toDouble()
        assertEquals(6, LfoSync.nearestDivision(hz120.toFloat(), 90f))
        val hz90 = LfoDetent.retime(hz120, rate, oldBpm = 120.0, newBpm = 90.0)
        assertEquals(LfoSync.divisionHz(5, 90f).toDouble(), hz90, 1e-9)
        assertEquals(5, LfoSync.nearestDivision(hz90.toFloat(), 90f))
    }

    @Test
    fun aRetimePastTheRangeTakesTheNearestDivisionThatFits() {
        val hz = LfoSync.divisionHz(12, 120f).toDouble() // 1/32T = 24 Hz
        val at200 = LfoDetent.retime(hz, rate, 120.0, 200.0)
        assertTrue(at200 <= rate.max)
        assertEquals(11, LfoSync.nearestDivision(at200.toFloat(), 200f))
    }
}
