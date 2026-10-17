package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LfoTraceTest {
    @Test
    fun aFasterRateDrawsMoreCyclesUpToACap() {
        assertEquals(2.0, LfoTrace.cycles(2.0, synced = false, bpm = 120.0), 1e-9)
        assertEquals(LfoTrace.MAX_CYCLES, LfoTrace.cycles(30.0, false, 120.0), 1e-9)
        // Synced 1/8 at 120: eight per bar.
        assertEquals(8.0, LfoTrace.cycles(4.0, synced = true, bpm = 120.0), 1e-9)
    }

    @Test
    fun depthScalesAndFadeRampsIn() {
        val deep = LfoTrace.points(2, 2.0, 1.0, 0.0, 1.0)
        val shallow = LfoTrace.points(2, 2.0, 0.0, 0.0, 1.0)
        assertEquals(1.0, deep.maxOf { abs(it.y) }, 1e-9)
        assertTrue(shallow.maxOf { abs(it.y) } in 0.05..0.2)
        val faded = LfoTrace.points(2, 4.0, 1.0, fadeS = 0.5, windowS = 1.0)
        assertTrue(abs(faded[4].y) < 0.1)
        assertEquals(1.0, abs(faded.last().y), 1e-9)
    }

    @Test
    fun everyShapeStaysInRange() {
        for (shape in 0..3) assertTrue(LfoTrace.points(shape, 5.0, 1.0, 0.0, 1.0).all { it.y in -1.0..1.0 })
    }
}
