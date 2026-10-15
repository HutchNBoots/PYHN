package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DisplayShapesTest {
    @Test
    fun pureShapesAtTheirIndexes() {
        assertEquals(-0.5, DisplayShapes.wave(0.0, 0.5, 0.25), 1e-9) // saw rises from -1
        assertEquals(1.0, DisplayShapes.wave(1.0, 0.5, 0.25), 1e-9) // square high before pw
        assertEquals(-1.0, DisplayShapes.wave(1.0, 0.5, 0.75), 1e-9)
        assertEquals(1.0, DisplayShapes.wave(2.0, 0.5, 0.5), 1e-9) // triangle peak
        assertEquals(sin(2 * PI * 0.1), DisplayShapes.wave(3.0, 0.5, 0.1), 1e-9)
    }

    @Test
    fun morphIsALinearBlendOfNeighbours() {
        val saw = DisplayShapes.wave(0.0, 0.5, 0.25)
        val sq = DisplayShapes.wave(1.0, 0.5, 0.25)
        assertEquals((saw + sq) / 2, DisplayShapes.wave(0.5, 0.5, 0.25), 1e-9)
    }

    @Test
    fun pulseWidthMovesTheSquareEdge() {
        assertEquals(-1.0, DisplayShapes.wave(1.0, 0.2, 0.3), 1e-9)
        assertEquals(1.0, DisplayShapes.wave(1.0, 0.8, 0.3), 1e-9)
    }

    @Test
    fun filterPassesLowsAndCutsHighs() {
        val pass = DisplayShapes.filterDb(50.0, cutoffHz = 2000.0, reso = 0.0, hpfHz = 20.0)
        val stop = DisplayShapes.filterDb(16000.0, cutoffHz = 2000.0, reso = 0.0, hpfHz = 20.0)
        assertTrue("passband $pass", pass > -1.0 && pass < 0.5)
        assertTrue("stopband $stop", stop < -60.0)
    }

    @Test
    fun resonancePeaksAtCutoffAndIsClamped() {
        val flat = DisplayShapes.filterDb(1000.0, 1000.0, 0.0, 20.0)
        val peak = DisplayShapes.filterDb(1000.0, 1000.0, 0.8, 20.0)
        val max = DisplayShapes.filterDb(1000.0, 1000.0, 1.0, 20.0)
        assertTrue(peak > flat + 6.0)
        assertTrue(max.isFinite() && max <= DisplayShapes.MAX_DB)
    }

    @Test
    fun highPassCutsLows() {
        assertTrue(DisplayShapes.filterDb(30.0, 18000.0, 0.0, hpfHz = 1000.0) < -20.0)
    }

    @Test
    fun envelopeShapeStartsAndEndsAtZero() {
        val pts = DisplayShapes.envelope(attack = 0.01, decay = 0.3, sustain = 0.6, release = 1.0)
        assertEquals(0.0, pts.first().x, 1e-9)
        assertEquals(0.0, pts.first().y, 1e-9)
        assertEquals(1.0, pts.last().x, 1e-9)
        assertEquals(0.0, pts.last().y, 0.01)
        assertTrue(pts.zipWithNext().all { (a, b) -> b.x >= a.x })
        assertTrue(pts.all { it.y in 0.0..1.0 })
        assertEquals(1.0, pts.maxOf { it.y }, 1e-9)
        assertTrue(pts.any { kotlin.math.abs(it.y - 0.6) < 1e-6 })
    }

    @Test
    fun longerTimesTakeMoreWidth() {
        val short = DisplayShapes.envelope(0.001, 0.3, 0.5, 0.3)
        val long = DisplayShapes.envelope(5.0, 0.3, 0.5, 0.3)
        fun peakX(p: List<DisplayShapes.Point>) = p.first { it.y >= 1.0 - 1e-9 }.x
        assertTrue(peakX(long) > peakX(short) + 0.1)
    }
}
