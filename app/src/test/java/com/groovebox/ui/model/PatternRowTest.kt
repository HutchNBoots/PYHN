package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-22: the pattern row's gestures, chains, beat edges, the triplet offer and page follow. */
class PatternRowTest {
    private val row = PatternRowGesture(longPressMs = 500)

    @Test
    fun aShortTapTapsAndShiftCopies() {
        assertNull(row.down(0, 3, shifted = false, timeMs = 0))
        assertEquals(PatternRowAction.Tap(3), row.up(0, 120))
        row.down(1, 5, shifted = true, timeMs = 1000)
        assertEquals(PatternRowAction.CopyHere(5), row.up(1, 1100))
    }

    @Test
    fun aLongHoldSwitchesAllSlotsOnRelease() {
        row.down(0, 2, false, 0)
        assertFalse(row.isLongHeld(0, 499))
        assertTrue(row.isLongHeld(0, 500))
        assertEquals(PatternRowAction.SwitchAll(2), row.up(0, 700))
    }

    @Test
    fun holdingACellAndTappingAnotherChainsAndNeitherReleaseActs() {
        row.down(0, 0, false, 0)
        assertEquals(PatternRowAction.Chain(0, 1), row.down(1, 1, false, 900))
        assertNull(row.up(1, 950))
        assertNull("not a switch-all, though held long", row.up(0, 2000))
    }

    @Test
    fun aThirdCellDuringAChainDoesNothing() {
        row.down(0, 0, false, 0)
        row.down(1, 4, false, 10)
        assertNull(row.down(2, 7, false, 20))
        assertNull(row.up(2, 30))
        assertNull(row.up(1, 40))
        assertNull(row.up(0, 50))
        // All up: a fresh tap works again.
        row.down(3, 7, false, 100)
        assertEquals(PatternRowAction.Tap(7), row.up(3, 150))
    }

    @Test
    fun theSameCellTwiceIsNoChainAndOutOfRangeCellsAreIgnored() {
        row.down(0, 3, false, 0)
        assertNull(row.down(1, 3, false, 5))
        assertNull(row.up(1, 6))
        assertNull(row.up(0, 7))
        assertNull(row.down(2, 16, false, 0))
        assertNull(row.up(2, 1))
        assertNull(row.up(9, 1))
    }

    @Test
    fun cancelForgetsHeldCells() {
        row.down(0, 3, false, 0)
        row.cancel()
        assertNull(row.up(0, 100))
    }

    // --- chain ---

    @Test
    fun aChainQueuesTheOtherPatternWhicheverPlays() {
        val c = PatternChain(0, 1)
        assertEquals(1, c.queueFor(current = 0, queued = -1))
        assertNull(c.queueFor(0, 1))
        assertEquals(0, c.queueFor(1, -1))
        assertNull(c.queueFor(1, 0))
        assertEquals("started elsewhere: A next", 0, c.queueFor(5, -1))
        assertNull(c.queueFor(5, 0))
    }

    // --- beats and triplets ---

    @Test
    fun beatEdgesFollowTheRate() {
        assertEquals(listOf(2, 4, 8, 3), (0..3).map(StepGrid::beatSteps))
        assertEquals(listOf(0, 3, 6, 9, 12, 15), (0 until 16).filter { StepGrid.isBeat(it, 3) })
        assertEquals(listOf(0, 2, 4, 6, 8, 10, 12, 14), (0 until 16).filter { StepGrid.isBeat(it, 0) })
        assertEquals(listOf(0, 4, 8, 12), (0 until 16).filter { StepGrid.isBeat(it, 1) })
    }

    @Test
    fun tripletsOfferWholeBarsOfTwelve() {
        assertEquals(12, StepGrid.tripletOffer(3, 16))
        assertEquals(24, StepGrid.tripletOffer(3, 32))
        assertEquals(12, StepGrid.tripletOffer(3, 1))
        assertEquals(48, StepGrid.tripletOffer(3, 64))
        assertEquals(24, StepGrid.tripletOffer(3, 20))
        assertNull(StepGrid.tripletOffer(3, 24))
        assertNull(StepGrid.tripletOffer(1, 16))
    }

    // --- page follow ---

    @Test
    fun pageFollowShowsThePlayheadsPageOnlyWhenOnAndShowingThePlayingPattern() {
        val f = PageFollow()
        assertNull(f.pageFor(20, showingPlaying = true))
        f.toggle()
        assertEquals(1, f.pageFor(20, true))
        assertEquals(3, f.pageFor(63, true))
        assertNull(f.pageFor(20, showingPlaying = false))
        assertNull("stopped", f.pageFor(-1, true))
        f.stop()
        assertFalse(f.on)
    }
}
