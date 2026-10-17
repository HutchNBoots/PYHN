package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCopyGestureTest {
    private val g = SlotCopyGesture(isSynth = { it != 0 })

    private val hold = SlotCopyGesture.MIN_HOLD_MS

    @Test
    fun holdOneSlotAndTapAnotherCopiesIt() {
        assertNull(g.down(1L, 2, 0))
        assertEquals(SlotCopyGesture.Copy(from = 2, to = 5), g.down(2L, 5, hold))
        g.up(2L)
        // Still holding 2: each further tap copies again.
        assertEquals(SlotCopyGesture.Copy(2, 6), g.down(3L, 6, hold + 200))
        g.up(3L); g.up(1L)
        assertNull(g.down(4L, 6, hold + 400))
    }

    @Test
    fun aFingerCountsAsHeldOnlyAfterTheMinimumHold() {
        // Two near-simultaneous taps (a two-finger brush over the slots) copy nothing.
        assertNull(g.down(1L, 2, 1000))
        assertNull(g.down(2L, 5, 1000 + hold - 1))
        g.up(2L)
        assertEquals(SlotCopyGesture.Copy(2, 5), g.down(3L, 5, 1000 + hold))
    }

    @Test
    fun theKitSlotNeitherGivesNorTakes() {
        assertNull(g.down(1L, 0, 0)); assertNull(g.down(2L, 3, hold)); g.up(1L); g.up(2L)
        assertNull(g.down(3L, 3, 1000)); assertNull(g.down(4L, 0, 1000 + hold))
    }

    @Test
    fun aSecondFingerOnTheSameSlotIsNoCopy() {
        g.down(1L, 4, 0)
        assertNull(g.down(2L, 4, hold))
    }

    @Test
    fun cancelForgetsHeldSlots() {
        g.down(1L, 4, 0)
        g.cancel()
        assertNull(g.down(2L, 5, hold))
    }

    @Test
    fun noSlotOpensItsPresetsWhileAnotherSlotFingerIsDown() {
        g.down(1L, 3, 0)
        assertFalse("alone, its long-press opens the presets", g.suppressLongPress(3))
        g.down(2L, 6, 100) // too soon to copy, but two slot fingers are down
        assertTrue(g.suppressLongPress(3))
        assertTrue(g.suppressLongPress(6))
        g.up(2L)
        assertFalse(g.suppressLongPress(3))
    }

    @Test
    fun aSlotThatGaveACopyDoesNotOpenItsPresetsWhileStillHeld() {
        g.down(1L, 3, 0)
        g.down(2L, 6, hold)
        g.up(2L)
        assertTrue("its long-press must not open the preset strip", g.suppressLongPress(3))
        g.up(1L)
        assertFalse(g.suppressLongPress(3))
    }
}
