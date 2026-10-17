package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotCopyGestureTest {
    private val g = SlotCopyGesture(isSynth = { it != 0 })

    @Test
    fun holdOneSlotAndTapAnotherCopiesIt() {
        assertNull(g.down(1L, 2))
        assertEquals(SlotCopyGesture.Copy(from = 2, to = 5), g.down(2L, 5))
        g.up(2L)
        // Still holding 2: each further tap copies again.
        assertEquals(SlotCopyGesture.Copy(2, 6), g.down(3L, 6))
        g.up(3L); g.up(1L)
        assertNull(g.down(4L, 6))
    }

    @Test
    fun theKitSlotNeitherGivesNorTakes() {
        assertNull(g.down(1L, 0)); assertNull(g.down(2L, 3)); g.up(1L); g.up(2L)
        assertNull(g.down(3L, 3)); assertNull(g.down(4L, 0))
    }

    @Test
    fun aSecondFingerOnTheSameSlotIsNoCopy() {
        g.down(1L, 4)
        assertNull(g.down(2L, 4))
    }

    @Test
    fun cancelForgetsHeldSlots() {
        g.down(1L, 4)
        g.cancel()
        assertNull(g.down(2L, 5))
    }
}
