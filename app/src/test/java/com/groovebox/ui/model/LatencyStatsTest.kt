package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyStatsTest {
    @Test
    fun emptyHasNoSummary() {
        assertNull(LatencyStats().summary())
    }

    @Test
    fun summarisesInMilliseconds() {
        val stats = LatencyStats()
        listOf(30, 10, 20, 50, 40).forEach { stats.add(it * 1_000_000L) }
        val s = stats.summary()!!
        assertEquals(5, s.count)
        assertEquals(40.0, s.lastMs, 1e-9)
        assertEquals(10.0, s.minMs, 1e-9)
        assertEquals(30.0, s.medianMs, 1e-9)
        assertEquals(50.0, s.maxMs, 1e-9)
    }

    @Test
    fun medianOfEvenCountIsMeanOfMiddlePair() {
        val stats = LatencyStats()
        listOf(10, 20, 30, 40).forEach { stats.add(it * 1_000_000L) }
        assertEquals(25.0, stats.summary()!!.medianMs, 1e-9)
    }

    @Test
    fun keepsOnlyTheMostRecentWindow() {
        val stats = LatencyStats(window = 3)
        listOf(100, 1, 2, 3).forEach { stats.add(it * 1_000_000L) }
        val s = stats.summary()!!
        assertEquals(3, s.count)
        assertEquals(3.0, s.maxMs, 1e-9)
        assertEquals(4, s.total)
    }

    @Test
    fun resetClearsEverything() {
        val stats = LatencyStats()
        stats.add(1_000_000L)
        stats.reset()
        assertNull(stats.summary())
    }
}
