package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerTextTest {
    @Test
    fun oneFormLayerDotFocus() {
        assertEquals("MAIN · osc", LayerText.of(LayerText.MAIN, "osc"))
        assertEquals("STEP · step 5", LayerText.of(LayerText.STEP, "step 5"))
        assertEquals("SHIFT · T2", LayerText.of(LayerText.SHIFT, "T2"))
    }

    @Test
    fun mainOrShiftByTheShiftState() {
        assertEquals("MAIN · channel 2", LayerText.mainOrShift(false, "channel 2"))
        assertEquals("SHIFT · channel 2", LayerText.mainOrShift(true, "channel 2"))
    }

    @Test
    fun noFocusIsJustTheLayer() {
        assertEquals("MAIN", LayerText.of(LayerText.MAIN, ""))
    }
}
