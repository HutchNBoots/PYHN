package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHoldTest {
    private val u = UndoHold(longPressMs = 500, flashMs = 700)

    @Test
    fun aTapUndoes() {
        u.down(1_000)
        assertNull(u.poll(1_200))
        assertEquals(UndoHold.Action.Undo, u.up(1_200))
        assertFalse(u.readsRedo(1_200))
    }

    @Test
    fun aLongPressRedoesOnceAndTheReleaseDoesNothing() {
        u.down(1_000)
        assertNull(u.poll(1_499))
        assertEquals(UndoHold.Action.Redo, u.poll(1_500))
        assertNull(u.poll(1_800)) // only once per press
        assertNull(u.up(2_000))
    }

    @Test
    fun theChipBrieflyReadsRedo() {
        u.down(1_000)
        assertFalse(u.readsRedo(1_400))
        u.poll(1_500)
        assertTrue(u.readsRedo(1_500))
        u.up(1_600)
        assertTrue(u.readsRedo(2_200))
        assertFalse(u.readsRedo(2_201))
    }

    @Test
    fun aCancelledTouchDoesNothing() {
        u.down(1_000)
        u.cancel()
        assertNull(u.poll(2_000))
        assertNull(u.up(2_000))
    }

    @Test
    fun anUpWithoutADownDoesNothing() {
        assertNull(u.up(1_000))
        assertNull(u.poll(5_000))
    }

    @Test
    fun theNextPressStartsFresh() {
        u.down(0); u.poll(600); u.up(700)
        u.down(2_000)
        assertEquals(UndoHold.Action.Undo, u.up(2_100))
    }
}
