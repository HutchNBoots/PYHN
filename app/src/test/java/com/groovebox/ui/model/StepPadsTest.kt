package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-20 pad grammar: tap toggles, long-press selects (latched), Shift + two pads loops. */
class StepPadsTest {
    private val pads = StepPads()

    private fun sel(vararg steps: Int) = PadAction.Selection(steps.toList())

    // --- taps ---

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
    fun twoPadsTappedTogetherWithoutShiftBothToggle() {
        // Two thumbs drumming at once: no loop without Shift.
        assertNull(pads.down(0, 4, false))
        assertNull(pads.down(1, 7, false))
        assertNull(pads.loop)
        assertEquals(PadAction.Toggle(7), pads.up(1))
        assertEquals(PadAction.Toggle(4), pads.up(0))
    }

    // --- the pitch roll (build-21): a tap on a note bar toggles its step like a long-press ---

    @Test
    fun aRollTapTogglesTheStepInTheSelection() {
        assertEquals(sel(3), pads.toggleSelection(3))
        assertEquals(sel(3, 9), pads.toggleSelection(9))
        assertEquals(sel(9), pads.toggleSelection(3))
        assertEquals(listOf(9), pads.selected)
    }

    // --- long-press selection ---

    @Test
    fun aLongPressSelectsAndTheReleaseDoesNotToggle() {
        pads.down(0, 3, false)
        assertEquals(sel(3), pads.longPress(0))
        assertNull("latched: letting go keeps it selected, no toggle", pads.up(0))
        assertEquals(listOf(3), pads.selected)
    }

    @Test
    fun aTapAfterASelectionStillToggles() {
        pads.down(0, 3, false)
        pads.longPress(0)
        pads.up(0)
        pads.down(1, 9, false)
        assertEquals(PadAction.Toggle(9), pads.up(1))
        assertEquals("the selection stays", listOf(3), pads.selected)
    }

    @Test
    fun padsTouchedWhileASelectingPadIsHeldJoinTheSelectionAtOnce() {
        pads.down(0, 2, false)
        pads.longPress(0)
        assertEquals(sel(2, 5), pads.down(1, 5, false))
        assertNull("no toggle for the added pad", pads.up(1))
        assertEquals(sel(2, 5, 11), pads.down(2, 11, false))
        assertNull(pads.longPress(2))
        assertNull(pads.up(2))
        assertNull(pads.up(0))
        assertEquals(listOf(2, 5, 11), pads.selected)
    }

    @Test
    fun aSecondLongPressAddsToTheLatchedSelection() {
        pads.down(0, 2, false); pads.longPress(0); pads.up(0)
        pads.down(1, 6, false)
        assertEquals(sel(2, 6), pads.longPress(1))
        pads.up(1)
        assertEquals(listOf(2, 6), pads.selected)
    }

    @Test
    fun longPressingASelectedStepDeselectsIt() {
        pads.down(0, 2, false); pads.longPress(0); pads.up(0)
        pads.down(1, 6, false); pads.longPress(1); pads.up(1)
        pads.down(2, 2, false)
        assertEquals(sel(6), pads.longPress(2))
        assertNull(pads.up(2))
        assertEquals(listOf(6), pads.selected)
    }

    @Test
    fun aDeselectingHoldDoesNotAddOtherPads() {
        pads.down(0, 2, false); pads.longPress(0); pads.up(0)
        pads.down(1, 2, false); pads.longPress(1) // deselects 2
        assertNull("not selecting: an ordinary pad", pads.down(2, 8, false))
        assertEquals(PadAction.Toggle(8), pads.up(2))
        assertTrue(pads.selected.isEmpty())
    }

    @Test
    fun aLongPressAfterReleaseOrForAnUnknownPointerDoesNothing() {
        pads.down(0, 4, false)
        pads.up(0)
        assertNull(pads.longPress(0))
        assertNull(pads.longPress(42))
        assertTrue(pads.selected.isEmpty())
    }

    @Test
    fun clearSelection() {
        pads.down(0, 3, false); pads.longPress(0); pads.up(0)
        pads.clearSelection()
        assertTrue(pads.selected.isEmpty())
    }

    // --- Shift ---

    @Test
    fun shiftAndTwoPadsLoopTheRangeInEitherOrder() {
        assertNull(pads.down(0, 12, shifted = true))
        assertEquals(PadAction.Loop(4, 12), pads.down(1, 4, true))
        assertEquals(4 to 12, pads.loop)
    }

    @Test
    fun releasingEitherLoopPadClearsTheLoopAndNothingElseHappens() {
        pads.down(0, 4, true)
        pads.down(1, 7, true)
        assertEquals(PadAction.ClearLoop, pads.up(1))
        assertNull(pads.loop)
        assertNull("the other loop pad does nothing", pads.up(0))
        assertTrue("a loop never selects", pads.selected.isEmpty())
        pads.down(2, 9, false)
        assertEquals(PadAction.Toggle(9), pads.up(2))
    }

