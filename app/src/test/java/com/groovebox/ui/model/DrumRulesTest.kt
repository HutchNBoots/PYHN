package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumRulesTest {
    // --- replay on release (manual 2-2A) ---

    @Test
    fun decayStartAndRevReplayTheSoundWhenTheirDragEnds() {
        for (key in listOf("drum.decay", "drum.start", "drum.reverse")) {
            assertTrue(key, DrumReplay.onRelease(key, changed = true, playing = false, soundInPattern = false))
        }
    }

    @Test
    fun paramsHeardWhileTheSoundRingsDoNotReplay() {
        for (key in listOf("drum.pitch", "drum.tone", "drum.level", "drum.pan", "drum.choke")) {
            assertFalse(key, DrumReplay.onRelease(key, changed = true, playing = false, soundInPattern = false))
        }
    }

    @Test
    fun nothingChangedNothingReplays() {
        assertFalse(DrumReplay.onRelease("drum.decay", changed = false, playing = false, soundInPattern = false))
    }

    @Test
    fun theBeatReplaysItWhenItIsInThePlayingPattern() {
        assertFalse(DrumReplay.onRelease("drum.decay", changed = true, playing = true, soundInPattern = true))
        assertTrue(DrumReplay.onRelease("drum.decay", changed = true, playing = true, soundInPattern = false))
        assertTrue(DrumReplay.onRelease("drum.decay", changed = true, playing = false, soundInPattern = true))
    }

    // --- per-sound locks ---

    @Test
    fun aLockBelongsToOneSound() {
        assertEquals("drum.decay@3", DrumLockKeys.of(3, "drum.decay"))
        assertEquals(3 to "drum.decay", DrumLockKeys.parse("drum.decay@3"))
        assertEquals(null, DrumLockKeys.parse("drum.decay"))
        assertEquals(null, DrumLockKeys.parse("drum.decay@x"))
    }

    @Test
    fun anOldKitWideLockBecomesALockOnEverySound() {
        val migrated = DrumLockKeys.migrate(setOf("drum.tone", "drum.pan@2"))
        assertEquals(DrumLayout.KEY_COUNT + 1, migrated.size)
        assertTrue((0 until 16).all { DrumLockKeys.of(it, "drum.tone") in migrated })
        assertTrue("drum.pan@2" in migrated)
    }

    // --- Rev and Choke tap cells ---

    @Test
    fun tapCellsAreTheShiftLayersToggles() {
        assertEquals(listOf(false, false, true, true), DrumLayout.shift.map { DrumTapCells.isCell(it) })
        assertTrue(DrumLayout.main.none { DrumTapCells.isCell(it) })
        assertEquals(listOf("Off", "On"), DrumTapCells.options("drum.reverse"))
        assertEquals(listOf("None", "A", "B"), DrumTapCells.options("drum.choke"))
    }

    // --- review 1: tap cells take the same 1 s still-hold lock as the strips ---

    @Test
    fun aMigratedKitWideRevLockLocksTheCellsOfEverySoundAndAHoldUnlocksIt() {
        // A v3 session locked Rev for the whole kit: after the migration each sound's Rev is locked...
        val locks = DrumLockKeys.migrate(setOf("drum.reverse")).toMutableSet()
        assertTrue(DrumLockKeys.of(5, "drum.reverse") in locks)
        // ... a tap on a locked cell does nothing, a 1 s still hold unlocks it ...
        val g = CellHold(touchSlop = 10f)
        g.down(0, 10f, 10f)
        assertEquals(CellHold.Action.Lock, g.timeout(CellHold.LOCK_HOLD_MS))
        locks -= DrumLockKeys.of(5, "drum.reverse")
        // ... and then taps choose again.
        g.down(2000, 10f, 10f)
        assertEquals(CellHold.Action.Tap, g.up(2080))
    }

    @Test
    fun aCellTapIsAQuickStillTouch() {
        val g = CellHold(touchSlop = 10f)
        g.down(0, 50f, 50f)
        assertEquals(CellHold.Action.Tap, g.up(120))
        g.down(500, 50f, 50f)
        g.move(520, 50f, 66f) // slid off: neither a tap nor a lock
        assertEquals(null, g.up(600))
        assertEquals(null, g.timeout(1600))
    }

    @Test
    fun theCellHoldFillsARingAndLocksOnce() {
        val g = CellHold(touchSlop = 10f)
        g.down(0, 0f, 0f)
        assertEquals(null, g.lockProgress(100))
        assertEquals(0.5f, g.lockProgress((StripGesture.RING_DELAY_MS + CellHold.LOCK_HOLD_MS) / 2)!!, 0.01f)
        assertEquals(null, g.timeout(999))
        assertEquals(CellHold.Action.Lock, g.timeout(1000))
        assertEquals(null, g.timeout(1100))
        assertEquals("the lift after a lock is not a tap", null, g.up(1200))
        assertEquals(null, g.lockProgress(1200))
    }

    @Test
    fun aLateTimerStillLocksOnRelease() {
        val g = CellHold(touchSlop = 10f)
        g.down(0, 0f, 0f)
        assertEquals(CellHold.Action.Lock, g.up(1050))
    }

    @Test
    fun aDiagonalMovePastTheSlopCancelsTheHold() {
        val g = CellHold(touchSlop = 10f)
        g.down(0, 0f, 0f)
        g.move(100, 8f, 8f) // 11.3 in 2D, under 10 on each axis
        assertEquals(null, g.timeout(1000))
    }
}
