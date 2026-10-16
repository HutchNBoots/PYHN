package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepPadsTest {
    private val pads = StepPads()

    @Test
    fun aTapTogglesOnRelease() {
        assertNull(pads.down(0, 5, shifted = false))
        assertEquals(PadAction.Toggle(5), pads.up(0))
    }

    @Test
    fun separateTapsToggleEachStep() {
        pads.down(0, 1, false)
        assertEquals(PadAction.Toggle(1), pads.up(0))
        pads.down(1, 2, false)
        assertEquals(PadAction.Toggle(2), pads.up(1))
    }

    @Test
    fun holdingTwoPadsLoopsTheRangeInEitherOrder() {
        pads.down(0, 12, false)
        assertEquals(PadAction.Loop(4, 12), pads.down(1, 4, false))
        assertEquals(4 to 12, pads.loop)
    }

    @Test
    fun releasingEitherLoopPadClearsTheLoopAndNothingToggles() {
        pads.down(0, 4, false)
        pads.down(1, 7, false)
        assertEquals(PadAction.ClearLoop, pads.up(1))
        assertNull(pads.loop)
        assertNull("the other loop pad does not toggle", pads.up(0))
        // The next tap is an ordinary tap again.
        pads.down(2, 9, false)
        assertEquals(PadAction.Toggle(9), pads.up(2))
    }

    @Test
    fun releasingTheFirstLoopPadAlsoClears() {
        pads.down(0, 4, false)
        pads.down(1, 7, false)
        assertEquals(PadAction.ClearLoop, pads.up(0))
        assertNull(pads.up(1))
    }

    @Test
    fun aThirdPadDuringALoopDoesNothing() {
        pads.down(0, 0, false)
        pads.down(1, 3, false)
        assertNull(pads.down(2, 9, false))
        assertEquals(0 to 3, pads.loop)
        assertNull(pads.up(2))
        assertEquals(PadAction.ClearLoop, pads.up(1))
        assertNull(pads.up(0))
    }

    @Test
    fun aPadStillHeldAfterTheLoopEndsCannotStartANewLoopUntilAllAreUp() {
        pads.down(0, 0, false)
        pads.down(1, 3, false)
        pads.up(1) // loop cleared, pad 0 still down
        assertNull(pads.down(2, 8, false))
        assertNull(pads.up(2))
        assertNull(pads.up(0))
        pads.down(3, 1, false)
        assertEquals(PadAction.Loop(1, 2), pads.down(4, 2, false))
    }

    @Test
    fun theSameStepTwiceLoopsOneStep() {
        pads.down(0, 6, false)
        assertEquals(PadAction.Loop(6, 6), pads.down(1, 6, false))
    }

    @Test
    fun shiftSelectsOnTouchDownAndAgainDeselects() {
        assertEquals(PadAction.Select(5), pads.down(0, 5, shifted = true))
        assertEquals(5, pads.selected)
        assertNull("no toggle on release", pads.up(0))
        assertEquals(PadAction.Select(9), pads.down(1, 9, true))
        assertEquals(9, pads.selected)
        assertEquals(PadAction.Select(null), pads.down(2, 9, true))
        assertNull(pads.selected)
    }

    @Test
    fun shiftPadsNeverLoop() {
        pads.down(0, 1, true)
        assertEquals(PadAction.Select(4), pads.down(1, 4, true))
        assertNull(pads.loop)
    }

    @Test
    fun clearSelection() {
        pads.down(0, 3, true)
        pads.clearSelection()
        assertNull(pads.selected)
    }

    @Test
    fun cancelForgetsHeldPadsAndClearsALoop() {
        pads.down(0, 1, false)
        pads.down(1, 2, false)
        assertEquals(PadAction.ClearLoop, pads.cancel())
        assertNull(pads.up(0))
        pads.down(2, 5, false)
        assertNull("no loop to clear", pads.cancel())
        assertNull("cancelled pads do not toggle", pads.up(2))
    }

    @Test
    fun releasingAnUnknownPointerDoesNothing() {
        assertNull(pads.up(42))
    }
}
