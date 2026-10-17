package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripFillTest {
    private val h = 240f
    private val header = 52f
    private val edge = 3f

    @Test
    fun fullValueStopsBelowTheLabelBand() {
        assertEquals(header, StripFill.edgeTop(1.0, h, header, edge), 1e-4f)
    }

    @Test
    fun zeroValueKeepsTheEdgeAtTheBottom() {
        assertEquals(h - edge, StripFill.edgeTop(0.0, h, header, edge), 1e-4f)
    }

    @Test
    fun ninetyFivePercentIsStillBelowTheBand() {
        val top = StripFill.edgeTop(0.95, h, header, edge)
        assertTrue(top > header)
        assertTrue(top < StripFill.edgeTop(0.9, h, header, edge))
    }

    @Test
    fun valuesOutsideTheRangeClamp() {
        assertEquals(header, StripFill.edgeTop(1.5, h, header, edge), 1e-4f)
        assertEquals(h - edge, StripFill.edgeTop(-1.0, h, header, edge), 1e-4f)
    }

    @Test
    fun aStripShorterThanTheBandFillsItsWholeHeight() {
        assertEquals(0f, StripFill.edgeTop(1.0, 40f, header, edge), 1e-4f)
        assertEquals(40f - edge, StripFill.edgeTop(0.0, 40f, header, edge), 1e-4f)
    }
}