    @Test
    fun aThirdShiftPadDuringALoopDoesNothing() {
        pads.down(0, 0, true)
        pads.down(1, 3, true)
        assertNull(pads.down(2, 9, true))
        assertEquals(0 to 3, pads.loop)
        assertNull(pads.up(2))
        assertEquals(PadAction.ClearLoop, pads.up(1))
        assertNull(pads.up(0))
    }

    @Test
    fun aShiftPadStillHeldAfterTheLoopEndsCannotStartANewLoopUntilAllAreUp() {
        pads.down(0, 0, true)
        pads.down(1, 3, true)
        pads.up(1)
        assertNull(pads.down(2, 8, true))
        assertNull(pads.up(2))
        assertNull(pads.up(0))
        pads.down(3, 1, true)
        assertEquals(PadAction.Loop(1, 2), pads.down(4, 2, true))
    }

    @Test
    fun theSameStepTwiceLoopsOneStep() {
        pads.down(0, 6, true)
        assertEquals(PadAction.Loop(6, 6), pads.down(1, 6, true))
    }

    @Test
    fun shiftTapAsksForAShiftTapAndNoLongerSelects() {
        assertNull(pads.down(0, 5, true))
        assertEquals(PadAction.ShiftTap(5), pads.up(0))
        assertTrue(pads.selected.isEmpty())
    }

    // --- build-22: drags, copies, swipes ---

    @Test
    fun aDragOntoAnotherPadMovesAndNeverToggles() {
        pads.down(0, 3, false)
        assertNull(pads.move(0, 3, drifted = true))
        assertNull("a drifting touch cannot become a long-press", pads.longPress(0))
        assertNull(pads.move(0, 6, true))
        assertEquals(PadAction.Move(3, 6, copy = false), pads.up(0))
        assertTrue(pads.selected.isEmpty())
    }

    @Test
    fun aDriftThatComesBackToItsPadCancels() {
        pads.down(0, 3, false)
        pads.move(0, 4, true)
        pads.move(0, 3, true)
        assertNull("read without risk: no toggle", pads.up(0))
    }

    @Test
    fun aSmallWobbleInsideTheSlopIsStillATap() {
        pads.down(0, 3, false)
        pads.move(0, 3, drifted = false)
        assertEquals(PadAction.Toggle(3), pads.up(0))
    }

    @Test
    fun aDragLiftedOffThePadsDoesNothing() {
        pads.down(0, 3, false)
        pads.move(0, null, true)
        assertNull(pads.up(0))
    }

    @Test
    fun aLongPressedPadDraggedOntoAnotherMovesToo() {
        pads.down(0, 3, false)
        assertEquals(sel(3), pads.longPress(0))
        pads.move(0, 9, true)
        assertEquals(PadAction.Move(3, 9, copy = false), pads.up(0))
    }

    @Test
    fun padsThatJoinASelectionNeverDrag() {
        pads.down(0, 2, false)
        pads.longPress(0)
        pads.down(1, 5, false)
        pads.move(1, 8, true)
        assertNull(pads.up(1))
    }

    @Test
    fun shiftHoldThenDragCopies() {
        pads.down(0, 4, true)
        assertNull(pads.longPress(0))
        assertNull("carrying: no swipe", pads.move(0, 7, true))
        assertEquals(PadAction.Move(4, 7, copy = true), pads.up(0))
    }

    @Test
    fun aShiftHoldReleasedInPlaceIsStillAShiftTap() {
        pads.down(0, 5, true)
        pads.longPress(0)
        assertEquals(PadAction.ShiftTap(5), pads.up(0))
    }

    @Test
    fun aShiftSwipeReportsEveryPadCrossedThenEnds() {
        pads.down(0, 2, true)
        assertNull(pads.move(0, 2, true))
        assertEquals(PadAction.Swipe(1), pads.move(0, 3, true))
        assertNull(pads.move(0, 3, true))
        assertEquals(PadAction.Swipe(2), pads.move(0, 5, true))
        assertNull("off the pads", pads.move(0, null, true))
        assertEquals(PadAction.Swipe(-4), pads.move(0, 1, true))
        assertNull(pads.longPress(0))
        assertEquals(PadAction.SwipeEnd, pads.up(0))
    }

    @Test
    fun aSwipingShiftPadDoesNotFormALoop() {
        pads.down(0, 2, true)
        pads.move(0, 4, true)
        assertNull(pads.down(1, 9, true))
        assertNull(pads.loop)
    }

    // --- cancel ---

    @Test
    fun cancelForgetsHeldPadsAndClearsALoop() {
        pads.down(0, 1, true)
        pads.down(1, 2, true)
        assertEquals(PadAction.ClearLoop, pads.cancel())
        assertNull(pads.up(0))
        pads.down(2, 5, false)
        assertNull("no loop to clear", pads.cancel())
        assertNull("cancelled pads do not toggle", pads.up(2))
    }

    @Test
    fun cancelKeepsTheLatchedSelection() {
        pads.down(0, 3, false); pads.longPress(0)
        pads.cancel()
        assertEquals(listOf(3), pads.selected)
    }

    @Test
    fun releasingAnUnknownPointerDoesNothing() {
        assertNull(pads.up(42))
    }
}
